package com.cryptoaipro.monitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import javax.xml.parsers.DocumentBuilderFactory

internal data class WhaleFlowReport(
    val symbol: String, val buyUsd: Double, val sellUsd: Double,
    val largeBuys: Int, val largeSells: Int, val imbalance: Double,
    val openInterest: Double, val funding: Double, val summary: String
)
internal data class MarketNewsItem(val title: String, val source: String, val link: String, val date: String)

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
        val symbol = symbolInput.replace("/", "").replace(":USDT", "").replace("_", "").uppercase(Locale.US)
        require(symbol.matches(Regex("[A-Z0-9]{4,24}"))) { "نماد برای تحلیل جریان سفارش معتبر نیست" }
        val trades = JSONArray(get("https://fapi.binance.com/fapi/v1/aggTrades?symbol=$symbol&limit=1000"))
        val notionals = (0 until trades.length()).map { i ->
            val t = trades.getJSONObject(i)
            t.optString("p").toDoubleOrNull()?.times(t.optString("q").toDoubleOrNull() ?: 0.0) ?: 0.0
        }.filter { it > 0.0 }.sorted()
        val threshold = maxOf(25_000.0, notionals.getOrNull((notionals.size * 0.90).toInt().coerceAtMost((notionals.size - 1).coerceAtLeast(0))) ?: 25_000.0)
        var buy = 0.0; var sell = 0.0; var bigBuy = 0; var bigSell = 0
        for (i in 0 until trades.length()) {
            val t = trades.getJSONObject(i)
            val usd = (t.optString("p").toDoubleOrNull() ?: 0.0) * (t.optString("q").toDoubleOrNull() ?: 0.0)
            val buyerIsMaker = t.optBoolean("m", false)
            if (buyerIsMaker) { sell += usd; if (usd >= threshold) bigSell++ }
            else { buy += usd; if (usd >= threshold) bigBuy++ }
        }
        val oi = runCatching {
            org.json.JSONObject(get("https://fapi.binance.com/fapi/v1/openInterest?symbol=$symbol")).optString("openInterest").toDoubleOrNull() ?: 0.0
        }.getOrDefault(0.0)
        val funding = runCatching {
            org.json.JSONObject(get("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$symbol")).optString("lastFundingRate").toDoubleOrNull() ?: 0.0
        }.getOrDefault(0.0)
        val total = (buy + sell).coerceAtLeast(1.0)
        val imbalance = (buy - sell) / total
        val summary = when {
            imbalance > 0.18 && bigBuy > bigSell -> "غلبه خرید تهاجمی و معاملات درشت؛ احتمال جذب نقدینگی فروشندگان"
            imbalance < -0.18 && bigSell > bigBuy -> "غلبه فروش تهاجمی و معاملات درشت؛ فشار عرضه بیشتر است"
            else -> "جریان سفارش متعادل یا بدون برتری روشن؛ منتظر تأیید ساختار قیمت بمان"
        }
        WhaleFlowReport(symbol, buy, sell, bigBuy, bigSell, imbalance, oi, funding, summary)
    }


    fun enrichSignals(context: android.content.Context, signals: List<MarketSignal>): List<MarketSignal> {
        val prefs = context.getSharedPreferences("wolf_background_intelligence", android.content.Context.MODE_PRIVATE)
        val flows = runCatching { JSONArray(prefs.getString("flow_reports", "[]")) }.getOrDefault(JSONArray())
        val headlines = runCatching { JSONArray(prefs.getString("market_news", "[]")) }.getOrDefault(JSONArray())
        val newsContext = (0 until minOf(3, headlines.length())).mapNotNull { headlines.optJSONObject(it)?.optString("title")?.takeIf { title -> title.isNotBlank() } }.joinToString(" | ")
        return signals.map { signal ->
            val flow = (0 until flows.length()).mapNotNull { flows.optJSONObject(it) }.firstOrNull { it.optString("symbol") == signal.symbol.replace("/", "").replace(":USDT", "") }
            val flowNote = flow?.let { "جریان معاملات درشت: " + it.optString("summary") + "؛ عدم‌تعادل " + String.format(Locale.US, "%.1f%%", it.optDouble("imbalance") * 100) } ?: "جریان نهنگ هنوز داده تأییدشده‌ای برای این نماد ندارد"
            val newsNote = if (newsContext.isBlank()) "اخبار بازار هنوز دریافت نشده" else "زمینه خبری عمومی: " + newsContext
            val researchNote = prefs.getString("auto_research", "").orEmpty().take(900)
            signal.copy(reason = signal.reason + " | " + flowNote + " | " + newsNote + if (researchNote.isBlank()) "" else " | یادداشت پژوهش خودکار: " + researchNote)
        }
    }

    suspend fun refreshForEngine(context: android.content.Context, signals: List<MarketSignal>) {
        val last = context.getSharedPreferences("wolf_background_intelligence", android.content.Context.MODE_PRIVATE).getLong("last_refresh", 0L)
        if (System.currentTimeMillis() - last < 15L * 60L * 1000L) return
        val reports = signals.filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }.sortedByDescending { it.volume }.take(3).mapNotNull { signal -> runCatching { whale(signal.symbol) }.getOrNull() }
        val headlines = runCatching { news() }.getOrDefault(emptyList())
        val focus = signals.maxByOrNull { it.volume }
        val researchQuery = focus?.let { "crypto futures ${it.technical.regime} ${it.technical.candlePattern} market structure order flow risk management research" } ?: "crypto futures market structure volatility risk management research"
        val research = runCatching { WolfKnowledgeSearch.answer(researchQuery) }.getOrNull()
        val json = JSONArray().apply { reports.forEach { r -> put(org.json.JSONObject().apply { put("symbol", r.symbol); put("imbalance", r.imbalance); put("largeBuys", r.largeBuys); put("largeSells", r.largeSells); put("openInterest", r.openInterest); put("funding", r.funding); put("summary", r.summary); put("updatedAt", System.currentTimeMillis()) }) } }
        val newsJson = JSONArray().apply { headlines.take(12).forEach { n -> put(org.json.JSONObject().apply { put("title", n.title); put("source", n.source); put("url", n.link); put("date", n.date) }) } }
        context.getSharedPreferences("wolf_background_intelligence", android.content.Context.MODE_PRIVATE).edit().putString("flow_reports", json.toString()).putString("market_news", newsJson.toString()).putString("auto_research", research?.first.orEmpty()).putString("auto_research_source", research?.second.orEmpty()).putLong("last_refresh", System.currentTimeMillis()).apply()
    }

    suspend fun news(): List<MarketNewsItem> = withContext(Dispatchers.IO) {
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
                    setRequestProperty("User-Agent", "AmirArsalanTraderWolf/2.14 Android")
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
                        if (title.isBlank()) null else MarketNewsItem(title, if (feed.contains("coindesk")) "CoinDesk" else "Cointelegraph", tag("link"), tag("pubDate"))
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
                Text("معاملات درشت و فشار خرید/فروش • $symbol", color = Color(0xFF9AAAC2), fontSize = 10.sp)
            }
            Button(onClick = { scope.launch { refresh() } }, enabled = !loading) { Text("بروزرسانی") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = Color(0xFFFF657A), fontSize = 11.sp) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            report?.let { r ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151F33))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.symbol + " • معاملات تجمیعی اخیر", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("خرید تهاجمی: $" + String.format(Locale.US, "%,.0f", r.buyUsd), color = Color(0xFF27D6A0), fontSize = 12.sp)
                            Text("فروش تهاجمی: $" + String.format(Locale.US, "%,.0f", r.sellUsd), color = Color(0xFFFF657A), fontSize = 12.sp)
                            Text("معاملات درشت شناسایی‌شده • خرید " + r.largeBuys + " / فروش " + r.largeSells, color = Color.White, fontSize = 11.sp)
                            Text("عدم‌تعادل جریان: " + String.format(Locale.US, "%.1f%%", r.imbalance * 100), color = Color(0xFF55B8FF), fontWeight = FontWeight.Bold)
                            Text("Open Interest: " + String.format(Locale.US, "%,.3f", r.openInterest), color = Color(0xFF9AAAC2), fontSize = 10.sp)
                            Text("Funding Rate: " + String.format(Locale.US, "%.5f%%", r.funding * 100), color = Color(0xFF9AAAC2), fontSize = 10.sp)
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
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151F33))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(n.title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        Text(n.source + " • " + n.date, color = Color(0xFF9AAAC2), fontSize = 9.sp)
                        if (n.link.startsWith("https://")) Text(n.link, color = Color(0xFF55B8FF), fontSize = 9.sp)
                    }
                }
            }
            if (news.isEmpty() && !loading) item { Text("فعلاً خبری دریافت نشد؛ اتصال اینترنت یا منبع RSS را بررسی کن.", color = Color(0xFF9AAAC2), fontSize = 11.sp) }
        }
    }
}
