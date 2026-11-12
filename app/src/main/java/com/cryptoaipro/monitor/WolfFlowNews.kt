package com.cryptoaipro.monitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import javax.xml.parsers.DocumentBuilderFactory

internal data class WhaleFlowReport(
    val symbol: String, val buyUsd: Double, val sellUsd: Double,
    val largeBuys: Int, val largeSells: Int, val imbalance: Double,
    val openInterest: Double, val funding: Double, val summary: String,
    val bookImbalance: Double = 0.0, val bidDepthUsd: Double = 0.0,
    val askDepthUsd: Double = 0.0, val bidWallUsd: Double = 0.0,
    val askWallUsd: Double = 0.0, val bookFresh: Boolean = false
)
internal data class MarketNewsItem(val title: String, val source: String, val link: String, val date: String, val description: String = "")

internal object WolfFlowNewsRepository {
    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "AmirArsalanTraderWolf/2.14 Android")
        connection.setRequestProperty("Accept", "application/json, application/rss+xml, application/xml")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("سرویس داده عمومی پاسخ HTTP $code داد")
            body
        } finally { connection.disconnect() }
    }

    suspend fun whale(symbolInput: String): WhaleFlowReport = withContext(Dispatchers.IO) {
        // XT-native public recent trades only. This is large-trade/order-flow estimation,
        // not proof of a whale's identity or wallet ownership.
        val normalizedSymbol = symbolInput.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
        // XT contract symbols can arrive as BTCUSDT or BTC/USDT:USDT.
        // Strip one or two quote suffixes without turning BTCUSDTUSDT into BTCUSDT_usdt.
        val baseAsset = normalizedSymbol.removeSuffix("USDTUSDT").removeSuffix("USDT")
        require(baseAsset.isNotBlank()) { "نماد پایه برای تحلیل جریان سفارش خالی است" }
        val symbol = baseAsset.lowercase(Locale.US) + "_usdt"
        require(symbol.matches(Regex("[a-z0-9_]{4,24}"))) { "نماد برای تحلیل جریان سفارش معتبر نیست" }
        val tradesRoot = org.json.JSONObject(get("https://fapi.xt.com/future/market/v1/public/q/deal?symbol=$symbol&num=50"))
        val trades = tradesRoot.optJSONArray("result") ?: JSONArray()
        val notionals = (0 until trades.length()).mapNotNull { i ->
            val t = trades.optJSONObject(i) ?: return@mapNotNull null
            val price = t.optString("p").toDoubleOrNull() ?: return@mapNotNull null
            val amount = t.optString("a").toDoubleOrNull() ?: return@mapNotNull null
            (price * amount).takeIf { it > 0.0 && it.isFinite() }
        }.sorted()
        val threshold = maxOf(25_000.0, notionals.getOrNull((notionals.size * 0.90).toInt().coerceAtMost((notionals.size - 1).coerceAtLeast(0))) ?: 25_000.0)
        var buy = 0.0; var sell = 0.0; var bigBuy = 0; var bigSell = 0
        for (i in 0 until trades.length()) {
            val t = trades.optJSONObject(i) ?: continue
            val price = t.optString("p").toDoubleOrNull() ?: continue
            val amount = t.optString("a").toDoubleOrNull() ?: continue
            val usd = price * amount
            if (!usd.isFinite() || usd <= 0.0) continue
            when (t.optString("m").uppercase(Locale.US)) {
                "BUY", "BID", "BUYER", "B" -> { buy += usd; if (usd >= threshold) bigBuy++ }
                "SELL", "ASK", "SELLER", "S" -> { sell += usd; if (usd >= threshold) bigSell++ }
            }
        }
        // Public XT futures order-book snapshot: aggregate visible bid/ask notional
        // and the largest visible wall. A snapshot is not proof that liquidity will remain.
        val book = runCatching {
            val root = org.json.JSONObject(get("https://fapi.xt.com/future/market/v1/public/cg/orderbook?symbol=$symbol&level=50"))
            val data = root.optJSONObject("result") ?: root.optJSONObject("data") ?: root
            fun levels(key: String): List<Pair<Double, Double>> {
                val rows = data.optJSONArray(key) ?: JSONArray()
                return (0 until rows.length()).mapNotNull { i ->
                    val row = rows.optJSONArray(i) ?: return@mapNotNull null
                    if (row.length() < 2) return@mapNotNull null
                    val price = row.optString(0).toDoubleOrNull() ?: return@mapNotNull null
                    val qty = row.optString(1).toDoubleOrNull() ?: return@mapNotNull null
                    if (price <= 0.0 || qty <= 0.0 || !price.isFinite() || !qty.isFinite()) null else price to qty
                }
            }
            val bids = levels("bids")
            val asks = levels("asks")
            val bidDepth = bids.sumOf { it.first * it.second }
            val askDepth = asks.sumOf { it.first * it.second }
            val totalDepth = (bidDepth + askDepth).coerceAtLeast(1.0)
            val timestamp = data.optLong("timestamp", root.optLong("timestamp", 0L))
            listOf(
                (bidDepth - askDepth) / totalDepth,
                bidDepth, askDepth,
                bids.maxOfOrNull { it.first * it.second } ?: 0.0,
                asks.maxOfOrNull { it.first * it.second } ?: 0.0,
                if (timestamp > 0L && abs(System.currentTimeMillis() - timestamp) <= 120_000L &&
                    bids.isNotEmpty() && asks.isNotEmpty()) 1.0 else 0.0
            )
        }.getOrNull()
        val bookImbalance = book?.getOrNull(0) ?: 0.0
        val bidDepthUsd = book?.getOrNull(1) ?: 0.0
        val askDepthUsd = book?.getOrNull(2) ?: 0.0
        val bidWallUsd = book?.getOrNull(3) ?: 0.0
        val askWallUsd = book?.getOrNull(4) ?: 0.0
        val bookFresh = (book?.getOrNull(5) ?: 0.0) > 0.5

        val contracts = runCatching {
            val root = get("https://fapi.xt.com/future/market/v1/public/cg/contracts")
            val rows = if (root.trim().startsWith("[")) JSONArray(root) else {
                val obj = org.json.JSONObject(root)
                obj.optJSONArray("result") ?: obj.optJSONObject("result")?.optJSONArray("items") ?: JSONArray()
            }
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull {
                it.optString("symbol").equals(symbol, true)
            }
        }.getOrNull()
        val oi = contracts?.optString("open_interest")?.toDoubleOrNull() ?: 0.0
        val rawFunding = contracts?.optString("funding_rate")?.toDoubleOrNull() ?: 0.0
        val funding = if (kotlin.math.abs(rawFunding) > 0.01) rawFunding / 100.0 else rawFunding
        val total = (buy + sell).coerceAtLeast(1.0)
        val imbalance = (buy - sell) / total
        val tradeSummary = when {
            notionals.isEmpty() -> "داده معاملات عمومی XT برای محاسبه جریان کافی نیست"
            imbalance > 0.18 && bigBuy > bigSell -> "غلبه خرید تهاجمی و معاملات بزرگ بر اساس معاملات عمومی XT"
            imbalance < -0.18 && bigSell > bigBuy -> "غلبه فروش تهاجمی و معاملات بزرگ بر اساس معاملات عمومی XT"
            else -> "جریان معاملات عمومی XT متعادل یا بدون برتری روشن است"
        }
        val bookSummary = if (!bookFresh) "؛ دفتر سفارش تازه و معتبر در دسترس نیست"
            else if (bookImbalance > 0.18) "؛ عمق قابل مشاهده سمت خرید سنگین‌تر است"
            else if (bookImbalance < -0.18) "؛ عمق قابل مشاهده سمت فروش سنگین‌تر است"
            else "؛ عمق خرید و فروش نزدیک به تعادل است"
        WhaleFlowReport(symbol, buy, sell, bigBuy, bigSell, imbalance, oi, funding,
            tradeSummary + bookSummary + " • ارزش معاملات برآوردی است؛ هویت معامله‌گران مشخص نیست",
            bookImbalance, bidDepthUsd, askDepthUsd, bidWallUsd, askWallUsd, bookFresh)
    }

    private fun headlineSentiment(text: String): Int {
        val value = " " + text.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ") + " "
        val positive = listOf(" approval ", " approved ", " adoption ", " inflow ", " partnership ", " upgrade ", " launch ", " integration ", " bullish ", " breakout ", " etf ", " listing ", " mainnet ", " funding secured ")
        val negative = listOf(" hack ", " exploit ", " bankruptcy ", " lawsuit ", " crash ", " outage ", " ban ", " insolvency ", " attack ", " liquidation ", " delist ", " breach ", " exploit ", " bearish ", " fraud ", " investigation ", " arrest ", " rejection ")
        return (positive.count { value.contains(it) } - negative.count { value.contains(it) }).coerceIn(-2, 2)
    }

    fun enrichSignals(context: android.content.Context, signals: List<MarketSignal>): List<MarketSignal> {
        val prefs = context.getSharedPreferences("wolf_background_intelligence", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val flows = runCatching { JSONArray(prefs.getString("flow_reports", "[]")) }.getOrDefault(JSONArray())
        val headlines = runCatching { JSONArray(prefs.getString("market_news", "[]")) }.getOrDefault(JSONArray())
        val newsFresh = now - prefs.getLong("market_news_updated_at", 0L) <= 2L * 60L * 60L * 1000L
        val articles = if (newsFresh) (0 until headlines.length()).mapNotNull { headlines.optJSONObject(it) } else emptyList()
        val globalTerms = listOf("crypto market", "digital assets", "market-wide", "federal reserve", "interest rate", "sec ")
        return signals.map { signal ->
            val normalized = signal.symbol.replace("/", "").replace(":USDT", "").replace("_", "").uppercase(Locale.US)
            val base = normalized.removeSuffix("USDT").removeSuffix("USD")
            val aliases = when (base) {
                "BTC" -> listOf("bitcoin", "btc")
                "ETH" -> listOf("ethereum", "ether", "eth")
                "BNB" -> listOf("binance coin", "bnb")
                "SOL" -> listOf("solana", "sol")
                "XRP" -> listOf("ripple", "xrp")
                "DOGE" -> listOf("dogecoin", "doge")
                else -> listOf(base.lowercase(Locale.US))
            }
            val related = articles.filter { article ->
                val text = (article.optString("title") + " " + article.optString("description")).lowercase(Locale.US)
                aliases.any { alias -> Regex("(^|[^a-z0-9])" + Regex.escape(alias) + "([^a-z0-9]|$)").containsMatchIn(text) }
            }.take(12)
            val assetNewsBias = related.sumOf { headlineSentiment(it.optString("title") + " " + it.optString("description")) }.coerceIn(-3, 3)
            val broadNews = articles.filter { article ->
                val text = (article.optString("title") + " " + article.optString("description")).lowercase(Locale.US)
                globalTerms.any { text.contains(it) }
            }.take(5)
            val broadBias = broadNews.sumOf { headlineSentiment(it.optString("title") + " " + it.optString("description")) }.coerceIn(-1, 1)
            val flow = (0 until flows.length()).mapNotNull { flows.optJSONObject(it) }.firstOrNull {
                it.optString("symbol").uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "") == normalized &&
                    now - it.optLong("updatedAt", 0L) in 0..(20L * 60L * 1000L) &&
                    prefs.getString("flow_exchange", "XT") == "XT"
            }
            val isLong = signal.direction == "LONG_BIAS"
            val directionalFlow = flow?.optDouble("imbalance", 0.0)?.times(if (isLong) 1.0 else -1.0) ?: 0.0
            val flowAdjustment = if (flow != null && abs(directionalFlow) >= 0.18) {
                if (directionalFlow > 0) 3 else -4
            } else 0
            val bookFresh = flow?.optBoolean("bookFresh", false) == true &&
                now - (flow?.optLong("updatedAt", 0L) ?: 0L) in 0..(20L * 60L * 1000L)
            val directionalBook = (flow?.optDouble("bookImbalance", 0.0) ?: 0.0) * if (isLong) 1.0 else -1.0
            val bookAdjustment = if (bookFresh && abs(directionalBook) >= 0.22) {
                if (directionalBook > 0) 2 else -3
            } else 0
            val funding = flow?.optDouble("funding", 0.0) ?: 0.0
            val crowdedSidePenalty = if ((isLong && funding > 0.001) || (!isLong && funding < -0.001)) -2 else 0
            val newsDirectional = (assetNewsBias + broadBias) * if (isLong) 1 else -1
            // Headlines are a low-weight risk/context modifier, never a standalone trade trigger.
            val newsAdjustment = if (signal.direction == "NEUTRAL") 0 else newsDirectional.coerceIn(-2, 2)
            val flowNote = flow?.let {
                "جریان معاملات تهاجمی تازه: " + it.optString("summary") +
                    "؛ عدم‌تعادل معاملات " + String.format(Locale.US, "%.1f%%", it.optDouble("imbalance") * 100) +
                    (if (bookFresh) "؛ عدم‌تعادل دفتر سفارش " + String.format(Locale.US, "%.1f%%", it.optDouble("bookImbalance") * 100) else "")
            } ?: "جریان معاملات درشت برای این نماد تازه/قابل‌دسترسی نیست"
            val newsNote = when {
                related.isNotEmpty() -> "خبرهای مرتبط با " + base + ": " + related.take(2).joinToString(" | ") { it.optString("title").take(110) } +
                    "؛ امتیاز احساس خبر " + assetNewsBias
                broadNews.isNotEmpty() -> "زمینه خبر کل بازار دریافت شد؛ امتیاز محدود " + broadBias
                else -> "خبر معتبر و مرتبط تازه برای این دارایی در دسترس نیست"
            }
            val researchNote = prefs.getString("auto_research", "").orEmpty().take(500)
            val adjustment = (flowAdjustment + bookAdjustment + crowdedSidePenalty + newsAdjustment).coerceIn(-7, 5)
            signal.copy(
                score = (signal.score + adjustment).coerceIn(-100, 100),
                confidence = (signal.confidence + adjustment).coerceIn(0, 95),
                reason = signal.reason + " | " + flowNote + " | " + newsNote +
                    if (researchNote.isBlank()) "" else " | پژوهش پس‌زمینه: " + researchNote
            )
        }
    }

    suspend fun refreshForEngine(context: android.content.Context, signals: List<MarketSignal>, exchange: String = "XT") {
        val prefs = context.getSharedPreferences("wolf_background_intelligence", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_refresh", 0L)
        val previousExchange = prefs.getString("last_refresh_exchange", "")
        if (now - last < 15L * 60L * 1000L && previousExchange == exchange) return
        // Use only XT USDT-M public trades; no Binance fallback or mixed-exchange flow.
        val reports = if (exchange == "XT") {
            signals.filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
                .sortedByDescending { it.volume }.take(5)
                .mapNotNull { signal -> runCatching { whale(signal.symbol) }.getOrNull() }
        } else emptyList()
        val headlines = runCatching { news() }.getOrDefault(emptyList())
        val focus = signals.maxByOrNull { it.volume }
        val researchQuery = focus?.let { "crypto futures ${it.technical.regime} ${it.technical.candlePattern} market structure order flow risk management research" } ?: "crypto futures market structure volatility risk management research"
        val research = runCatching { WolfKnowledgeSearch.answer(context, researchQuery) }.getOrNull()
        val refreshedAt = System.currentTimeMillis()
        val json = JSONArray().apply { reports.forEach { r -> put(org.json.JSONObject().apply {
            put("symbol", r.symbol); put("imbalance", r.imbalance); put("largeBuys", r.largeBuys)
            put("largeSells", r.largeSells); put("openInterest", r.openInterest); put("funding", r.funding)
            put("summary", r.summary); put("updatedAt", refreshedAt)
            put("bookImbalance", r.bookImbalance); put("bidDepthUsd", r.bidDepthUsd)
            put("askDepthUsd", r.askDepthUsd); put("bidWallUsd", r.bidWallUsd)
            put("askWallUsd", r.askWallUsd); put("bookFresh", r.bookFresh)
        }) } }
        val newsJson = JSONArray().apply { headlines.take(12).forEach { n -> put(org.json.JSONObject().apply {
            put("title", n.title); put("source", n.source); put("url", n.link); put("date", n.date)
        }) } }
        prefs.edit().putString("flow_reports", json.toString()).putString("flow_exchange", exchange)
            .putString("market_news", newsJson.toString()).putLong("market_news_updated_at", refreshedAt)
            .putString("auto_research", research?.first.orEmpty()).putString("auto_research_source", research?.second.orEmpty())
            .putLong("last_refresh", refreshedAt).putString("last_refresh_exchange", exchange).apply()
    }

    suspend fun news(): List<MarketNewsItem> = withContext(Dispatchers.IO) {
        // Prefer the multi-source public aggregator; retain independent RSS fallbacks.
        val aggregated = runCatching {
            val root = org.json.JSONObject(get("https://cryptocurrency.cv/api/news?limit=40"))
            val items = root.optJSONArray("articles") ?: root.optJSONArray("data") ?: root.optJSONArray("news") ?: JSONArray()
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val title = item.optString("title").ifBlank { item.optString("headline") }.trim()
                if (title.isBlank()) return@mapNotNull null
                val link = item.optString("link").ifBlank { item.optString("url") }
                val source = item.optString("source").ifBlank { item.optString("publisher") }.ifBlank { "Crypto News Feed" }
                val date = item.optString("pubDate").ifBlank { item.optString("publishedAt") }.ifBlank { item.optString("date") }
                val description = item.optString("description").ifBlank { item.optString("summary") }
                MarketNewsItem(title, source, link, date, description)
            }.distinctBy { it.title.lowercase(Locale.US) }.take(30)
        }.getOrDefault(emptyList())
        if (aggregated.isNotEmpty()) return@withContext aggregated

        val feeds = listOf(
            "https://www.coindesk.com/arc/outboundfeeds/rss/",
            "https://cointelegraph.com/rss"
        )
        feeds.flatMap { feed ->
            runCatching {
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                val connection = (URL(feed).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 9000
                    setRequestProperty("User-Agent", "HopeHunter/2.40.0 Android")
                    setRequestProperty("Accept", "application/rss+xml, application/xml")
                }
                try {
                    require(connection.responseCode in 200..299) { "RSS HTTP " + connection.responseCode }
                    val doc = connection.inputStream.use { factory.newDocumentBuilder().parse(it) }
                    val nodes = doc.getElementsByTagName("item")
                    (0 until minOf(nodes.length, 15)).mapNotNull { i ->
                        val item = nodes.item(i)
                        fun tag(name: String) = item.childNodes.let { children ->
                            (0 until children.length).firstOrNull { children.item(it).nodeName.equals(name, true) }
                                ?.let { children.item(it).textContent?.trim().orEmpty() }.orEmpty()
                        }
                        val title = tag("title")
                        if (title.isBlank()) null else MarketNewsItem(
                            title, if (feed.contains("coindesk")) "CoinDesk" else "Cointelegraph",
                            tag("link"), tag("pubDate"), tag("description")
                        )
                    }
                } finally { connection.disconnect() }
            }.getOrDefault(emptyList())
        }.distinctBy { it.title }.take(20)
    }
}

