package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ZoroSentimentNode(
    val id: String,
    val label: String,
    val score: Int,
    val confidence: Int,
    val evidenceCount: Int,
    val summary: String
)

internal data class ZoroSentimentEdge(
    val from: String,
    val to: String,
    val weight: Double,
    val label: String
)

internal data class ZoroSentimentSnapshot(
    val marketScore: Int,
    val marketLabel: String,
    val fearGreed: Int?,
    val fearGreedLabel: String,
    val articleCount: Int,
    val sourceCount: Int,
    val updatedAtMs: Long,
    val nodes: List<ZoroSentimentNode>,
    val edges: List<ZoroSentimentEdge>
)

internal object ZoroSentimentNetwork {
    private val positive = listOf(
        "approval" to 2, "approved" to 2, "adoption" to 2, "inflow" to 2,
        "partnership" to 1, "upgrade" to 1, "bullish" to 2, "breakout" to 1,
        "etf" to 1, "listing" to 1, "mainnet" to 1, "surge" to 2,
        "rally" to 2, "record high" to 2, "integration" to 1, "accumulation" to 1,
        "صعود" to 2, "رشد" to 2, "تایید" to 2, "تأیید" to 2, "پذیرش" to 2,
        "ورود سرمایه" to 2, "انباشت" to 1, "شکست مقاومت" to 1, "رکورد" to 2
    )
    private val negative = listOf(
        "hack" to 3, "exploit" to 3, "bankruptcy" to 3, "lawsuit" to 2,
        "crash" to 3, "outage" to 2, "ban" to 2, "insolvency" to 3,
        "attack" to 2, "liquidation" to 2, "delist" to 2, "breach" to 3,
        "bearish" to 2, "fraud" to 3, "investigation" to 1, "rejection" to 2,
        "selloff" to 2, "plunge" to 3, "exploit" to 3,
        "سقوط" to 3, "هک" to 3, "حمله" to 2, "ورشکستگی" to 3,
        "ممنوعیت" to 2, "شکایت" to 2, "ریزش" to 2, "فروش سنگین" to 2,
        "لیکویید" to 2, "کلاهبرداری" to 3, "رد درخواست" to 2
    )
    private val negations = listOf(" not ", " no ", " without ", " fails to ", "denies ", "نیست", "نشد", "بدون", "عدم")
    private val intensifiers = listOf(" sharply", " massive", " major", " severe", " record", " شدید", "سنگین", "بزرگ")

    fun analyzeText(text: String): Int {
        val normalized = " " + text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ") + " "
        var raw = 0.0
        positive.forEach { (term, weight) ->
            val count = Regex("(^| )" + Regex.escape(term) + "( |$)").findAll(normalized).count()
            if (count > 0) {
                val start = normalized.indexOf(term)
                val context = normalized.substring(maxOf(0, start - 24), start)
                val polarity = if (negations.any { context.endsWith(it.trim()) }) -1.0 else 1.0
                val boost = if (intensifiers.any { context.contains(it.trim()) }) 1.35 else 1.0
                raw += weight * count.coerceAtMost(2) * polarity * boost
            }
        }
        negative.forEach { (term, weight) ->
            val count = Regex("(^| )" + Regex.escape(term) + "( |$)").findAll(normalized).count()
            if (count > 0) {
                val start = normalized.indexOf(term)
                val context = normalized.substring(maxOf(0, start - 24), start)
                val polarity = if (negations.any { context.endsWith(it.trim()) }) -1.0 else 1.0
                val boost = if (intensifiers.any { context.contains(it.trim()) }) 1.35 else 1.0
                raw -= weight * count.coerceAtMost(2) * polarity * boost
            }
        }
        return (raw * 18.0).roundToInt().coerceIn(-100, 100)
    }

    fun label(score: Int): String = when {
        score >= 45 -> "بسیار صعودی"
        score >= 15 -> "صعودی"
        score <= -45 -> "بسیار نزولی"
        score <= -15 -> "نزولی"
        else -> "خنثی / مختلط"
    }

