package com.cryptoaipro.monitor

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.math.abs

/** Read-only XT USDT-M public futures scanner; never calls account or order endpoints. */
internal object XtFuturesMonitor {
    private const val BASE = "https://fapi.xt.com"
    private val gate = Semaphore(5)
    // Serialize foreground and in-app scans to prevent duplicate full-universe requests.
    private val fullScanMutex = Mutex()

    private fun get(path: String): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"; c.connectTimeout = 9000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "ZORO/2.69.0")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(
                when (code) {
                    429 -> "XT محدودیت نرخ درخواست داده است؛ چند لحظه بعد دوباره تلاش کن."
                    403, 451 -> "دسترسی XT از شبکه فعلی رد شد؛ محدودیت منطقه‌ای را دور نمی‌زنیم."
                    else -> "XT HTTP $code: ${body.take(180)}"
                })
            return body
        } finally { c.disconnect() }
    }

    private fun extractRows(raw: String): JSONArray {
        if (raw.trim().startsWith("[")) return JSONArray(raw)
        var node = JSONObject(raw)
        repeat(5) {
            val next = sequenceOf("result", "data", "items", "list", "rows")
                .mapNotNull { node.opt(it) }.firstOrNull { it is JSONArray || it is JSONObject }
            when (next) {
                is JSONArray -> return next
                is JSONObject -> node = next
                else -> return JSONArray()
            }
        }
        return JSONArray()
    }

    private fun parseCandle(row: Any?): DoubleArray? = runCatching {
        when (row) {
            is JSONArray -> {
                require(row.length() >= 6)
                val ts = row.getDouble(0)
                doubleArrayOf(row.getDouble(1), row.getDouble(2), row.getDouble(3), row.getDouble(4),
                    row.getDouble(5), if (ts < 10_000_000_000.0) ts * 1000.0 else ts)
            }
            is JSONObject -> {
                fun num(vararg keys: String): Double {
                    for (key in keys) {
                        val v = row.opt(key)
                        val n = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
                        if (n != null) return n
                    }
                    error("Missing XT candle field: ${keys.joinToString("/")}")
                }
                val ts = num("time", "timestamp", "ts", "t", "openTime")
                doubleArrayOf(num("open", "o"), num("high", "h"), num("low", "l"),
                    num("close", "c"), num("volume", "vol", "v"),
                    if (ts < 10_000_000_000.0) ts * 1000.0 else ts)
            }
            else -> null
        }
    }.getOrNull()

    private suspend fun candles(symbol: String, interval: String): List<DoubleArray> = gate.withPermit {
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(symbol, "UTF-8")
            val raw = get("/future/market/v1/public/q/kline?symbol=$encoded&interval=$interval&limit=500")
            val rows = extractRows(raw)
            val parsed = (0 until rows.length()).mapNotNull { parseCandle(rows.opt(it)) }
                .sortedBy { it[5] }.distinctBy { it[5].toLong() }
            val ms = when (interval) {
                "5m" -> 300000L
                "15m" -> 900000L
                "1h" -> 3600000L
                "4h" -> 14400000L
                "1d" -> 86400000L
                else -> error("Unsupported XT timeframe")
            }
            val closed = parsed.filter { it[5].toLong() + ms <= System.currentTimeMillis() }
            require(closed.size >= 210) { "XT برای $symbol کندل بسته‌شده کافی برنگرداند (${closed.size})" }
            require(closed.all { x -> x.all(Double::isFinite) && x[0] > 0 && x[1] >= maxOf(x[0], x[3]) &&
                x[2] <= minOf(x[0], x[3]) && x[1] >= x[2] && x[4] >= 0 && x[5] > 0 }) { "ساختار OHLCV دریافتی XT معتبر نیست: $symbol" }
            require(closed.zipWithNext().all { (a,b) -> b[5] > a[5] && b[5]-a[5] <= ms*2.0 }) { "کندل‌های XT تکراری یا دارای شکاف زمانی هستند: $symbol" }
            require(System.currentTimeMillis() - closed.last()[5] - ms <= ms*2.0) { "داده کندل XT قدیمی است: $symbol" }
            closed
        }
    }

    internal suspend fun historicalCandles(
        symbol: String, interval: String, startTime: Long, limit: Int = 1000
    ): List<DoubleArray> = withContext(Dispatchers.IO) {
        val apiSymbol = symbol.lowercase().replace("-", "_").replace("/", "_").removeSuffix(":usdt").let {
            if (it.endsWith("_usdt")) it else "${it}_usdt"
        }
        require(apiSymbol.matches(Regex("[a-z0-9_]{3,40}"))) { "نماد XT نامعتبر است" }
        val intervalMs = when (interval) {
            "5m" -> 5L * 60_000L
            "15m" -> 15L * 60_000L
            "1h" -> 60L * 60_000L
            "4h" -> 4L * 60L * 60_000L
            else -> throw IllegalArgumentException("Unsupported XT historical interval: $interval")
        }
        require(limit in 300..30_000) { "سقف تاریخچه درخواستی خارج از محدوده امن است." }
        val encoded = URLEncoder.encode(apiSymbol, "UTF-8")
        val now = System.currentTimeMillis()
        val pageSize = 1000
        val collected = linkedMapOf<Long, DoubleArray>()
        var cursor = startTime
        var pages = 0
        val maxPages = (limit + pageSize - 1) / pageSize
        // XT caps a single kline response. Page forward by the last candle timestamp
        // so a 90-day replay is not silently reduced to the most recent ~3.5 days.
        while (collected.size < limit && pages < maxPages) {
            currentCoroutineContext().ensureActive()
            val raw = get("/future/market/v1/public/q/kline?symbol=$encoded&interval=$interval&startTime=$cursor&limit=$pageSize")
            val rows = extractRows(raw)
            val page = (0 until rows.length()).mapNotNull { parseCandle(rows.opt(it)) }
                .filter { it[5].toLong() >= cursor && it[5].toLong() + intervalMs <= now }
                .sortedBy { it[5] }.distinctBy { it[5].toLong() }
            if (page.isEmpty()) break
            page.forEach { collected[it[5].toLong()] = it }
            val nextCursor = page.last()[5].toLong() + intervalMs
            if (nextCursor <= cursor) break
            cursor = nextCursor
            pages++
            if (page.size < pageSize) break
        }
        val parsed = collected.values.sortedBy { it[5] }.take(limit)
        require(parsed.size >= 210) {
            "تاریخچه XT برای $interval کافی نیست؛ کندل بسته‌شده: ${parsed.size}"
        }
        require(parsed.all { x -> x.all(Double::isFinite) && x[0] > 0 && x[1] >= maxOf(x[0], x[3]) &&
            x[2] <= minOf(x[0], x[3]) && x[1] >= x[2] && x[4] >= 0 && x[5] > 0 }) {
            "ساختار OHLCV تاریخی XT معتبر نیست: $symbol"
        }
        require(parsed.zipWithNext().all { (a,b) -> b[5] > a[5] && b[5]-a[5] <= intervalMs*2.0 }) {
            "تاریخچه XT دارای تکرار یا شکاف زمانی است: $symbol"
        }
        parsed
    }

    suspend fun scan(onProgress: suspend (List<MarketSignal>, Int, Int, Int) -> Unit = { _,_,_,_ -> }): List<MarketSignal> =
        fullScanMutex.withLock {
        withContext(Dispatchers.IO) {
            // Pull broad-market TOTAL/TOTAL2/TOTAL3 context once per scan; never expose session clocks.
            val marketContext = HopeMacroMarketEngine.fetch()
            val contracts = extractRows(get("/future/market/v1/public/cg/contracts"))
            val universe = (0 until contracts.length()).mapNotNull { contracts.optJSONObject(it) }
                .filter { c -> c.optString("product_type").equals("PERPETUAL", true) &&
                    (c.optString("target_currency").equals("USDT", true) ||
                     c.optString("ticker_id").endsWith("-USDT", true) ||
                     c.optString("symbol").endsWith("_usdt", true)) }
                .mapNotNull { c ->
                    val symbol = c.optString("symbol").lowercase()
                    val price = c.optString("last_price").toDoubleOrNull() ?: c.optDouble("last_price")
                    val volume = c.optString("target_volume").toDoubleOrNull() ?: c.optDouble("target_volume")
                    val display = c.optString("ticker_id").ifBlank { c.optString("symbol").uppercase().replace("_","-") }
                    if (symbol.isBlank() || price <= 0 || volume <= 0) null else Triple(symbol, display, c)
                }.distinctBy { it.first }
                .sortedByDescending { it.third.optString("target_volume").toDoubleOrNull() ?: it.third.optDouble("target_volume") }
            require(universe.isNotEmpty()) { "از فهرست قراردادهای XT هیچ قرارداد دائمی USDT قابل تشخیصی دریافت نشد." }
            val results = mutableListOf<MarketSignal>()
            var completed = 0; var failed = 0
            for (batch in universe.chunked(8)) {
                val outcomes = coroutineScope {
                    batch.map { item -> async {
                        runCatching {
                            val c = item.third
                            val price = c.optString("last_price").toDoubleOrNull() ?: c.optDouble("last_price")
                            val open = c.optString("open").toDoubleOrNull() ?: c.optDouble("open", price)
                            val change = if (open > 0) (price/open-1)*100 else c.optDouble("price_change_percent")
                            val volume = c.optString("target_volume").toDoubleOrNull() ?: c.optDouble("target_volume")
                            val f = coroutineScope {
                                val micro = async { candles(item.first,"5m") }
                                val a = async { candles(item.first,"15m") }
                                val b = async { candles(item.first,"1h") }
                                val d = async { candles(item.first,"4h") }
                                // Daily context is useful but optional: a missing daily endpoint
                                // must not discard otherwise valid intraday market analysis.
                                val macro = async { runCatching { candles(item.first,"1d") }.getOrNull() }
                                listOf(micro.await(), a.await(), b.await(), d.await()) to macro.await()
                            }
                            MarketAnalysisEngine.analyzeWithCandles(
                                item.second, price, change, volume,
                                f.first[1], f.first[2], f.first[3],
                                micro = f.first[0], macro = f.second,
                                marketContext = marketContext
                            )
                        }
                    } }.awaitAll()
                }
                outcomes.forEach { it.onSuccess { s -> results.add(s) }.onFailure { failed++ } }
                completed += batch.size
                onProgress(results.sortedByDescending { abs(it.score) },completed,universe.size,failed)
            }
            if (results.isEmpty()) throw IllegalStateException("اسکن XT هیچ کندل معتبر دریافت نکرد؛ تعداد نمادهای ناموفق: $failed")

            // Expensive microstructure sampling is restricted to the 20 most liquid valid
            // contracts, while the main XT candle scan still covers the full USDT-M universe.
            // All raw observations remain hidden; only a bounded signed score reaches the brain.
            val flowCandidates = results.sortedByDescending { it.volume }.take(20)
            val flowSnapshots = coroutineScope {
                flowCandidates.map { signal -> async {
                    signal.symbol to HopeHiddenFlowEngine.fetch(
                        signal.symbol.lowercase().replace("-", "_").replace("/", "_").removeSuffix(":usdt")
                    )
                } }.awaitAll().toMap()
            }
            val enriched = results.map { signal ->
                val flow = flowSnapshots[signal.symbol]
                if (flow?.fresh == true) signal.copy(
                    hiddenFlowScore = flow.directionalScore.coerceIn(-25, 25),
                    hiddenFlowFresh = true
                ) else signal
            }
            enriched.sortedByDescending { abs(it.score) }.also { onProgress(it,completed,universe.size,failed) }
        }
        }
}