@Composable
internal fun WolfFlowNewsScreen(symbol: String) {
    var report by remember { mutableStateOf<WhaleFlowReport?>(null) }
    var news by remember { mutableStateOf<List<MarketNewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshed by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    suspend fun refresh() {
        loading = true; error = null
        try {
            val result = coroutineScope {
                val flow = async { WolfFlowNewsRepository.whale(symbol.ifBlank { "BTCUSDT" }) }
                val headlines = async { WolfFlowNewsRepository.news() }
                flow.await() to headlines.await()
            }
            report = result.first; news = result.second
            refreshed = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        } catch (e: Exception) { error = e.message ?: "دریافت اطلاعات ناموفق بود" }
        finally { loading = false }
    }
    LaunchedEffect(symbol) { refresh() }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("ردیابی جریان نهنگ‌ها", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("معاملات درشت و فشار خرید/فروش • $symbol", color = Color(0xFFB8B0A0), fontSize = 10.sp)
            }
            Button(onClick = { scope.launch { refresh() } }, enabled = !loading) { Text("بروزرسانی") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = Color(0xFFFF657A), fontSize = 11.sp) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            report?.let { r ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15130E))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.symbol + " • معاملات تجمیعی اخیر", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("خرید تهاجمی: $" + String.format(Locale.US, "%,.0f", r.buyUsd), color = Color(0xFF27D6A0), fontSize = 12.sp)
                            Text("فروش تهاجمی: $" + String.format(Locale.US, "%,.0f", r.sellUsd), color = Color(0xFFFF657A), fontSize = 12.sp)
                            Text("معاملات درشت شناسایی‌شده • خرید " + r.largeBuys + " / فروش " + r.largeSells, color = Color.White, fontSize = 11.sp)
                            Text("عدم‌تعادل معاملات: " + String.format(Locale.US, "%.1f%%", r.imbalance * 100), color = Color(0xFFFFD45A), fontWeight = FontWeight.Bold)
                            Text(if (r.bookFresh) "عدم‌تعادل دفتر سفارش: " + String.format(Locale.US, "%.1f%%", r.bookImbalance * 100) else "دفتر سفارش تازه در دسترس نیست",
                                color = if (r.bookFresh && kotlin.math.abs(r.bookImbalance) >= 0.18) Color(0xFFFFD45A) else Color(0xFFB8B0A0), fontSize = 11.sp)
                            if (r.bookFresh) {
                                Text("عمق قابل مشاهده خرید/فروش: $" + String.format(Locale.US, "%,.0f", r.bidDepthUsd) +
                                    " / $" + String.format(Locale.US, "%,.0f", r.askDepthUsd), color = Color(0xFFB8B0A0), fontSize = 10.sp)
                                Text("بزرگ‌ترین دیوار خرید/فروش: $" + String.format(Locale.US, "%,.0f", r.bidWallUsd) +
                                    " / $" + String.format(Locale.US, "%,.0f", r.askWallUsd), color = Color(0xFFB8B0A0), fontSize = 10.sp)
                            }
                            Text("Open Interest: " + String.format(Locale.US, "%,.3f", r.openInterest), color = Color(0xFFB8B0A0), fontSize = 10.sp)
                            Text("Funding Rate: " + String.format(Locale.US, "%.5f%%", r.funding * 100), color = Color(0xFFB8B0A0), fontSize = 10.sp)
                            Text(r.summary, color = Color.White, fontSize = 11.sp)
                            Text("معاملات درشت به معنی شناسایی هویت نهنگ نیست؛ این تخمین از اندازه و جهت تیکرهاست و به‌تنهایی نقطه ورود قطعی نمی‌سازد.", color = Color(0xFF9AAAC2), fontSize = 9.sp)
                        }
                    }
                }
            }
            item {
                Text("اخبار زنده بازار", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("منابع عمومی CoinDesk و Cointelegraph • آخرین دریافت: $refreshed", color = Color(0xFF9AAAC2), fontSize = 9.sp)
            }
            items(news) { n ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15130E))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(n.title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        Text(n.source + " • " + n.date, color = Color(0xFF9AAAC2), fontSize = 9.sp)
                        if (n.link.startsWith("https://")) Text(
                            n.link, color = Color(0xFFFFD45A), fontSize = 9.sp,
                            modifier = Modifier.clickable { uriHandler.openUri(n.link) }
                        )
                    }
                }
            }
            if (news.isEmpty() && !loading) item { Text("فعلاً خبری دریافت نشد؛ اتصال اینترنت یا منبع RSS را بررسی کن.", color = Color(0xFF9AAAC2), fontSize = 11.sp) }
        }
    }
}