    fun build(
        symbols: List<String>,
        articles: List<MarketNewsItem>,
        flows: Map<String, Pair<Double, Double>>,
        fearGreed: Int?,
        now: Long = System.currentTimeMillis()
    ): ZoroSentimentSnapshot {
        val sourceCount = articles.map { it.source.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }.distinct().size
        val globalArticles = articles.filter { article ->
            val t = (article.title + " " + article.description).lowercase(Locale.ROOT)
            listOf("crypto market", "digital assets", "federal reserve", "interest rate",
                "market-wide", "بازار ارز دیجیتال", "بازار کریپتو", "نرخ بهره").any { t.contains(it) }
        }
        val globalScores = globalArticles.map { analyzeText(it.title + " " + it.description) }
        val newsMarket = if (globalScores.isEmpty()) 0 else globalScores.average().roundToInt()
        val fgScore = fearGreed?.let { ((it.coerceIn(0, 100) - 50) * 1.2).roundToInt() } ?: 0
        val marketScore = (newsMarket * 0.55 + fgScore * 0.30).roundToInt().coerceIn(-100, 100)

        val nodes = symbols.distinct().take(24).map { rawSymbol ->
            val symbol = rawSymbol.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
                .removeSuffix("USDT").removeSuffix("USD")
            val aliases = aliases(symbol)
            val related = articles.filter { article ->
                val body = (article.title + " " + article.description).lowercase(Locale.ROOT)
                aliases.any { alias ->
                    Regex("(^|[^a-z0-9])" + Regex.escape(alias.lowercase(Locale.ROOT)) + "([^a-z0-9]|$)")
                        .containsMatchIn(body)
                }
            }.take(20)
            val scores = related.map { analyzeText(it.title + " " + it.description) }
            val newsScore = if (scores.isEmpty()) 0.0 else scores.average()
            val flow = flows[rawSymbol.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")]
            val flowScore = flow?.first?.coerceIn(-1.0, 1.0)?.times(55.0) ?: 0.0
            val fundingScore = flow?.second?.let { (-it * 8000.0).coerceIn(-25.0, 25.0) } ?: 0.0
            val propagation = if (symbol == "BTC") 0.0 else marketScore * if (symbol == "ETH") 0.24 else 0.14
            val confidence = when {
                related.size >= 5 && sourceCount >= 3 -> 82
                related.size >= 3 -> 68
                related.isNotEmpty() -> 48
                flow != null -> 42
                else -> 12
            }
            val score = (newsScore * 0.55 + flowScore * 0.28 + fundingScore * 0.07 + propagation * 0.10)
                .roundToInt().coerceIn(-100, 100)
            ZoroSentimentNode(
                id = symbol,
                label = symbol,
                score = score,
                confidence = confidence,
                evidenceCount = related.size + if (flow != null) 1 else 0,
                summary = when {
                    related.isEmpty() && flow == null -> "شواهد مستقیم کافی نیست؛ انتقال احساس بازار با وزن کم"
                    related.isEmpty() -> "جریان سفارش و نرخ فاندینگ؛ بدون خبر مستقیم"
                    else -> "${related.size} خبر مرتبط از ${related.map { it.source }.distinct().size} منبع"
                }
            )
        }
        val edges = mutableListOf<ZoroSentimentEdge>()
        nodes.filter { it.id != "BTC" }.forEach { node ->
            edges += ZoroSentimentEdge("BTC", node.id, if (node.id == "ETH") 0.24 else 0.14, "سرایت احساس بازار")
        }
        nodes.filter { it.id != "ETH" && it.id != "BTC" }.forEach { node ->
            edges += ZoroSentimentEdge("ETH", node.id, 0.08, "هم‌حرکتی آلت‌کوین‌ها")
        }
        if (fearGreed != null) edges += ZoroSentimentEdge("FEAR_GREED", "MARKET", 0.30, "شاخص ترس و طمع")
        edges += ZoroSentimentEdge("NEWS", "MARKET", 0.55, "احساس تیترها")
        edges += ZoroSentimentEdge("ORDER_FLOW", "ASSETS", 0.28, "عدم‌تعادل معاملات")
        val fgLabel = fearGreed?.let {
            when {
                it <= 20 -> "ترس شدید"
                it <= 40 -> "ترس"
                it <= 60 -> "خنثی"
                it <= 80 -> "طمع"
                else -> "طمع شدید"
            }
        } ?: "داده در دسترس نیست"
        return ZoroSentimentSnapshot(
            marketScore, label(marketScore), fearGreed, fgLabel, articles.size,
            sourceCount, now, nodes, edges
        )
    }

    suspend fun refresh(context: Context, symbols: List<String>): ZoroSentimentSnapshot =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("wolf_background_intelligence", Context.MODE_PRIVATE)
            // Reuse the batch just fetched by WolfFlowNewsRepository; avoid a second
            // news request during the same intelligence refresh cycle.
            val cachedNewsAt = prefs.getLong("market_news_updated_at", 0L)
            val cachedNews = runCatching { JSONArray(prefs.getString("market_news", "[]")) }
                .getOrDefault(JSONArray())
            val cachedArticles = (0 until cachedNews.length()).mapNotNull { index ->
                val row = cachedNews.optJSONObject(index) ?: return@mapNotNull null
                val title = row.optString("title").trim()
                if (title.isBlank()) null else MarketNewsItem(
                    title,
                    row.optString("source"),
                    row.optString("url"),
                    row.optString("date"),
                    row.optString("description")
                )
            }
            val articles = if (cachedArticles.isNotEmpty() &&
                System.currentTimeMillis() - cachedNewsAt in 0..(2L * 60L * 60L * 1000L)
            ) cachedArticles else runCatching { WolfFlowNewsRepository.news() }.getOrDefault(emptyList())
            val fearGreed = runCatching {
                val connection = URL("https://api.alternative.me/fng/?limit=1&format=json")
                    .openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 6000
                    connection.readTimeout = 8000
                    val code = connection.responseCode
                    require(code in 200..299) { "Fear & Greed HTTP $code" }
                    val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    root.optJSONArray("data")?.optJSONObject(0)?.optString("value")?.toIntOrNull()
                } finally { connection.disconnect() }
            }.getOrNull()
            val flowRows = runCatching { JSONArray(prefs.getString("flow_reports", "[]")) }.getOrDefault(JSONArray())
            val flows = mutableMapOf<String, Pair<Double, Double>>()
            for (i in 0 until flowRows.length()) {
                val row = flowRows.optJSONObject(i) ?: continue
                val updated = row.optLong("updatedAt", 0L)
                if (System.currentTimeMillis() - updated !in 0..(30L * 60L * 1000L)) continue
                val key = row.optString("symbol").uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
                flows[key] = row.optDouble("imbalance", 0.0) to row.optDouble("funding", 0.0)
            }
            val snapshot = build(symbols, articles, flows, fearGreed)
            val encoded = JSONObject().apply {
                put("marketScore", snapshot.marketScore)
                put("marketLabel", snapshot.marketLabel)
                put("fearGreed", snapshot.fearGreed ?: JSONObject.NULL)
                put("updatedAt", snapshot.updatedAtMs)
                put("nodes", JSONArray().apply {
                    snapshot.nodes.forEach { node -> put(JSONObject().apply {
                        put("id", node.id); put("score", node.score); put("confidence", node.confidence)
                        put("evidenceCount", node.evidenceCount); put("summary", node.summary)
                    }) }
                })
            }
            prefs.edit().putString("sentiment_network_snapshot", encoded.toString()).apply()
            snapshot
        }

    private fun aliases(symbol: String): List<String> = when (symbol) {
        "BTC" -> listOf("bitcoin", "btc")
        "ETH" -> listOf("ethereum", "ether", "eth")
        "BNB" -> listOf("binance coin", "bnb")
        "SOL" -> listOf("solana", "sol")
        "XRP" -> listOf("ripple", "xrp")
        "DOGE" -> listOf("dogecoin", "doge")
        "ADA" -> listOf("cardano", "ada")
        "AVAX" -> listOf("avalanche", "avax")
        "LINK" -> listOf("chainlink", "link")
        else -> listOf(symbol.lowercase(Locale.ROOT))
    }
}

