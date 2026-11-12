package com.cryptoaipro.monitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Private-to-the-model microstructure feature extractor.
 * It reads public XT USDT-M depth and recent public trades only.
 * Raw flow, large-trade and liquidity-wall observations are never rendered or notified.
 * It infers large-participant pressure; it cannot identify the owner of an order.
 */
internal data class HiddenFlowFeatures(
    val directionalScore: Int,
    val fresh: Boolean,
    val depthImbalance: Double,
    val takerImbalance: Double,
    val largeTradeImbalance: Double,
    val persistentWallBias: Double
)

internal object HopeHiddenFlowEngine {
    private const val BASE = "https://fapi.xt.com"
    private val previousWalls = ConcurrentHashMap<String, Map<Long, Double>>()

    private fun get(path: String): String {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6500
            connection.readTimeout = 8500
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "HOPE-HUNTER/2.56")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("XT public flow HTTP $code")
            return body
        } finally { connection.disconnect() }
    }

    private fun resultObject(raw: String): JSONObject {
        var node = JSONObject(raw)
        repeat(4) {
            val next = node.opt("result") ?: node.opt("data")
            if (next is JSONObject) node = next else return node
        }
        return node
    }

    private fun rows(raw: String): JSONArray {
        val root = JSONObject(raw)
        val value = root.opt("result") ?: root.opt("data")
        return when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("list") ?: value.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
    }

    private fun pair(row: Any?): Pair<Double, Double>? = runCatching {
        when (row) {
            is JSONArray -> row.getString(0).toDouble() to row.getString(1).toDouble()
            is JSONObject -> {
                val p = (row.opt("price") as? Number)?.toDouble()
                    ?: row.optString("price").toDouble()
                val q = (row.opt("amount") as? Number)?.toDouble()
                    ?: (row.opt("quantity") as? Number)?.toDouble()
                    ?: row.optString("amount").toDouble()
                p to q
            }
            else -> error("invalid level")
        }
    }.getOrNull()?.takeIf { it.first > 0.0 && it.second >= 0.0 && it.first.isFinite() && it.second.isFinite() }

    private fun side(value: Any?): Int {
        val s = value?.toString()?.uppercase().orEmpty()
        return when (s) {
            "BUY", "BID", "B", "1", "TRUE" -> 1
            "SELL", "ASK", "S", "0", "FALSE" -> -1
            else -> 0
        }
    }

    suspend fun fetch(symbol: String): HiddenFlowFeatures? = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(symbol, "UTF-8")
            val depthRaw = get("/future/market/v1/public/q/depth?symbol=$encoded&level=20")
            val dealRaw = get("/future/market/v1/public/q/deal?symbol=$encoded&num=100")
            val depth = resultObject(depthRaw)
            val bids = depth.optJSONArray("a") ?: depth.optJSONArray("bids") ?: JSONArray()
            val asks = depth.optJSONArray("b") ?: depth.optJSONArray("asks") ?: JSONArray()
            require(bids.length() > 0 && asks.length() > 0) { "XT depth unavailable" }
            val bidLevels = (0 until bids.length()).mapNotNull { pair(bids.opt(it)) }
            val askLevels = (0 until asks.length()).mapNotNull { pair(asks.opt(it)) }
            require(bidLevels.isNotEmpty() && askLevels.isNotEmpty())
            val mid = (bidLevels.maxOf { it.first } + askLevels.minOf { it.first }) / 2.0
            require(mid > 0.0 && mid.isFinite())
            val bidNotional = bidLevels.sumOf { it.first * it.second }
            val askNotional = askLevels.sumOf { it.first * it.second }
            val depthImbalance = ((bidNotional - askNotional) / max(1e-9, bidNotional + askNotional)).coerceIn(-1.0, 1.0)

            val deals = rows(dealRaw)
            val trades = (0 until deals.length()).mapNotNull { i ->
                val row = deals.optJSONObject(i) ?: return@mapNotNull null
                val amount = (row.opt("a") as? Number)?.toDouble() ?: row.optString("a").toDoubleOrNull() ?: 0.0
                val price = (row.opt("p") as? Number)?.toDouble() ?: row.optString("p").toDoubleOrNull() ?: 0.0
                val direction = side(row.opt("m"))
                if (amount > 0 && price > 0 && direction != 0 && amount.isFinite() && price.isFinite())
                    Triple(direction, amount * price, row.optLong("t")) else null
            }
            val totalBuy = trades.filter { it.first > 0 }.sumOf { it.second }
            val totalSell = trades.filter { it.first < 0 }.sumOf { it.second }
            val takerImbalance = ((totalBuy - totalSell) / max(1e-9, totalBuy + totalSell)).coerceIn(-1.0, 1.0)
            val notionals = trades.map { it.second }.sorted()
            val median = notionals.getOrNull(notionals.size / 2) ?: 0.0
            val large = trades.filter { it.second >= max(median * 4.0, 1.0) }
            val largeBuy = large.filter { it.first > 0 }.sumOf { it.second }
            val largeSell = large.filter { it.first < 0 }.sumOf { it.second }
            val largeTradeImbalance = if (large.isEmpty()) 0.0 else
                ((largeBuy - largeSell) / max(1e-9, largeBuy + largeSell)).coerceIn(-1.0, 1.0)

            // Detect unusually concentrated near-mid depth and whether similar walls persist
            // across scans. This is an inference, not proof of spoofing or owner identity.
            val combined = (bidLevels + askLevels).filter { abs(it.first / mid - 1.0) <= 0.01 }
            val sizes = combined.map { it.first * it.second }.sorted()
            val typical = sizes.getOrNull(sizes.size / 2) ?: 0.0
            val walls = combined.filter { it.first * it.second >= max(typical * 4.0, 1.0) }
                .associate { (price, qty) -> (price / mid * 10000.0).toLong() to price * qty }
            val previous = previousWalls.put(symbol, walls)
            val persistentBid = walls.filterKeys { it < 10000L }.values.sum()
            val persistentAsk = walls.filterKeys { it >= 10000L }.values.sum()
            // Compare actual bid/ask levels relative to midpoint, not price-key buckets.
            val bidWall = bidLevels.filter { it.first * it.second >= max(typical * 4.0, 1.0) }.sumOf { it.first * it.second }
            val askWall = askLevels.filter { it.first * it.second >= max(typical * 4.0, 1.0) }.sumOf { it.first * it.second }
            val priorPersistence = previous?.let { old ->
                val common = walls.keys.intersect(old.keys).size.toDouble()
                common / max(1, walls.size).toDouble()
            } ?: 0.0
            val wallBias = ((bidWall - askWall) / max(1e-9, bidWall + askWall)) * (0.5 + 0.5 * priorPersistence)
            val score = (depthImbalance * 4.0 + takerImbalance * 3.0 +
                largeTradeImbalance * 2.0 + wallBias * 1.0).let { (it * 2.5).toInt() }.coerceIn(-25, 25)
            val timestamp = depth.optLong("t", System.currentTimeMillis())
            val fresh = abs(System.currentTimeMillis() - timestamp) <= 120_000L && trades.isNotEmpty()
            if (!fresh) return@runCatching HiddenFlowFeatures(0, false, 0.0, 0.0, 0.0, 0.0)
            // Keep the feature internal: caller stores only a signed bounded score.
            HiddenFlowFeatures(score, true, depthImbalance, takerImbalance, largeTradeImbalance, wallBias)
        }.getOrNull()
    }
}
