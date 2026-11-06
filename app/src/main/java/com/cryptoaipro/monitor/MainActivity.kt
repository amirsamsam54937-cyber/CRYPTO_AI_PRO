package com.cryptoaipro.monitor

import android.os.Bundle
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import java.time.ZoneId
import java.time.ZonedDateTime
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
private val Blue = Color(0xFF55B8FF)
private val Purple = Color(0xFF9B7BFF)
private val Gold = Color(0xFFFFD166)

data class MarketSignal(
    val symbol: String, val price: Double, val change: Double, val volume: Double,
    val direction: String, val score: Int, val rsi: Double, val confidence: Int,
    val reason: String, val entry: Double, val stop: Double, val tp1: Double, val tp2: Double, val tp3: Double, val leverage: Int, val atr: Double,
    val technical: TechnicalSnapshot,
    val candles: List<DoubleArray> = emptyList(),
    val consensus: WolfConsensus? = null
)

data class TechnicalSnapshot(
    val ema9: Double, val ema20: Double, val ema50: Double, val ema100: Double, val ema200: Double,
    val adx: Double, val bollingerWidthPct: Double, val bollingerPosition: Double,
    val relativeVolume: Double, val vwap: Double, val stochasticRsi: Double,
    val atrPercent: Double, val regime: String,
    val candlePattern: String, val marketStructure: String,
    val support: Double, val resistance: Double
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
    var exchange by remember { mutableStateOf("XT") }
    var selectedTab by remember { mutableStateOf("خانه") }
    var selectedSymbol by remember { mutableStateOf("") }
    var voiceTranscript by remember { mutableStateOf("") }
    var voiceReply by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val learningPrefs = remember(context) { context.getSharedPreferences("wolf_learning_memory", Context.MODE_PRIVATE) }
    var learningSamples by remember { mutableIntStateOf(learningPrefs.getInt("samples", 0)) }
    var learningWins by remember { mutableIntStateOf(learningPrefs.getInt("wins", 0)) }
    var learningLosses by remember { mutableIntStateOf(learningPrefs.getInt("losses", 0)) }
    val voiceEngine = remember(context) { TextToSpeech(context) { } }
    DisposableEffect(voiceEngine) { onDispose { voiceEngine.stop(); voiceEngine.shutdown() } }
    LaunchedEffect(voiceReply) { if (voiceReply.isNotBlank()) { voiceEngine.language = Locale("fa", "IR"); voiceEngine.speak(voiceReply, TextToSpeech.QUEUE_FLUSH, null, "wolf-voice-reply") } }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (spoken.isNotBlank()) {
            voiceTranscript = spoken
            val current = rows.firstOrNull { it.symbol == selectedSymbol } ?: rows.firstOrNull()
            voiceReply = if (current == null) "هنوز داده زنده‌ای برای تحلیل ندارم؛ ابتدا اسکن بازار را اجرا کن."
            else if (spoken.contains("استاپ") || spoken.contains("حد ضرر")) "حد ضرر مرجع " + formatPrice(current.stop) + " و ورود مرجع " + formatPrice(current.entry) + " است."
            else if (spoken.contains("الگو") || spoken.contains("کندل") || spoken.contains("چارت")) "الگوی کندلی: " + current.technical.candlePattern + "؛ ساختار: " + current.technical.marketStructure
            else if (spoken.contains("لانگ") || spoken.contains("خرید")) "وضعیت خرید: " + if (current.direction == "LONG_BIAS") "تمایل صعودی با اطمینان " + current.confidence + " درصد" else "سیگنال خرید تأیید نشده است"
            else if (spoken.contains("شورت") || spoken.contains("فروش")) "وضعیت فروش: " + if (current.direction == "SHORT_BIAS") "تمایل نزولی با اطمینان " + current.confidence + " درصد" else "سیگنال فروش تأیید نشده است"
            else "قیمت " + formatPrice(current.price) + "، RSI " + String.format(Locale.US, "%.1f", current.rsi) + "، ADX " + String.format(Locale.US, "%.1f", current.technical.adx) + ". " + current.reason
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "سوالت را درباره بازار بپرس")
            }
            speechLauncher.launch(speechIntent)
        } else voiceReply = "برای گفت‌وگوی صوتی، اجازه میکروفون را فعال کن."
    }


    suspend fun performScan() {
        loading = true
        error = null
        try {
            rows = if (exchange == "XT") XTMonitor.scan() else BinanceMonitor.scan()
            val memoryStats = WolfLearningMemory.processScan(context, rows)
            learningSamples = memoryStats.samples
            learningWins = memoryStats.wins
            learningLosses = memoryStats.losses
            updated = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (selectedSymbol.isBlank() || rows.none { it.symbol == selectedSymbol }) {
                selectedSymbol = rows.firstOrNull()?.symbol.orEmpty()
            }
        } catch (e: Exception) {
            error = e.message ?: "خطا در دریافت اطلاعات بازار"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(exchange) {
        while (true) {
            performScan()
            delay(180_000)
        }
    }

    val shown = when (filter) {
        "صعودی" -> rows.filter { it.direction == "LONG_BIAS" }
        "نزولی" -> rows.filter { it.direction == "SHORT_BIAS" }
        else -> rows
    }
    val selectedSignal = rows.firstOrNull { it.symbol == selectedSymbol } ?: rows.firstOrNull()

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, secondary = Purple, background = Navy, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = Color.White) {
                    listOf("خانه" to "⌂", "بازار" to "◫", "تحلیل" to "⌁", "جریان نهنگ" to "◉", "هوش مصنوعی" to "✧", "تنظیمات" to "⚙").forEach { entry ->
                        NavigationBarItem(
                            selected = selectedTab == entry.first,
                            onClick = { selectedTab = entry.first },
                            icon = { Text(entry.second, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                            label = { Text(entry.first, fontSize = 9.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Blue,
                                selectedTextColor = Blue,
                                indicatorColor = Color(0xFF24344E),
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                Modifier.fillMaxSize().padding(innerPadding).background(Navy).drawBehind {
                    val wolf = Path().apply {
                        moveTo(size.width * 0.08f, size.height * 0.08f)
                        lineTo(size.width * 0.32f, size.height * 0.28f)
                        lineTo(size.width * 0.50f, size.height * 0.18f)
                        lineTo(size.width * 0.68f, size.height * 0.28f)
                        lineTo(size.width * 0.92f, size.height * 0.08f)
                        lineTo(size.width * 0.80f, size.height * 0.52f)
                        lineTo(size.width * 0.66f, size.height * 0.76f)
                        lineTo(size.width * 0.50f, size.height * 0.94f)
                        lineTo(size.width * 0.34f, size.height * 0.76f)
                        lineTo(size.width * 0.20f, size.height * 0.52f)
                        close()
                    }
                    drawPath(wolf, Blue.copy(alpha = 0.035f))
                    drawCircle(Blue.copy(alpha = 0.08f), radius = size.width * 0.018f, center = androidx.compose.ui.geometry.Offset(size.width * 0.40f, size.height * 0.43f))
                    drawCircle(Blue.copy(alpha = 0.08f), radius = size.width * 0.018f, center = androidx.compose.ui.geometry.Offset(size.width * 0.60f, size.height * 0.43f))
                }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTab != "خانه") TextButton(onClick = { selectedTab = "خانه" }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹ بازگشت", color = Blue, fontSize = 11.sp) }
                    Surface(
                        color = Color(0xFF142B4B),
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("🐺", color = Blue, fontSize = 25.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AMIR ARSALAN", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text("TRADER WOLF  •  LIVE TECHNICAL INTELLIGENCE", color = Muted, fontSize = 8.sp, letterSpacing = 0.5.sp)
                    }
                    Surface(
                        color = if (loading) Color(0xFF3A2C17) else Color(0xFF10382F),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            if (loading) "در حال اسکن" else "● " + exchange,
                            color = if (loading) Color(0xFFFFD166) else Green,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            fontSize = 10.sp
                        )
                    }
                }

                when (selectedTab) {
                    "خانه" -> {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            item {
                                Surface(color = Color(0xFF14243C), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                        Text("مرکز فرماندهی بازار", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text("اسکن چندتایم‌فریمی • تحلیل تکنیکال • موتور هوشمند سیگنال", color = Muted, fontSize = 10.sp)
                                        MarketSessionsPanel()
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            MetricCard("بازارهای بررسی‌شده", rows.size.toString(), Modifier.weight(1f))
                                            MetricCard("تمایل صعودی", rows.count { it.direction == "LONG_BIAS" }.toString(), Modifier.weight(1f), Green)
                                            MetricCard("تمایل نزولی", rows.count { it.direction == "SHORT_BIAS" }.toString(), Modifier.weight(1f), Red)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { selectedTab = "بازار" }, modifier = Modifier.weight(1f)) { Text("مشاهده بازار") }
                                            OutlinedButton(onClick = { scope.launch { performScan() } }, enabled = !loading, modifier = Modifier.weight(1f)) {
                                                Text(if (loading) "در حال اسکن..." else "اسکن مجدد")
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("فرصت‌های برتر", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                    Text("آخرین بروزرسانی " + updated, color = Muted, fontSize = 9.sp)
                                }
                            }
                            if (loading && rows.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) } }
                            error?.let { msg -> item { Text(msg, color = Red, fontSize = 11.sp) } }
                            items(rows.take(5), key = { it.symbol }) { signal ->
                                SignalCard(signal, onSelect = { selectedSymbol = signal.symbol; selectedTab = "تحلیل" })
                            }
                            item { Text("سیگنال‌ها ابزار غربالگری‌اند، نه تضمین سود یا توصیه قطعی معامله.", color = Muted, fontSize = 9.sp) }
                        }
                    }

                    "بازار" -> {
                        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = exchange == "XT", onClick = { if (exchange != "XT") exchange = "XT" }, label = { Text("XT Futures") })
                                FilterChip(selected = exchange == "BINANCE", onClick = { if (exchange != "BINANCE") exchange = "BINANCE" }, label = { Text("Binance Futures") })
                                Spacer(Modifier.weight(1f))
                                Text("تمام قراردادهای USDT", color = Blue, fontSize = 10.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                listOf("همه", "صعودی", "نزولی").forEach { option ->
                                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { scope.launch { performScan() } }, enabled = !loading) { Text("اسکن مجدد", color = Blue) }
                            }
                            error?.let { Text(it, color = Red, fontSize = 11.sp) }
                            if (loading && rows.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
                            } else {
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                                    items(shown, key = { it.symbol }) { signal ->
                                        SignalCard(signal, onSelect = { selectedSymbol = signal.symbol; selectedTab = "تحلیل" })
                                    }
                                }
                            }
                        }
                    }

                    "تحلیل" -> {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("مغز تکنیکال گرگ", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text("تحلیل کندل، ساختار، چندتایم‌فریم و حافظه یادگیری محلی", color = Muted, fontSize = 10.sp)
                                    }
                                    Button(onClick = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) }) { Text("🎙 صحبت") }
                                }
                            }
                            item { TradingViewPairChart(selectedSymbol = selectedSignal?.symbol ?: "BTCUSDT") }
                            if (voiceTranscript.isNotBlank() || voiceReply.isNotBlank()) {
                                item {
                                    Surface(color = Color(0xFF132640), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("گفت‌وگوی صوتی", color = Blue, fontWeight = FontWeight.Bold)
                                            if (voiceTranscript.isNotBlank()) Text("تو: " + voiceTranscript, color = Color.White, fontSize = 12.sp)
                                            if (voiceReply.isNotBlank()) Text("گرگ: " + voiceReply, color = Muted, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            if (selectedSignal == null) {
                                item { Text(if (loading) "در حال دریافت داده بازار..." else "هنوز داده‌ای برای تحلیل وجود ندارد. ابتدا اسکن بازار را اجرا کن.", color = Muted) }
                            } else {
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("نماد تحلیل", color = Muted, fontSize = 11.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(selectedSignal.symbol, color = Blue, fontWeight = FontWeight.Bold)
                                    }
                                }
                                item { SignalCard(selectedSignal, onSelect = { selectedSymbol = selectedSignal.symbol }) }
                                item {
                                    Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("نقشه تکنیکال و ساختار بازار", color = Color.White, fontWeight = FontWeight.Bold)
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                SmallStat("EMA 9", formatPrice(selectedSignal.technical.ema9))
                                                SmallStat("EMA 20", formatPrice(selectedSignal.technical.ema20))
                                                SmallStat("EMA 50", formatPrice(selectedSignal.technical.ema50))
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                SmallStat("EMA 100", formatPrice(selectedSignal.technical.ema100))
                                                SmallStat("EMA 200", formatPrice(selectedSignal.technical.ema200))
                                                SmallStat("VWAP", formatPrice(selectedSignal.technical.vwap))
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                SmallStat("ADX", String.format(Locale.US, "%.1f", selectedSignal.technical.adx))
                                                SmallStat("RSI", String.format(Locale.US, "%.1f", selectedSignal.rsi))
                                                SmallStat("ATR %%", String.format(Locale.US, "%.2f", selectedSignal.technical.atrPercent))
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                SmallStat("حجم نسبی", String.format(Locale.US, "%.2fx", selectedSignal.technical.relativeVolume))
                                                SmallStat("حمایت", formatPrice(selectedSignal.technical.support), Green)
                                                SmallStat("مقاومت", formatPrice(selectedSignal.technical.resistance), Red)
                                            }
                                            Text("الگوی کندلی: " + selectedSignal.technical.candlePattern, color = Blue, fontSize = 11.sp)
                                            Text("الگوی هارمونیک: " + detectHarmonicPattern(selectedSignal.candles), color = Purple, fontSize = 11.sp)
                                            Text("ساختار: " + selectedSignal.technical.marketStructure + " • رژیم: " + selectedSignal.technical.regime, color = Muted, fontSize = 10.sp)
                                            Divider(color = Color(0xFF30415C))
                                            Text("اجماع ۱۰ تحلیلگر تخصصی گرگ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text("توافق وزنی: " + (selectedSignal.consensus?.agreement ?: 0) + "% • قدرت سیگنال: " + selectedSignal.confidence, color = Blue, fontSize = 10.sp)
                                            selectedSignal.consensus?.votes?.forEach { vote ->
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(vote.name, color = Muted, fontSize = 10.sp)
                                                    Text(vote.direction + " • " + String.format(Locale.US, "%.2f", vote.score), color = when (vote.direction) { "LONG" -> Green; "SHORT" -> Red; else -> Muted }, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            Divider(color = Color(0xFF30415C))
                                            Text("شبکه عصبی عمیق گرگ • ۷ ورودی، ۲ لایه پنهان، ۲۴ نورون", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text("حافظه یادگیری پایدار روی گوشی", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            val memoryStats = WolfLearningMemory.stats(context)
                                            Text("نمونه‌های ارزیابی‌شده: " + memoryStats.samples + " • موفق: " + memoryStats.wins + " • ناموفق: " + memoryStats.losses, color = Muted, fontSize = 10.sp)
                                            Text("در انتظار ارزیابی ۴ ساعته: " + memoryStats.pending + " • آموزش خودکار امروز: " + memoryStats.learnedToday, color = Blue, fontSize = 10.sp)
                                            Text("سیستم سیگنال‌های جهت‌دار را ذخیره می‌کند و پس از گذشت ۴ ساعت، نتیجه حرکت قیمت را می‌سنجد؛ وزن‌های شبکه عصبی در حافظه داخلی برنامه نگهداری می‌شوند.", color = Muted, fontSize = 9.sp)
                                            Text("بازخورد ذخیره‌شده: " + learningSamples + " • درست: " + learningWins + " • نادرست: " + learningLosses, color = Muted, fontSize = 10.sp)
                                            Text("خروجی شبکه عصبی (امتیاز آزمایشی): " + String.format(Locale.US, "%.1f%%", WolfDeepNeuralNetwork.predict(learningPrefs, selectedSignal) * 100.0), color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text("شبکه با بازخورد دستی آموزش می‌بیند و وزن‌هایش روی گوشی ذخیره می‌شود؛ مدل از پیش‌آموزش‌دیده نیست.", color = Muted, fontSize = 9.sp)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = {
                                                    learningSamples += 1
                                                    learningWins += 1
                                                    WolfDeepNeuralNetwork.learn(learningPrefs, selectedSignal, 1.0)
                                                    learningPrefs.edit().putInt("samples", learningSamples).putInt("wins", learningWins).putInt("losses", learningLosses).putString("last_symbol", selectedSignal.symbol).putString("last_feedback", "correct").apply()
                                                }, modifier = Modifier.weight(1f)) { Text("✓ تحلیل درست بود", fontSize = 10.sp) }
                                                OutlinedButton(onClick = {
                                                    learningSamples += 1
                                                    learningLosses += 1
                                                    WolfDeepNeuralNetwork.learn(learningPrefs, selectedSignal, 0.0)
                                                    learningPrefs.edit().putInt("samples", learningSamples).putInt("wins", learningWins).putInt("losses", learningLosses).putString("last_symbol", selectedSignal.symbol).putString("last_feedback", "wrong").apply()
                                                }, modifier = Modifier.weight(1f)) { Text("✕ تحلیل نادرست بود", fontSize = 10.sp) }
                                            }
                                            if (selectedSignal.candles.isNotEmpty()) {
                                                Text("نمودار کندل ژاپنی • ۱۵ دقیقه", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                CandleChart(selectedSignal.candles)
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text("برای بررسی نماد دیگر، از بخش بازار روی نماد موردنظر بزن و سپس به تحلیل برگرد.", color = Muted, fontSize = 10.sp)
                                }
                            }
                        }
                    }





                    "جریان نهنگ" -> {
                        WolfFlowNewsScreen(selectedSignal?.symbol ?: "BTCUSDT")
                    }

                    "هوش مصنوعی" -> {
                        WolfAiCompanionScreen()
                    }

                    else -> {
                        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("تنظیمات و پایش پس‌زمینه", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("پایش زنده بازار", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("با شروع سرویس، اعلان دائمی اندروید نمایش داده می‌شود و اسکن بازار در پس‌زمینه ادامه می‌یابد. توقف اجباری، قطع اینترنت یا محدودیت باتری می‌تواند سرویس را متوقف کند.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                                    Button(
                                        onClick = {
                                            val intent = Intent(context, MarketMonitorService::class.java)
                                                .putExtra("exchange", exchange)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                                            else context.startService(intent)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("شروع پایش پس‌زمینه") }
                                    OutlinedButton(
                                        onClick = { context.stopService(Intent(context, MarketMonitorService::class.java)) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("توقف پایش") }
                                }
                            }
                            Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("پروفایل تحلیل", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("بازار: " + exchange + " Futures", color = Muted, fontSize = 11.sp)
                                    Text("تایم‌فریم اصلی: ۱۵ دقیقه • تأیید: ۱ ساعت و ۴ ساعت", color = Muted, fontSize = 11.sp)
                                    Text("دامنه اسکن: حداکثر ۱۰۰ قرارداد پرحجم USDT", color = Muted, fontSize = 11.sp)
                                    Text("بروزرسانی خودکار: هر ۳ دقیقه", color = Muted, fontSize = 11.sp)
                                    Text("نسخه: 1.6.0 • رابط ماژولار", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text("پیشنهاد برای گوشی سامسونگ: تنظیمات ← برنامه‌ها ← CRYPTO AI PRO ← باتری ← بدون محدودیت. این تنظیم تضمین اجرای ۲۴ ساعته نیست.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }
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
private fun SignalCard(item: MarketSignal, onSelect: () -> Unit = {}) {
    var expanded by remember(item.symbol) { mutableStateOf(false) }
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
                TextButton(onClick = onSelect, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text("تحلیل", color = Blue, fontSize = 10.sp)
                }
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
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "بستن جزئیات تحلیل ▲" else "مشاهده شاخص‌های پیشرفته ▼", color = Blue, fontSize = 10.sp)
            }
            if (expanded) {
                Surface(color = Color(0xFF0E1728), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("تحلیل تکنیکال تایم‌فریم ۱۵ دقیقه", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallStat("EMA 9", formatPrice(item.technical.ema9))
                            SmallStat("EMA 20", formatPrice(item.technical.ema20))
                            SmallStat("EMA 50", formatPrice(item.technical.ema50))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallStat("EMA 100", formatPrice(item.technical.ema100))
                            SmallStat("EMA 200", formatPrice(item.technical.ema200))
                            SmallStat("VWAP", formatPrice(item.technical.vwap))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallStat("ADX", String.format(Locale.US, "%.1f", item.technical.adx))
                            SmallStat("Stoch RSI", String.format(Locale.US, "%.1f", item.technical.stochasticRsi))
                            SmallStat("ATR ٪", String.format(Locale.US, "%.2f", item.technical.atrPercent))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallStat("قدرت حجم", String.format(Locale.US, "%.2fx", item.technical.relativeVolume))
                            SmallStat("عرض باند", String.format(Locale.US, "%.2f%%", item.technical.bollingerWidthPct))
                            SmallStat("موقعیت باند", String.format(Locale.US, "%.2f", item.technical.bollingerPosition))
                        }
                        Text("الگوی شمعی: " + item.technical.candlePattern, color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallStat("حمایت ساختاری", formatPrice(item.technical.support), Green)
                            SmallStat("مقاومت ساختاری", formatPrice(item.technical.resistance), Red)
                        }
                        Text("ساختار بازار: " + item.technical.marketStructure + " • رژیم: " + item.technical.regime, color = Muted, fontSize = 9.sp)
                        if (item.candles.isNotEmpty()) {
                            Text("نمودار کندل ژاپنی • ۱۵ دقیقه", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            CandleChart(item.candles)
                        }
                        Text("الگوها احتمالی‌اند؛ هیچ الگویی جهت قیمت را تضمین نمی‌کند.", color = Muted, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CandleChart(candles: List<DoubleArray>) {
    val visible = candles.takeLast(54)
    if (visible.isEmpty()) return
    Canvas(Modifier.fillMaxWidth().height(154.dp)) {
        val high = visible.maxOf { it[1] }
        val low = visible.minOf { it[2] }
        val span = (high - low).coerceAtLeast(1e-12)
        val step = size.width / visible.size
        val bodyWidth = (step * 0.58f).coerceAtLeast(2f)
        repeat(4) { n ->
            val y = size.height * n / 3f
            drawLine(Color(0xFF26364E), Offset(0f, y), Offset(size.width, y), 1f)
        }
        visible.forEachIndexed { index, candle ->
            val x = step * index + step / 2f
            fun y(price: Double) = size.height - ((price - low) / span * (size.height - 8f) + 4f).toFloat()
            val tint = if (candle[3] >= candle[0]) Green else Red
            drawLine(tint, Offset(x, y(candle[1])), Offset(x, y(candle[2])), 1.4f)
            val top = minOf(y(candle[0]), y(candle[3]))
            val bottom = maxOf(y(candle[0]), y(candle[3]))
            drawRect(tint, Offset(x - bodyWidth / 2f, top), androidx.compose.ui.geometry.Size(bodyWidth, (bottom - top).coerceAtLeast(2f)))
        }
    }
}

@Composable
private fun TradingViewPairChart(selectedSymbol: String) {
    val symbol = "BINANCE:" + selectedSymbol.replace("/", "").replace(":USDT", "").replace("_", "") + ".P"
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("چارت آنلاین " + selectedSymbol + " • TradingView", color = Color.White, fontWeight = FontWeight.Bold)
            Text("فقط نمودار همان ارزی که سیگنال آن انتخاب شده نمایش داده می‌شود.", color = Muted, fontSize = 9.sp)
            AndroidView(modifier = Modifier.fillMaxWidth().height(320.dp),
                factory = { context -> WebView(context).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient() } },
                update = { it.loadUrl("https://www.tradingview.com/chart/?symbol=" + java.net.URLEncoder.encode(symbol, "UTF-8")) })
            Text("نمودار مرجع TradingView است؛ محاسبات سیگنال از کندل‌های صرافی انجام می‌شود.", color = Muted, fontSize = 9.sp)
        }
    }
}

private fun detectHarmonicPattern(candles: List<DoubleArray>): String {
    if (candles.size < 35) return "داده کافی برای تشخیص هارمونیک نیست"
    val pivots = mutableListOf<Pair<Int, Double>>()
    val start = maxOf(2, candles.size - 100)
    for (i in start until candles.size - 2) {
        val high = candles[i][1]
        val low = candles[i][2]
        val isHigh = (i - 2..i + 2).all { candles[it][1] <= high }
        val isLow = (i - 2..i + 2).all { candles[it][2] >= low }
        if (isHigh) pivots.add(i to high) else if (isLow) pivots.add(i to low)
    }
    val clean = pivots.fold(mutableListOf<Pair<Int, Double>>()) { acc, p ->
        if (acc.isEmpty() || kotlin.math.abs(acc.last().second - p.second) / p.second.coerceAtLeast(1e-12) > 0.002) acc.add(p)
        else if (p.second > acc.last().second || p.second < acc.last().second) acc[acc.lastIndex] = p
        acc
    }
    if (clean.size < 5) return "الگوی هارمونیک تأیید نشد"
    val p = clean.takeLast(5).map { it.second }
    val xa = kotlin.math.abs(p[1] - p[0])
    val ab = kotlin.math.abs(p[2] - p[1])
    val bc = kotlin.math.abs(p[3] - p[2])
    val cd = kotlin.math.abs(p[4] - p[3])
    if (xa <= 0.0 || ab <= 0.0 || bc <= 0.0) return "الگوی هارمونیک تأیید نشد"
    val rAB = ab / xa
    val rBC = bc / ab
    val rCD = cd / bc
    val rAD = kotlin.math.abs(p[4] - p[1]) / xa
    fun near(v: Double, target: Double, tolerance: Double = 0.12) = kotlin.math.abs(v - target) <= tolerance
    val side = if (p[4] < p[3]) "صعودی" else "نزولی"
    return when {
        near(rAB, 0.618, 0.10) && rBC in 0.382..0.886 && near(rCD, 1.272, 0.22) && near(rAD, 0.786, 0.10) -> "گارتلی " + side + " • ناحیه احتمالی تکمیل"
        rAB in 0.382..0.50 && rBC in 0.382..0.886 && near(rCD, 1.618, 0.28) && near(rAD, 0.886, 0.12) -> "خرچنگ " + side + " • ناحیه احتمالی تکمیل"
        near(rAB, 0.786, 0.10) && rBC in 0.382..0.886 && rCD in 1.50..2.20 && near(rAD, 1.27, 0.16) -> "باترفلای " + side + " • ناحیه احتمالی تکمیل"
        rAB in 0.382..0.618 && rBC in 0.382..0.886 && rCD in 2.0..3.0 -> "احتمال خفاش/خرچنگ " + side + " • نیازمند تأیید بیشتر"
        else -> "الگوی هارمونیک تأیید نشد"
    }
}

@Composable
private fun MarketSessionsPanel() {
    val utc = ZonedDateTime.now(ZoneId.of("UTC"))
    fun session(zone: String, start: Int, end: Int): Pair<String, Boolean> {
        val local = utc.withZoneSameInstant(ZoneId.of(zone))
        return String.format(Locale.US, "%02d:%02d", local.hour, local.minute) to (local.hour in start until end)
    }
    val asia = session("Asia/Tokyo", 9, 18)
    val london = session("Europe/London", 8, 17)
    val ny = session("America/New_York", 8, 17)
    Surface(color = Color(0xFF101B2D), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(11.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("آسیا" to asia, "لندن" to london, "نیویورک" to ny).forEach { (name, data) ->
                Column {
                    Text(name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(data.first, color = Blue, fontSize = 12.sp)
                    Text(if (data.second) "باز" else "بسته", color = if (data.second) Green else Muted, fontSize = 9.sp)
                }
            }
            Column {
                Text("هم‌پوشانی", color = Color.White, fontSize = 10.sp)
                Text(if (london.second && ny.second) "لندن + NY" else "—", color = if (london.second && ny.second) Green else Muted, fontSize = 10.sp)
                Text("ساعات محلی", color = Muted, fontSize = 9.sp)
            }
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


/** XT USDT-M public market scanner. API keys are not needed for public quotes. */
private object XTMonitor {
    private const val BASE = "https://fapi.xt.com"

    private fun get(path: String): String {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12000; readTimeout = 15000
            setRequestProperty("User-Agent", "CryptoAIPro-Android/1.3")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("XT پاسخ HTTP $code داد")
            return body
        } finally { c.disconnect() }
    }

    private fun array(json: JSONObject): JSONArray {
        val code = json.optString("returnCode", "0")
        if (code != "0" && code != "200") {
            val detail = json.optJSONObject("error")?.optString("msg")
                ?: json.optString("msgInfo", "خطای API صرافی XT")
            throw IllegalStateException("XT: $detail")
        }
        return json.optJSONArray("result")
            ?: throw IllegalStateException("پاسخ فهرست بازار XT آرایه نیست")
    }

    suspend fun scan(): List<MarketSignal> = withContext(Dispatchers.IO) {
        val tickers = array(JSONObject(get("/future/market/v1/public/q/agg-tickers")))
        val ranked = (0 until tickers.length()).mapNotNull { i ->
            val t = tickers.optJSONObject(i) ?: return@mapNotNull null
            val symbol = t.optString("s")
            if (!symbol.lowercase(Locale.US).endsWith("_usdt")) return@mapNotNull null
            val price = t.optString("c").toDoubleOrNull() ?: t.optDouble("c")
            val open = t.optString("o").toDoubleOrNull() ?: t.optDouble("o")
            val turnover = t.optString("v").toDoubleOrNull() ?: t.optDouble("v")
            if (price <= 0.0 || open <= 0.0 || turnover <= 0.0) return@mapNotNull null
            Triple(t, price, turnover)
        }.sortedByDescending { it.third }
        if (ranked.isEmpty()) throw IllegalStateException("XT هیچ قرارداد USDT Perpetual قابل اسکن برنگرداند")
        val semaphore = Semaphore(permits = 3)
        coroutineScope {
            ranked.map { item ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val t = item.first
                            val symbol = t.optString("s")
                            val open = t.optString("o").toDoubleOrNull() ?: t.optDouble("o")
                            val change = (item.second / open - 1.0) * 100.0
                            val a = candles(symbol, "15m")
                            val b = candles(symbol, "1h")
                            val c = candles(symbol, "4h")
                            BinanceMonitor.analyzeWithCandles(symbol, item.second, change, item.third, a, b, c)
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull().sortedByDescending { abs(it.score) }
        }
    }

    private fun candles(symbol: String, interval: String): List<DoubleArray> {
        val encoded = java.net.URLEncoder.encode(symbol.lowercase(Locale.US), "UTF-8")
        val rows = array(JSONObject(get("/future/market/v1/public/q/kline?symbol=$encoded&interval=$interval&limit=250")))
        if (rows.length() < 56) throw IllegalStateException("کندل کافی برای $symbol در $interval دریافت نشد")
        return (0 until rows.length() - 1).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            fun n(key: String): Double = row.optString(key).toDoubleOrNull() ?: row.optDouble(key)
            doubleArrayOf(n("o"), n("h"), n("l"), n("c"), n("a"))
        }.filter { it.all { value -> value.isFinite() } && it[3] > 0.0 }
    }
}

/** Signed XT futures requests. Orders are never retried automatically. */


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
        return analyzeWithCandles(symbol, price, change, volume, short, confirm, trend)
    }

    internal fun analyzeWithCandles(symbol: String, price: Double, change: Double, volume: Double,
                                    short: List<DoubleArray>, confirm: List<DoubleArray>,
                                    trend: List<DoubleArray>): MarketSignal {
        val a = indicators(short)
        val b = indicators(confirm)
        val c = indicators(trend)
        val score = a.first + b.first + c.first
        val wolf = WolfConsensusEngine.analyze(listOf(a.technical, b.technical, c.technical), price)
        val atrValue = atr(short).coerceAtLeast(price * 0.0001)
        val stretched = abs(price - a.technical.ema20) > atrValue * 2.2
        val extremeVolatility = a.technical.atrPercent > 10.0
        val fourHourConflict = (wolf.direction == "LONG_BIAS" && c.technical.marketStructure.contains("LH/LL")) ||
            (wolf.direction == "SHORT_BIAS" && c.technical.marketStructure.contains("HH/HL"))
        val filtered = stretched || extremeVolatility || fourHourConflict
        val direction = if (filtered) "NEUTRAL" else wolf.direction
        val confidence = if (filtered) (wolf.strengthIndex * 0.65).toInt() else wolf.strengthIndex
        val isLong = direction == "LONG_BIAS"
        val isShort = direction == "SHORT_BIAS"
        val atrBase = maxOf(atrValue * 1.5, price * 0.002)
        val structuralDistance = when {
            isLong -> price - a.technical.support + atrValue * 0.20
            isShort -> a.technical.resistance - price + atrValue * 0.20
            else -> atrBase
        }
        val stopDistance = if ((isLong || isShort) && structuralDistance in (atrValue * 0.8)..(atrValue * 2.5))
            maxOf(atrBase, structuralDistance).coerceAtMost(atrValue * 2.5)
        else atrBase
        val stop = when { isLong -> price - stopDistance; isShort -> price + stopDistance; else -> price }
        val tp1 = when { isLong -> price + stopDistance; isShort -> price - stopDistance; else -> price }
        val tp2 = when { isLong -> price + stopDistance * 2.0; isShort -> price - stopDistance * 2.0; else -> price }
        val tp3 = when { isLong -> price + stopDistance * 3.0; isShort -> price - stopDistance * 3.0; else -> price }
        val stopPct = stopDistance / price * 100.0
        val leverage = when { !isLong && !isShort -> 1; stopPct <= 0.8 -> 5; stopPct <= 1.5 -> 3; stopPct <= 3.0 -> 2; else -> 1 }
        val agentSummary = wolf.votes.joinToString("، ") { it.name + ":" + it.direction }
        val filterNote = when {
            stretched -> " | فیلتر: قیمت بیش از حد از EMA20 فاصله گرفته؛ تعقیب نکن"
            extremeVolatility -> " | فیلتر: نوسان ۱۵دقیقه‌ای بسیار شدید"
            fourHourConflict -> " | فیلتر: ساختار ۴ساعته مخالف جهت است"
            else -> ""
        }
        return MarketSignal(
            symbol, price, change, volume, direction, score, a.third, confidence,
            "اجماع گرگ " + wolf.agreement + "% | قدرت اجماع (نه احتمال برد): " + wolf.strengthIndex +
                "% | ۱۰ تحلیلگر: " + agentSummary + " | ۱۵د: " + a.second + " | ۱س: " + b.second +
                " | ۴س: " + c.second + filterNote,
            price, stop, tp1, tp2, tp3, leverage, atrValue, a.technical, short.takeLast(80), wolf
        )
    }

    private fun candles(symbol: String, interval: String): List<DoubleArray> {
        val raw = JSONArray(get("/fapi/v1/klines?symbol=" + symbol + "&interval=" + interval + "&limit=250"))
        return (0 until (raw.length() - 1).coerceAtLeast(0)).map { i ->
            val row = raw.getJSONArray(i)
            doubleArrayOf(row.getString(1).toDouble(), row.getString(2).toDouble(), row.getString(3).toDouble(), row.getString(4).toDouble(), row.getString(5).toDouble())
        }
    }

    private fun atr(data: List<DoubleArray>, period: Int = 14): Double {
        if (data.size < period + 1) return data.last()[3] * 0.01
        val ranges = (1 until data.size).map { i ->
            val high = data[i][1]; val low = data[i][2]; val prevClose = data[i-1][3]
            maxOf(high-low, abs(high-prevClose), abs(low-prevClose))
        }
        return ranges.takeLast(period).average()
    }
    // Each candle contains open, high, low, close, base volume.
    private fun indicators(data: List<DoubleArray>): Quad {
        val closes = data.map { it[3] }
        if (closes.size < 55) throw IllegalStateException("داده کافی نیست")
        val e9 = ema(closes, 9)
        val e20 = ema(closes, 20)
        val e50 = ema(closes, 50)
        val e100 = ema(closes, 100)
        val e200 = ema(closes, 200)
        val rsiValue = rsi(closes, 14)
        val macdValues = macdSeries(closes)
        var score = 0
        val reasons = mutableListOf<String>()
        if (e20 > e50) { score++; reasons.add("EMA مثبت") } else { score--; reasons.add("EMA منفی") }
        if (macdValues.first > macdValues.second) { score++; reasons.add("MACD مثبت") } else { score--; reasons.add("MACD منفی") }
        if (rsiValue >= 55) { score++; reasons.add("RSI قوی") }
        else if (rsiValue <= 45) { score--; reasons.add("RSI ضعیف") }
        else reasons.add("RSI خنثی")
        if (e20 > e50 && e50 > e100 && e100 > e200) reasons.add("چیدمان صعودی EMA")
        else if (e20 < e50 && e50 < e100 && e100 < e200) reasons.add("چیدمان نزولی EMA")
        val adx = calculateAdx(data)
        val lastClose = closes.last()
        val bbWindow = closes.takeLast(20)
        val bbMean = bbWindow.average()
        val bbStd = kotlin.math.sqrt(bbWindow.map { (it - bbMean) * (it - bbMean) }.average())
        val bbUpper = bbMean + 2.0 * bbStd
        val bbLower = bbMean - 2.0 * bbStd
        val bbWidth = if (bbMean != 0.0) (bbUpper - bbLower) / bbMean * 100.0 else 0.0
        val bbPosition = if (bbUpper != bbLower) ((lastClose - bbLower) / (bbUpper - bbLower)).coerceIn(-0.5, 1.5) else 0.5
        val volumeWindow = data.takeLast(21).dropLast(1).map { it[4] }
        val averageVolume = volumeWindow.average().coerceAtLeast(1e-12)
        val relativeVolume = data.last()[4] / averageVolume
        val vwapWindow = data.takeLast(24)
        val volumeSum = vwapWindow.sumOf { it[4] }.coerceAtLeast(1e-12)
        val vwap = vwapWindow.sumOf { ((it[1] + it[2] + it[3]) / 3.0) * it[4] } / volumeSum
        val rsiValues = rsiSeries(closes, 14).takeLast(14)
        val rsiMin = rsiValues.minOrNull() ?: rsiValue
        val rsiMax = rsiValues.maxOrNull() ?: rsiValue
        val stochRsi = if (rsiMax > rsiMin) ((rsiValue - rsiMin) / (rsiMax - rsiMin) * 100.0).coerceIn(0.0, 100.0) else 50.0
        val atrPct = if (lastClose > 0) atr(data) / lastClose * 100.0 else 0.0
        val regime = when {
            adx >= 25.0 -> "رونددار"
            bbWidth < 2.0 -> "فشردگی نوسان"
            else -> "رنج"
        }
        val pattern = detectCandlePattern(data)
        val recent = data.takeLast(60)
        val support = recent.minOf { it[2] }
        val resistance = recent.maxOf { it[1] }
        val structure = detectMarketStructure(data)
        // Ten independent technical sub-models vote on each timeframe.
        if (lastClose > vwap * 1.001) { score++; reasons.add("قیمت بالای VWAP") }
        else if (lastClose < vwap * 0.999) { score--; reasons.add("قیمت زیر VWAP") }
        if (bbPosition >= 0.62) { score++; reasons.add("مومنتوم باند صعودی") }
        else if (bbPosition <= 0.38) { score--; reasons.add("مومنتوم باند نزولی") }
        if (relativeVolume >= 1.20) {
            if (data.last()[3] >= data.last()[0]) { score++; reasons.add("حجم تأییدی خریدار") }
            else { score--; reasons.add("حجم تأییدی فروشنده") }
        }
        if (adx >= 20.0) {
            if (e20 > e50) { score++; reasons.add("قدرت روند صعودی ADX") }
            else if (e20 < e50) { score--; reasons.add("قدرت روند نزولی ADX") }
        }
        when {
            structure.contains("HH/HL") -> { score++; reasons.add("ساختار صعودی بازار") }
            structure.contains("LH/LL") -> { score--; reasons.add("ساختار نزولی بازار") }
        }
        when {
            pattern.contains("صعودی") || pattern.contains("چکش") -> { score++; reasons.add("الگوی کندلی صعودی") }
            pattern.contains("نزولی") || pattern.contains("ثاقب") -> { score--; reasons.add("الگوی کندلی نزولی") }
        }
        if (stochRsi >= 60.0 && stochRsi <= 90.0) { score++; reasons.add("Stoch RSI صعودی") }
        else if (stochRsi <= 40.0 && stochRsi >= 10.0) { score--; reasons.add("Stoch RSI نزولی") }
        val technical = TechnicalSnapshot(e9, e20, e50, e100, e200, adx, bbWidth, bbPosition, relativeVolume, vwap, stochRsi, atrPct, regime, pattern, structure, support, resistance)
        return Quad(score, reasons.joinToString("، "), rsiValue, technical)
    }

    private fun detectCandlePattern(data: List<DoubleArray>): String {
        if (data.size < 3) return "الگوی مشخصی نیست"
        val a = data[data.size - 3]
        val b = data[data.size - 2]
        val c = data.last()
        fun body(x: DoubleArray) = abs(x[3] - x[0])
        fun range(x: DoubleArray) = (x[1] - x[2]).coerceAtLeast(1e-12)
        val upper = c[1] - maxOf(c[0], c[3])
        val lower = minOf(c[0], c[3]) - c[2]
        if (body(c) / range(c) < 0.08) return "دوجی • بی‌تصمیمی"
        if (c[3] > c[0] && lower > body(c) * 2.2 && upper < body(c) * 0.8) return "چکش صعودی"
        if (c[3] < c[0] && upper > body(c) * 2.2 && lower < body(c) * 0.8) return "ستاره ثاقب نزولی"
        if (b[3] < b[0] && c[3] > c[0] && c[0] <= b[3] && c[3] >= b[0]) return "پوشای صعودی"
        if (b[3] > b[0] && c[3] < c[0] && c[0] >= b[3] && c[3] <= b[0]) return "پوشای نزولی"
        if (a[3] < a[0] && body(b) < body(a) * 0.55 && c[3] > c[0] && c[3] > (a[0] + a[3]) / 2.0) return "ستاره صبحگاهی صعودی"
        if (a[3] > a[0] && body(b) < body(a) * 0.55 && c[3] < c[0] && c[3] < (a[0] + a[3]) / 2.0) return "ستاره شامگاهی نزولی"
        if (b[3] > b[0] && c[3] < c[0] && c[0] > b[0] && c[3] < (b[0] + b[3]) / 2.0) return "ابر سیاه نزولی"
        if (b[3] < b[0] && c[3] > c[0] && c[0] < b[0] && c[3] > (b[0] + b[3]) / 2.0) return "خط نفوذی صعودی"
        if (b[3] > b[0] && c[3] < c[0] && c[0] < b[3] && c[3] > b[0]) return "هارامی نزولی"
        if (b[3] < b[0] && c[3] > c[0] && c[0] > b[3] && c[3] < b[0]) return "هارامی صعودی"
        if (abs(b[1] - c[1]) / b[1].coerceAtLeast(1e-12) < 0.0015) return "سقف دوقلو کندلی احتمالی"
        if (abs(b[2] - c[2]) / b[2].coerceAtLeast(1e-12) < 0.0015) return "کف دوقلو کندلی احتمالی"
        return "الگوی پرقدرتی تأیید نشد"
    }

    private fun detectMarketStructure(data: List<DoubleArray>): String {
        if (data.size < 30) return "داده ناکافی"
        val chunks = data.takeLast(60).chunked(10).takeLast(4)
        if (chunks.size < 4) return "در حال شکل‌گیری"
        val highs = chunks.map { it.maxOf { candle -> candle[1] } }
        val lows = chunks.map { it.minOf { candle -> candle[2] } }
        return when {
            highs.zipWithNext().all { it.second > it.first } && lows.zipWithNext().all { it.second > it.first } -> "سقف و کف بالاتر (HH/HL)"
            highs.zipWithNext().all { it.second < it.first } && lows.zipWithNext().all { it.second < it.first } -> "سقف و کف پایین‌تر (LH/LL)"
            else -> "رنج / ساختار ترکیبی"
        }
    }

    private fun calculateAdx(data: List<DoubleArray>, period: Int = 14): Double {
        if (data.size < period * 2 + 1) return 0.0
        val trueRanges = mutableListOf<Double>()
        val plusMoves = mutableListOf<Double>()
        val minusMoves = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val upMove = data[i][1] - data[i - 1][1]
            val downMove = data[i - 1][2] - data[i][2]
            plusMoves.add(if (upMove > downMove && upMove > 0.0) upMove else 0.0)
            minusMoves.add(if (downMove > upMove && downMove > 0.0) downMove else 0.0)
            trueRanges.add(maxOf(
                data[i][1] - data[i][2],
                abs(data[i][1] - data[i - 1][3]),
                abs(data[i][2] - data[i - 1][3])
            ))
        }
        var smoothTr = trueRanges.take(period).sum()
        var smoothPlus = plusMoves.take(period).sum()
        var smoothMinus = minusMoves.take(period).sum()
        val dxValues = mutableListOf<Double>()
        for (i in period until trueRanges.size) {
            if (i > period) {
                smoothTr = smoothTr - smoothTr / period + trueRanges[i]
                smoothPlus = smoothPlus - smoothPlus / period + plusMoves[i]
                smoothMinus = smoothMinus - smoothMinus / period + minusMoves[i]
            }
            val safeTr = smoothTr.coerceAtLeast(1e-12)
            val plusDi = 100.0 * smoothPlus / safeTr
            val minusDi = 100.0 * smoothMinus / safeTr
            val denominator = (plusDi + minusDi).coerceAtLeast(1e-12)
            dxValues.add(abs(plusDi - minusDi) / denominator * 100.0)
        }
        if (dxValues.size < period) return dxValues.lastOrNull() ?: 0.0
        var adx = dxValues.take(period).average()
        for (i in period until dxValues.size) {
            adx = (adx * (period - 1) + dxValues[i]) / period
        }
        return adx.takeIf { it.isFinite() } ?: 0.0
    }

    private fun rsiSeries(values: List<Double>, period: Int): List<Double> {
        if (values.size <= period) return listOf(rsi(values, period))
        return (period until values.size).map { end ->
            rsi(values.subList(0, end + 1), period)
        }
    }

    private data class Quad(val first: Int, val second: String, val third: Double, val technical: TechnicalSnapshot)

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

/** User-visible on-device monitor. Android can still stop it under force-stop, battery policy, reboot, or OS limits. */
class MarketMonitorService : Service() {
    private var worker: kotlinx.coroutines.Job? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var selectedExchange: String = "XT"
    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = getSharedPreferences("market_monitor_config", Context.MODE_PRIVATE)
        if (intent != null) {
            selectedExchange = intent.getStringExtra("exchange") ?: selectedExchange
            config.edit()
                .putString("exchange", selectedExchange)
                .apply()
        } else {
            selectedExchange = config.getString("exchange", selectedExchange) ?: selectedExchange
        }
        startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی پایش بازار"))
        if (worker?.isActive != true) {
            worker = serviceScope.launch {
                while (kotlin.coroutines.coroutineContext.isActive) {
                    val result = runCatching {
                        val signals = if (selectedExchange == "XT") XTMonitor.scan() else BinanceMonitor.scan()
                        val active = signals.count { it.direction != "NEUTRAL" }
                        "اسکن ${signals.size} قرارداد • سیگنال‌های جهت‌دار: $active • $selectedExchange • فقط تحلیل تکنیکال"
                    }.getOrElse { "خطای اسکن $selectedExchange: ${it.message ?: "نامشخص"}" }
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(result))
                    delay(180_000L)
                }
            }
        }
        return START_STICKY
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "پایش بازار CRYPTO AI PRO", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
    private fun buildNotification(status: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("CRYPTO AI PRO • پایش پس‌زمینه")
            .setContentText(status).setOngoing(true).build()
    }
    override fun onDestroy() { worker?.cancel(); serviceScope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL_ID = "crypto_ai_market_monitor"; private const val NOTIFICATION_ID = 4101 }
}