@Composable
internal fun ZoroSentimentNetworkPanel(context: Context, symbols: List<String>) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<ZoroSentimentSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("برای دریافت خبرها، ترس و طمع و ساخت شبکه احساسات، به‌روزرسانی را بزن.") }
    val gold = Color(0xFFFFD45A)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF15130F))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ZORO • شبکه احساسات بازار", color = gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("ترکیب خبرهای چندمنبعی، Fear & Greed، جریان معاملات XT و سرایت کم‌وزن احساس BTC/ETH به آلت‌کوین‌ها",
                color = Color(0xFFB8B0A0), fontSize = 10.sp)
            Button(enabled = !loading, onClick = {
                loading = true
                status = "در حال جمع‌آوری و تحلیل منابع عمومی…"
                scope.launch {
                    val result = runCatching { ZoroSentimentNetwork.refresh(context, symbols) }
                    snapshot = result.getOrNull()
                    status = result.exceptionOrNull()?.message ?: "شبکه احساسات به‌روزرسانی شد؛ شواهد و تازگی داده‌ها در امتیاز لحاظ شده‌اند."
                    loading = false
                }
            }) { Text(if (loading) "در حال تحلیل…" else "به‌روزرسانی شبکه احساسات") }
            if (loading) CircularProgressIndicator(color = gold)
            Text(status, color = Color(0xFFB8B0A0), fontSize = 10.sp)
            snapshot?.let { data ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("احساس کل بازار", color = Color.LightGray, fontSize = 10.sp)
                        Text("${data.marketScore} / 100", color = gold, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text(data.marketLabel, color = Color.White, fontSize = 11.sp)
                    }
                    Column {
                        Text("ترس و طمع", color = Color.LightGray, fontSize = 10.sp)
                        Text(data.fearGreed?.toString() ?: "—", color = gold, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text(data.fearGreedLabel, color = Color.White, fontSize = 11.sp)
                    }
                }
                Text("خبر: ${data.articleCount} • منابع مستقل: ${data.sourceCount} • یال‌های شبکه: ${data.edges.size}",
                    color = Color(0xFFB8B0A0), fontSize = 10.sp)
                data.nodes.sortedByDescending { abs(it.score) }.take(12).forEach { node ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(node.label + " • " + ZoroSentimentNetwork.label(node.score),
                                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            Text(node.summary, color = Color(0xFFB8B0A0), fontSize = 9.sp)
                        }
                        Column {
                            Text((if (node.score > 0) "+" else "") + node.score,
                                color = if (node.score > 0) Color(0xFF66D9A3) else if (node.score < 0) Color(0xFFFF7777) else Color.LightGray,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("اطمینان ${node.confidence}٪", color = Color(0xFFB8B0A0), fontSize = 9.sp)
                        }
                    }
                }
                Text("شبکه ارتباطی: BTC → آلت‌کوین‌ها (۰٫۱۴)، BTC → ETH (۰٫۲۴)، ETH → آلت‌کوین‌ها (۰٫۰۸). این ضرایب فرضیه‌های محافظه‌کارانه‌اند، نه همبستگی آماری آموزش‌دیده.",
                    color = Color(0xFFB8B0A0), fontSize = 9.sp)
                Text("آخرین به‌روزرسانی: ${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(data.updatedAtMs))}",
                    color = Color(0xFF777777), fontSize = 9.sp)
            }
            Text("امتیاز احساسات سیگنال مستقل ورود نیست؛ نبود داده یا خبر کم‌اعتبار با اطمینان پایین نمایش داده می‌شود.",
                color = Color(0xFFB8B0A0), fontSize = 9.sp)
        }
    }
}
