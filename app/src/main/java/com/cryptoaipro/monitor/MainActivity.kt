package com.cryptoaipro.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.cryptoaipro.monitor.security.ExchangeCredentialVault
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val Navy = Color(0xFF0A1020)
private val Panel = Color(0xFF151F33)
private val Muted = Color(0xFF9AAAC2)
private val Green = Color(0xFF27D6A0)
private val Red = Color(0xFFFF657A)
private val Blue = Color(0xFF64B5F6)

data class MarketSignal(
    val symbol: String, val price: Double, val change: Double, val volume: Double,
    val direction: String, val score: Int, val rsi: Double, val confidence: Int,
    val reason: String, val entry: Double, val stop: Double, val tp1: Double, val tp2: Double, val tp3: Double, val leverage: Int, val atr: Double
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CryptoMonitorScreen() }
    }
}

@Composable
fun CryptoMonitorScreen() {
    var rows by remember { mutableStateOf<List<MarketSignal>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var updated by remember { mutableStateOf("هنوز اسکن انجام نشده") }
    var filter by remember { mutableStateOf("همه") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val credentialVault = remember(context) { ExchangeCredentialVault(context) }
    var showConnection by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }
    var secretKeyInput by remember { mutableStateOf("") }
    var credentialsSaved by remember { mutableStateOf(credentialVault.hasCredentials()) }
    var credentialMessage by remember { mutableStateOf<String?>(null) }

    suspend fun performScan() {
        loading = true
        error = null
        try {
            rows = BinanceMonitor.scan()
            updated = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        } catch (e: Exception) {
            error = e.message ?: "خطا در دریافت اطلاعات بازار"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { while (true) { performScan(); delay(180_000) } }
    val shown = when (filter) {
        "صعودی" -> rows.filter { it.direction == "LONG_BIAS" }
        "نزولی" -> rows.filter { it.direction == "SHORT_BIAS" }
        else -> rows
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Column(Modifier.fillMaxSize().background(Navy).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CRYPTO AI PRO", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text("پایش هوشمند بازار فیوچرز • بدون معامله", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { showConnection = !showConnection }) {
                    Text(if (showConnection) "بستن اتصال" else "اتصال XT", color = Blue, fontSize = 12.sp)
                }
                Surface(color = Color(0xFF123B36), shape = RoundedCornerShape(20.dp)) {
                    Text("● تحلیل؛ بدون معامله", color = Green, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (showConnection) {
                ConnectionCard(
                    apiKey = apiKeyInput,
                    onApiKeyChange = { apiKeyInput = it },
                    secretKey = secretKeyInput,
                    onSecretKeyChange = { secretKeyInput = it },
                    saved = credentialsSaved,
                    message = credentialMessage,
                    onSave = {
                        runCatching {
                            credentialVault.save(ExchangeCredentialVault.Credentials(apiKeyInput.trim(), secretKeyInput))
                        }.onSuccess {
                            credentialsSaved = true
                            apiKeyInput = ""
                            secretKeyInput = ""
                            credentialMessage = "کلیدها به‌صورت رمزگذاری‌شده روی همین دستگاه ذخیره شدند."
                        }.onFailure { credentialMessage = it.message ?: "ذخیره‌سازی ناموفق بود." }
                    },
                    onClear = {
                        runCatching { credentialVault.clear() }
                            .onSuccess {
                                credentialsSaved = false
                                apiKeyInput = ""
                                secretKeyInput = ""
                                credentialMessage = "کلیدهای ذخیره‌شده از این دستگاه پاک شدند."
                            }
                            .onFailure { credentialMessage = it.message ?: "پاک‌سازی ناموفق بود." }
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("قراردادهای بررسی‌شده", rows.size.toString(), Modifier.weight(1f))
                MetricCard("تمایل صعودی", rows.count { it.direction == "LONG_BIAS" }.toString(), Modifier.weight(1f), Green)
                MetricCard("تمایل نزولی", rows.count { it.direction == "SHORT_BIAS" }.toString(), Modifier.weight(1f), Red)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("۳۰ قرارداد پرحجم بازار", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("آخرین اسکن: " + updated, color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("همه", "صعودی", "نزولی").forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { scope.launch { performScan() } }, enabled = !loading) {
                    Text(if (loading) "در حال اسکن" else "اسکن مجدد")
                }
            }
            if (loading && rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Blue)
                }
            } else {
                error?.let { Text(it, color = Red, modifier = Modifier.padding(vertical = 8.dp)) }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp)
                ) {
                    items(shown, key = { it.symbol }) { item -> SignalCard(item) }
                }
            }
            Text(
                "اسکن خودکار هر ۳ دقیقه • تایم‌فریم ۱۵ دقیقه، ۱ ساعت و ۴ ساعت • فقط نظارت",
                color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    secretKey: String,
    onSecretKeyChange: (String) -> Unit,
    saved: Boolean,
    message: String?,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("اتصال امن به XT Futures", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(if (saved) "کلید ذخیره است" else "بدون کلید", color = if (saved) Green else Muted, fontSize = 10.sp)
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("XT API Key") },
                placeholder = { Text("کلید API را وارد کنید") },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp)
            )
            OutlinedTextField(
                value = secretKey,
                onValueChange = onSecretKeyChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("XT Secret Key") },
                placeholder = { Text("کلید محرمانه را وارد کنید") },
                visualTransformation = PasswordVisualTransformation(),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    enabled = apiKey.isNotBlank() && secretKey.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("ذخیره امن") }
                OutlinedButton(onClick = onClear, enabled = saved) { Text("حذف کلیدها") }
            }
            message?.let { Text(it, color = Green, fontSize = 10.sp) }
            Text(
                "ذخیره‌سازی با Android Keystore و AES-GCM انجام می‌شود. این بخش فعلاً فقط کلیدها را امن ذخیره می‌کند و هنوز اتصال احراز هویت‌شده یا ارسال سفارش را فعال نمی‌کند.",
                color = Muted, fontSize = 10.sp, lineHeight = 15.sp
            )
            Text(
                "امنیت: مجوز برداشت وجه را فعال نکنید. کلیدهایی که قبلاً در چت یا مخزن عمومی فرستاده‌اید، باطل و با کلید جدید جایگزین کنید.",
                color = Red, fontSize = 10.sp, lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier, valueColor: Color = Color.White) {
    Surface(modifier = modifier, color = Panel, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(title, color = Muted, fontSize = 9.sp, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun SignalCard(item: MarketSignal) {
    val tint = when (item.direction) {
        "LONG_BIAS" -> Green
        "SHORT_BIAS" -> Red
        else -> Muted
    }
    val label = when (item.direction) {
        "LONG_BIAS" -> "تمایل صعودی"
        "SHORT_BIAS" -> "تمایل نزولی"
        else -> "خنثی"
    }
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Surface(color = tint.copy(alpha = .15f), shape = RoundedCornerShape(20.dp)) {
                    Text(label, color = tint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SmallStat("قیمت", formatPrice(item.price))
                SmallStat("تغییر ۲۴ساعت", String.format(Locale.US, "%.2f%%", item.change), if (item.change >= 0) Green else Red)
                SmallStat("RSI", String.format(Locale.US, "%.1f", item.rsi))
                SmallStat("امتیاز", item.score.toString() + "/9")
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("قدرت هم‌جهتی تایم‌فریم‌ها", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(item.confidence.toString() + "%", color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { item.confidence / 100f },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = tint, trackColor = Color(0xFF29364D)
            )
            if (item.direction != "NEUTRAL") {
                Spacer(Modifier.height(8.dp))
                Surface(color = Color(0xFF0E1728), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ورود مرجع: " + formatPrice(item.entry) + "   |   اهرم سقف: " + item.leverage + "x", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallStat("حد ضرر", formatPrice(item.stop), Red)
                            SmallStat("TP1 • 1R", formatPrice(item.tp1), Green)
                            SmallStat("TP2 • 2R", formatPrice(item.tp2), Green)
                            SmallStat("TP3 • 3R", formatPrice(item.tp3), Green)
                        }
                        Text("استاپ مبتنی بر ۱٫۵ برابر ATR؛ اهرم صرفاً سقف محافظه‌کارانه تخمینی است.", color = Muted, fontSize = 9.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(item.reason, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, color: Color = Color.White) {
    Column {
        Text(label, color = Muted, fontSize = 9.sp)
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatPrice(value: Double): String = when {
    value >= 1000 -> DecimalFormat("#,##0.00").format(value)
    value >= 1 -> DecimalFormat("0.000").format(value)
    else -> DecimalFormat("0.000000").format(value)
}

private object BinanceMonitor {
    private const val BASE = "https://fapi.binance.com"

    private fun get(path: String): String {
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "CryptoAIPro-Android/1.0")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Binance پاسخ " + code + " داد")
            return body
        } finally {
            connection.disconnect()
        }
    }

    suspend fun scan(): List<MarketSignal> = withContext(Dispatchers.IO) {
        val exchangeInfo = JSONObject(get("/fapi/v1/exchangeInfo"))
        val allowed = mutableSetOf<String>()
        val symbols = exchangeInfo.getJSONArray("symbols")
        for (i in 0 until symbols.length()) {
            val s = symbols.getJSONObject(i)
            if (s.optString("status") == "TRADING" &&
                s.optString("quoteAsset") == "USDT" &&
                s.optString("contractType") == "PERPETUAL") {
                allowed.add(s.optString("symbol"))
            }
        }
        val tickers = JSONArray(get("/fapi/v1/ticker/24hr"))
        val ranked = (0 until tickers.length()).map { tickers.getJSONObject(it) }
            .filter { allowed.contains(it.optString("symbol")) }
            .sortedByDescending { it.optDouble("quoteVolume", 0.0) }
            .take(30)
        val semaphore = Semaphore(permits = 4)
        coroutineScope {
            ranked.map { ticker ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            analyze(
                                ticker.optString("symbol"),
                                ticker.optDouble("lastPrice"),
                                ticker.optDouble("priceChangePercent"),
                                ticker.optDouble("quoteVolume")
                            )
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull().sortedByDescending { abs(it.score) }
        }
    }

    private suspend fun analyze(symbol: String, price: Double, change: Double, volume: Double): MarketSignal {
        val short = candles(symbol, "15m")
        val confirm = candles(symbol, "1h")
        val trend = candles(symbol, "4h")
        val a = indicators(short)
        val b = indicators(confirm)
        val c = indicators(trend)
        val score = a.first + b.first + c.first
        val direction = when {
            score >= 4 -> "LONG_BIAS"
            score <= -4 -> "SHORT_BIAS"
            else -> "NEUTRAL"
        }
        val confidence = (abs(score) * 100 / 9).coerceIn(0, 100)
        val atrValue = atr(short)
        val stopDistance = maxOf(atrValue * 1.5, price * 0.002)
        val isLong = direction == "LONG_BIAS"
        val stop = if (isLong) price - stopDistance else price + stopDistance
        val tp1 = if (isLong) price + stopDistance else price - stopDistance
        val tp2 = if (isLong) price + stopDistance * 2 else price - stopDistance * 2
        val tp3 = if (isLong) price + stopDistance * 3 else price - stopDistance * 3
        val stopPct = stopDistance / price * 100.0
        val leverage = when { stopPct <= 0.8 -> 5; stopPct <= 1.5 -> 3; stopPct <= 3.0 -> 2; else -> 1 }
        return MarketSignal(
            symbol, price, change, volume, direction, score, a.third, confidence,
            "۱۵ دقیقه: " + a.second + " | یک‌ساعته: " + b.second + " | چهارساعته: " + c.second,
            price, stop, tp1, tp2, tp3, leverage, atrValue
        )
    }

    private fun candles(symbol: String, interval: String): List<DoubleArray> {
        val raw = JSONArray(get("/fapi/v1/klines?symbol=" + symbol + "&interval=" + interval + "&limit=100"))
        return (0 until (raw.length() - 1).coerceAtLeast(0)).map { i ->
            val row = raw.getJSONArray(i)
            doubleArrayOf(row.getString(2).toDouble(), row.getString(3).toDouble(), row.getString(4).toDouble())
        }
    }

    private fun atr(data: List<DoubleArray>, period: Int = 14): Double {
        if (data.size < period + 1) return data.last()[2] * 0.01
        val ranges = (1 until data.size).map { i ->
            val high = data[i][0]; val low = data[i][1]; val prevClose = data[i-1][2]
            maxOf(high-low, abs(high-prevClose), abs(low-prevClose))
        }
        return ranges.takeLast(period).average()
    }
    // Each candle contains high, low, close.
    private fun indicators(data: List<DoubleArray>): Quad {
        val closes = data.map { it[2] }
        if (closes.size < 55) throw IllegalStateException("داده کافی نیست")
        val e20 = ema(closes, 20)
        val e50 = ema(closes, 50)
        val rsiValue = rsi(closes, 14)
        val macdValues = macdSeries(closes)
        var score = 0
        val reasons = mutableListOf<String>()
        if (e20 > e50) { score++; reasons.add("EMA مثبت") } else { score--; reasons.add("EMA منفی") }
        if (macdValues.first > macdValues.second) { score++; reasons.add("MACD مثبت") } else { score--; reasons.add("MACD منفی") }
        if (rsiValue >= 55) { score++; reasons.add("RSI قوی") }
        else if (rsiValue <= 45) { score--; reasons.add("RSI ضعیف") }
        else reasons.add("RSI خنثی")
        return Quad(score, reasons.joinToString("، "), rsiValue)
    }

    private data class Quad(val first: Int, val second: String, val third: Double)

    private fun ema(values: List<Double>, period: Int): Double {
        val alpha = 2.0 / (period + 1)
        var result = values.first()
        values.drop(1).forEach { result = alpha * it + (1 - alpha) * result }
        return result
    }

    private fun rsi(values: List<Double>, period: Int): Double {
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val diff = values[i] - values[i - 1]
            if (diff >= 0) gain += diff else loss -= diff
        }
        gain /= period
        loss /= period
        for (i in period + 1 until values.size) {
            val diff = values[i] - values[i - 1]
            gain = (gain * (period - 1) + maxOf(diff, 0.0)) / period
            loss = (loss * (period - 1) + maxOf(-diff, 0.0)) / period
        }
        return if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
    }

    private fun macdSeries(values: List<Double>): Pair<Double, Double> {
        val fast = mutableListOf<Double>()
        val slow = mutableListOf<Double>()
        val af = 2.0 / 13
        val aslow = 2.0 / 27
        var f = values.first()
        var s = values.first()
        values.forEachIndexed { i, v ->
            if (i > 0) {
                f = af * v + (1 - af) * f
                s = aslow * v + (1 - aslow) * s
            }
            fast.add(f)
            slow.add(s)
        }
        val macd = fast.zip(slow) { x, y -> x - y }
        return macd.last() to ema(macd, 9)
    }
}
