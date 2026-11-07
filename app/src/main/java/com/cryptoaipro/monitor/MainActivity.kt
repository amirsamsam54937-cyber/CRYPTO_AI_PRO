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
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import java.time.ZoneId
import java.time.ZonedDateTime
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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

// Wolf v2.18 visual system: graphite, silver, electric turquoise and restrained gold.
private val Navy = Color(0xFF050505)
private val Panel = Color(0xFF111111)
private val Muted = Color(0xFFB8B0A0)
private val Green = Color(0xFF20D477)
private val Red = Color(0xFFFF4655)
private val Blue = Color(0xFFFFC52E)
private val Purple = Color(0xFFD9A62E)
private val Gold = Color(0xFFFFD45A)

data class MarketSignal(
    val symbol: String, val price: Double, val change: Double, val volume: Double,
    val direction: String, val score: Int, val rsi: Double, val confidence: Int,
    val reason: String, val entry: Double, val stop: Double, val tp1: Double, val tp2: Double, val tp3: Double, val leverage: Int, val atr: Double,
    val technical: TechnicalSnapshot,
    val candles: List<DoubleArray> = emptyList(),
    val consensus: WolfConsensus? = null,
    val neuralScore: Int = 50,
    val timeframeCoverage: String = "15m / 1h / 4h"
)

data class TechnicalSnapshot(
    val ema9: Double, val ema20: Double, val ema50: Double, val ema100: Double, val ema200: Double,
    val adx: Double, val bollingerWidthPct: Double, val bollingerPosition: Double,
    val relativeVolume: Double, val vwap: Double, val stochasticRsi: Double,
    val atrPercent: Double, val regime: String,
    val candlePattern: String, val marketStructure: String,
    val support: Double, val resistance: Double,
    val cci: Double = 0.0,
    val rocPercent: Double = 0.0,
    val mfi: Double = 50.0,
    val obvTrend: Double = 0.0,
    val supertrendDirection: String = "خنثی",
    val liquiditySweep: String = "مشاهده نشد",
    val fairValueGap: String = "مشاهده نشد",
    val rsiDivergence: String = "مشاهده نشد",
    val macdLine: Double = 0.0,
    val macdSignalLine: Double = 0.0
)

internal fun applyWolfNeuralScoring(context: Context, signals: List<MarketSignal>): List<MarketSignal> {
    val prefs = context.getSharedPreferences("wolf_learning_memory", Context.MODE_PRIVATE)
    // Load the bundled, historically trained ten-network ensemble once; online DNN remains a secondary learner.
    val pretrainedStatus = WolfPretrainedEnsemble.initialize(context)
    return signals.map { signal ->
        if (signal.direction == "NEUTRAL") signal.copy(neuralScore = 50)
        else {
            val pretrained = WolfPretrainedEnsemble.predict(signal)
            val online = WolfDeepNeuralNetwork.predict(prefs, signal)
            val neuralProbability = pretrained ?: online
            val neural = (neuralProbability * 100.0).toInt().coerceIn(1, 99)
            // Experimental scores remain visible, but only a model that beats a temporal baseline may affect confidence.
            val adjustedConfidence = if (pretrained != null && pretrainedStatus.validatedForScoring)
                (signal.confidence * 0.90 + neural * 0.10).toInt().coerceIn(0, 100)
            else signal.confidence
            signal.copy(neuralScore = neural, confidence = adjustedConfidence)
        }
    }.sortedWith(compareByDescending<MarketSignal> {
        if (it.direction == "NEUTRAL") it.score.toDouble() else it.confidence.toDouble()
    }.thenByDescending { it.volume })
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WolfCrashReporter.install(applicationContext)
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
    var scanProgress by remember { mutableStateOf("آماده اسکن") }
    var scannedCount by remember { mutableIntStateOf(0) }
    var totalContracts by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableStateOf("خانه") }
    var selectedSymbol by remember { mutableStateOf("") }
    var chartInterval by remember { mutableStateOf("15") }
    var voiceTranscript by remember { mutableStateOf("") }
    var voiceReply by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appSettings = remember(context) { context.getSharedPreferences("wolf_app_settings", Context.MODE_PRIVATE) }
    var selectedExchange by remember(context) { mutableStateOf(appSettings.getString("market_exchange", "BINANCE") ?: "BINANCE") }
    var scanIntervalMinutes by remember(context) {
        mutableIntStateOf(appSettings.getInt("scan_interval_minutes", 3).coerceIn(1, 10))
    }
    var outcomeSnapshot by remember(context) { mutableStateOf(WolfSignalOutcomeJournal.snapshot(context)) }
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
            val progress: suspend (List<MarketSignal>, Int, Int, Int) -> Unit = { partial, completed, total, failed ->
                withContext(Dispatchers.Main) {
                    rows = applyWolfNeuralScoring(context, WolfFlowNewsRepository.enrichSignals(context, partial))
                    scannedCount = completed
                    totalContracts = total
                    scanProgress = "بررسی $completed از $total قرارداد • خطاهای داده: $failed"
                    if (selectedSymbol.isBlank() || rows.none { it.symbol == selectedSymbol }) selectedSymbol = rows.firstOrNull()?.symbol.orEmpty()
                }
            }
            appSettings.edit().putString("market_exchange", selectedExchange).apply()
            val scanned = if (selectedExchange == "XT") XtFuturesMonitor.scan(progress) else BinanceMonitor.scan(progress)
            rows = applyWolfNeuralScoring(context, WolfFlowNewsRepository.enrichSignals(context, scanned))
            scannedCount = totalContracts
            scanProgress = "پایان اسکن • " + scanned.size + " نتیجه از " + totalContracts + " قرارداد"
            scope.launch { runCatching { WolfFlowNewsRepository.refreshForEngine(context, scanned) } }
            outcomeSnapshot = WolfSignalOutcomeJournal.processScan(context, rows)
            val memoryStats = WolfLearningMemory.processScan(context, rows, outcomeSnapshot.records)
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

    LaunchedEffect(scanIntervalMinutes, selectedExchange) {
        while (isActive) {
            performScan()
            // HTTP 451 is an explicit regional-eligibility denial, not a transient outage.
            // Stop automatic polling instead of repeatedly hitting a blocked endpoint.
            if (error?.contains("HTTP 451") == true) {
                scanProgress = "اسکن خودکار متوقف شد؛ دسترسی Binance از این شبکه مجاز نیست"
                break
            }
            delay(scanIntervalMinutes.toLong() * 60_000L)
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
                NavigationBar(containerColor = Color(0xFF080808), contentColor = Color.White) {
                    listOf("خانه", "بازار", "تحلیل", "دفترچه", "هوش مصنوعی", "تنظیمات").forEach { entry ->
                        NavigationBarItem(
                            selected = selectedTab == entry,
                            onClick = { selectedTab = entry },
                            icon = { Icon(imageVector = when (entry) { "خانه" -> Icons.Outlined.Home; "بازار" -> Icons.Outlined.ShowChart; "تحلیل" -> Icons.Outlined.Insights; "دفترچه" -> Icons.Outlined.MenuBook; "هوش مصنوعی" -> Icons.Outlined.Psychology; else -> Icons.Outlined.Settings }, contentDescription = entry, modifier = Modifier.size(19.dp)) },
                            label = { Text(entry, fontSize = 9.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Blue,
                                selectedTextColor = Blue,
                                indicatorColor = Color(0xFF342607),
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                Image(
                    painter = painterResource(id = R.drawable.wolf_poster),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.105f }
                )
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xE6080808), Color(0xF2050505), Color(0xEA171006)))
                ))
                Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTab != "خانه") TextButton(onClick = { selectedTab = "خانه" }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹ بازگشت", color = Blue, fontSize = 11.sp) }
                    Surface(
                        color = Color(0xFF17130A),
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.size(52.dp),
                        shadowElevation = 8.dp
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_crypto_ai_logo),
                            contentDescription = "نشان گرگ امیر ارسلان",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AMIRARSALAN TRADER WOLF", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        Text("TRADER WOLF  •  LIVE TECHNICAL INTELLIGENCE", color = Muted, fontSize = 8.sp, letterSpacing = 0.5.sp)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { if (selectedExchange != "BINANCE") selectedExchange = "BINANCE" },
                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                                Text("BINANCE", color = if (selectedExchange == "BINANCE") Blue else Muted, fontSize = 9.sp,
                                    fontWeight = if (selectedExchange == "BINANCE") FontWeight.Bold else FontWeight.Normal)
                            }
                            Text("•", color = Muted, fontSize = 9.sp)
                            TextButton(onClick = { if (selectedExchange != "XT") selectedExchange = "XT" },
                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                                Text("XT", color = if (selectedExchange == "XT") Blue else Muted, fontSize = 9.sp,
                                    fontWeight = if (selectedExchange == "XT") FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                        Surface(color = if (loading) Color(0xFF3A2B0B) else Color(0xFF2B2108),
                            shape = RoundedCornerShape(20.dp)) {
                            Text(if (loading) "در حال اسکن" else "● $selectedExchange",
                                color = if (loading) Gold else Green,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 9.sp)
                        }
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
                                Surface(
                                    color = Color(0xFF090909),
                                    shape = RoundedCornerShape(22.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.72f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                        Box(Modifier.fillMaxWidth().height(196.dp)) {
                                            Image(
                                                painter = painterResource(id = R.drawable.wolf_hero),
                                                contentDescription = "گرگ نمادین AMIRARSALAN TRADER WOLF",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Box(Modifier.fillMaxSize().background(
                                                Brush.horizontalGradient(listOf(Color(0xEE050505), Color(0x66050505), Color(0x22050505)))
                                            ))
                                            Column(
                                                Modifier.align(Alignment.CenterStart).padding(start = 18.dp, end = 14.dp),
                                                verticalArrangement = Arrangement.spacedBy(7.dp)
                                            ) {
                                                Text("AMIRARSALAN", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                                                Text("TRADER WOLF", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                                Text("TRADE LIKE A WOLF", color = Gold, fontSize = 9.sp, letterSpacing = 2.sp)
                                                Text("قدرت تحلیل • هوش مصنوعی • سیگنال هوشمند", color = Color.White, fontSize = 10.sp)
                                            }
                                            Surface(
                                                color = Color(0xCC090909), shape = RoundedCornerShape(30.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.7f)),
                                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                                            ) {
                                                Text("FUTURES  •  AI", color = Gold, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                            }
                                        }
                                        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("اتاق فرمان گرگ", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                                                    Text("تحلیل تکنیکال زنده  •  Binance / XT", color = Gold, fontSize = 10.sp)
                                                    Text("سازنده: amir arsalan samsami", color = Muted, fontSize = 9.sp)
                                                }
                                                Surface(color = Color(0xFF211805), shape = RoundedCornerShape(12.dp),
                                                    border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.55f))) {
                                                    Text("WOLF  AI", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                                                }
                                            }
                                            MarketSessionsPanel()
                                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
                                            Text(scanProgress, color = Muted, fontSize = 10.sp)
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
                            }
                            item {
                                ActiveCommandSignals(
                                    records = outcomeSnapshot.records.filter { !it.closed },
                                    onOpenJournal = { selectedTab = "دفترچه" }
                                )
                            }
                            item { WolfAutoIntelPanel(context) }
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("فرصت‌های برتر بازار", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
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
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("$selectedExchange • قراردادهای دائمی USDT", color = Blue, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                Text(rows.size.toString() + " نتیجه", color = Muted, fontSize = 10.sp)
                            }
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
                            Text(scanProgress, color = Muted, fontSize = 10.sp)
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
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("5" to "۵د", "15" to "۱۵د", "60" to "۱س", "240" to "۴س", "1D" to "۱روز").forEach { (interval, label) ->
                                        FilterChip(
                                            selected = chartInterval == interval,
                                            onClick = { chartInterval = interval },
                                            label = { Text(label, fontSize = 10.sp) }
                                        )
                                    }
                                }
                            }
                            item { TradingViewPairChart(selectedSymbol = selectedSignal?.symbol ?: "BTCUSDT", interval = chartInterval) }
                            selectedSignal?.let { signal -> item { SignalLevelsChart(signal) } }
                            if (voiceTranscript.isNotBlank() || voiceReply.isNotBlank()) {
                                item {
                                    Surface(color = Color(0xFF252D31), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
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
                                item { SignalOutcomePanel(outcomeSnapshot) }
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
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                SmallStat("CCI", String.format(Locale.US, "%.1f", selectedSignal.technical.cci))
                                                SmallStat("ROC %", String.format(Locale.US, "%.2f", selectedSignal.technical.rocPercent))
                                                SmallStat("MFI", String.format(Locale.US, "%.1f", selectedSignal.technical.mfi))
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                SmallStat("OBV", String.format(Locale.US, "%.2f", selectedSignal.technical.obvTrend))
                                                SmallStat("Supertrend", selectedSignal.technical.supertrendDirection)
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                SmallStat("MACD", String.format(Locale.US, "%.6f", selectedSignal.technical.macdLine))
                                                SmallStat("Signal", String.format(Locale.US, "%.6f", selectedSignal.technical.macdSignalLine))
                                                SmallStat("BB Width %", String.format(Locale.US, "%.2f", selectedSignal.technical.bollingerWidthPct))
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                SmallStat("BB Position", String.format(Locale.US, "%.2f", selectedSignal.technical.bollingerPosition))
                                                SmallStat("Stoch RSI", String.format(Locale.US, "%.1f", selectedSignal.technical.stochasticRsi))
                                                SmallStat("ATR %", String.format(Locale.US, "%.2f", selectedSignal.technical.atrPercent))
                                            }
                                            Text("الگوی کندلی: " + selectedSignal.technical.candlePattern, color = Blue, fontSize = 11.sp)
                                            Text("الگوی هارمونیک: " + detectHarmonicPattern(selectedSignal.candles), color = Purple, fontSize = 11.sp)
                                            Text("ساختار: " + selectedSignal.technical.marketStructure + " • رژیم: " + selectedSignal.technical.regime, color = Muted, fontSize = 10.sp)
                                            Text("نقدینگی: " + selectedSignal.technical.liquiditySweep, color = Gold, fontSize = 10.sp)
                                            Text("FVG: " + selectedSignal.technical.fairValueGap, color = Blue, fontSize = 10.sp)
                                            Text("واگرایی: " + selectedSignal.technical.rsiDivergence, color = Purple, fontSize = 10.sp)
                                            Divider(color = Color(0xFF3A454A))
                                            Text("اجماع ۱۰ تحلیلگر تخصصی گرگ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text("توافق وزنی: " + (selectedSignal.consensus?.agreement ?: 0) + "% • قدرت سیگنال: " + selectedSignal.confidence, color = Blue, fontSize = 10.sp)
                                            selectedSignal.consensus?.votes?.forEach { vote ->
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(vote.name, color = Muted, fontSize = 10.sp)
                                                    Text(vote.direction + " • " + String.format(Locale.US, "%.2f", vote.score), color = when (vote.direction) { "LONG" -> Green; "SHORT" -> Red; else -> Muted }, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            Divider(color = Color(0xFF3A454A))
                                            val pretrainedStatus = WolfPretrainedEnsemble.initialize(context)
                                            Text(
                                                if (pretrainedStatus.loaded) "مغز عصبی AMIR WOLF • ۱۰ شبکه پیش‌آموزش‌دیده"
                                                else "مغز عصبی AMIR WOLF • یادگیری محلی آزمایشی",
                                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp
                                            )
                                            Text(if (pretrainedStatus.loaded) "۱۰ شبکه عمیق فعال • کندل‌های واقعی: " + String.format(Locale.US, "%,d", pretrainedStatus.corpusRows) + " • نمونه‌های آموزش: " + String.format(Locale.US, "%,d", pretrainedStatus.samples) + " • دقت آزمون زمانی: " + String.format(Locale.US, "%.1f%%", pretrainedStatus.testAccuracy * 100.0) + " • خط پایه: " + String.format(Locale.US, "%.1f%%", pretrainedStatus.baselineAccuracy * 100.0) + " • دقت متوازن: " + String.format(Locale.US, "%.1f%%", pretrainedStatus.balancedAccuracy * 100.0) + if (pretrainedStatus.validatedForScoring) " • اثر محدود بر امتیاز سیگنال فعال" else " • مدل آزمایشی؛ بدون اثر بر اعتماد سیگنال" else "مدل پیش‌آموزش‌دیده هنوز در این بسته بارگذاری نشده؛ امتیاز شبکه محلی در حالت پشتیبان است.", color = if (pretrainedStatus.loaded && pretrainedStatus.validatedForScoring) Green else Gold, fontSize = 10.sp)
                                            Text("تاریخ آموزش UTC: " + if (pretrainedStatus.trainedAt.isBlank()) "نامشخص" else pretrainedStatus.trainedAt, color = Muted, fontSize = 9.sp)
                                            Text("حافظه یادگیری تطبیقی و پایدار روی گوشی", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            val memoryStats = WolfLearningMemory.stats(context)
                                            Text("نمونه‌های ارزیابی‌شده: " + memoryStats.samples + " • موفق: " + memoryStats.wins + " • ناموفق: " + memoryStats.losses, color = Muted, fontSize = 10.sp)
                                            Text("در انتظار ارزیابی ۴ ساعته: " + memoryStats.pending + " • آموزش خودکار امروز: " + memoryStats.learnedToday, color = Blue, fontSize = 10.sp)
                                            Text("شبکه عصبی از نتایج واقعیِ قابل‌مشاهده سیگنال‌های ثبت‌شده (TP، SL یا انقضای ۱۵ دقیقه‌ای) به‌طور خودکار آموزش می‌بیند؛ افق ارزیابی ۴ ساعته نیز برای نمونه‌های بدون نتیجه بسته‌شده باقی است. وزن‌ها روی گوشی ذخیره می‌شوند.", color = Muted, fontSize = 9.sp)
                                            Text("بازخورد ذخیره‌شده: " + learningSamples + " • درست: " + learningWins + " • نادرست: " + learningLosses, color = Muted, fontSize = 10.sp)
                                            Text("امتیاز اجماع عصبی ۱۰مدلی: " + selectedSignal.neuralScore + " از ۱۰۰", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                if (pretrainedStatus.loaded) "ده شبکه با داده‌های تاریخی فیوچرز آموزش دیده‌اند؛ حافظه آنلاین نیز از نتیجه سیگنال‌های بسته‌شده یاد می‌گیرد. معیار آزمون گذشته‌نگر، تضمین سود یا پیش‌بینی قطعی آینده نیست."
                                                else "در نبود بسته وزن پیش‌آموزش‌دیده، فقط شبکه محلی از نتایج سیگنال‌های ثبت‌شده یاد می‌گیرد؛ هیچ وزن ساختگی یا امتیاز تأییدنشده‌ای استفاده نمی‌شود.",
                                                color = Muted, fontSize = 9.sp
                                            )
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
                    }"دفترچه" -> {
                        SignalJournalScreen(outcomeSnapshot)
                    }

                    "هوش مصنوعی" -> {
                        Column(Modifier.fillMaxSize()) {
                            WolfAiCompanionScreen(selectedSignal)
                            WolfKnowledgeBaseScreen()
                        }
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
                                    Text("بازار: Binance USDⓈ-M Futures", color = Muted, fontSize = 11.sp)
                                    Text("اسکن پایه: ۱۵ دقیقه، ۱ ساعت و ۴ ساعت • بازبینی عمیق ۲۴ نامزد برتر: ۵ دقیقه و ۱ روز", color = Muted, fontSize = 11.sp)
                                    Text("چارت قابل انتخاب: ۵ دقیقه، ۱۵ دقیقه، ۱ ساعت، ۴ ساعت و ۱ روز", color = Muted, fontSize = 11.sp)
                                    Text("دامنه اسکن: تمام قراردادهای دائمی USDT فعال؛ مرتب‌شده بر اساس حجم معاملات", color = Muted, fontSize = 11.sp)
                                    Text("فاصله مکث بین دورهای اسکن (پس از پایان هر دور):", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(1, 3, 5, 10).forEach { minutes ->
                                            FilterChip(
                                                selected = scanIntervalMinutes == minutes,
                                                onClick = {
                                                    scanIntervalMinutes = minutes
                                                    appSettings.edit().putInt("scan_interval_minutes", minutes).apply()
                                                },
                                                label = { Text("$minutes دقیقه", fontSize = 9.sp) }
                                            )
                                        }
                                    }
                                    Text("اسکن پیوسته پس از پایان هر دور، به‌اندازه فاصله انتخابی مکث می‌کند؛ زمان خود اسکن و محدودیت API به آن اضافه می‌شود.", color = Muted, fontSize = 10.sp)
                                    Text("نسخه: 2.19.0 • Binance / XT • تحلیل و سیگنال فقط", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("سازنده: amir arsalan samsami", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Medium)
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
}

private fun iranDateTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("yyyy/MM/dd  HH:mm:ss", Locale("fa", "IR"))
    formatter.timeZone = java.util.TimeZone.getTimeZone("Asia/Tehran")
    return formatter.format(Date(timestamp)) + "  (ایران)"
}

@Composable
private fun ActiveCommandSignals(
    records: List<WolfSignalOutcomeJournal.Record>,
    onOpenJournal: () -> Unit
) {
    Surface(color = Color(0xFF100D07), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.38f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("سیگنال‌های فعال فرماندهی", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(records.size.toString() + " فعال", color = Gold, fontSize = 10.sp)
            }
            Text("سیگنال با برخورد به اولین حد سود یا حد ضرر فوراً به دفترچه منتقل می‌شود؛ اگر هیچ‌کدام رخ ندهد تا پایان اعتبار ۱۵ دقیقه‌ای فعال می‌ماند.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
            if (records.isEmpty()) {
                Text("در حال حاضر سیگنال معتبر فعالی وجود ندارد؛ با دور بعدی اسکن دوباره بررسی می‌شود.", color = Muted, fontSize = 10.sp)
            } else {
                records.take(8).forEach { record ->
                    val tint = if (record.side == "LONG_BIAS") Green else Red
                    Surface(color = Panel, shape = RoundedCornerShape(11.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(record.symbol, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(if (record.side == "LONG_BIAS") "LONG" else "SHORT", color = tint, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                Text(" • " + record.timeframe, color = Muted, fontSize = 9.sp)
                            }
                            Text("ورود سیگنال: " + iranDateTime(record.created), color = Blue, fontSize = 9.sp)
                            Text("اعتبار تا: " + iranDateTime(record.expiresAt), color = Muted, fontSize = 9.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                SmallStat("ورود", formatPrice(record.entry), Blue)
                                SmallStat("حد ضرر", formatPrice(record.stop), Red)
                                SmallStat("TP1", formatPrice(record.tp1), Green)
                            }
                            Text("قدرت تحلیلی: " + record.confidence + "% • وضعیت: " + WolfSignalOutcomeJournal.statusLabel(record.status), color = Muted, fontSize = 9.sp)
                        }
                    }
                }
            }
            TextButton(onClick = onOpenJournal, modifier = Modifier.align(Alignment.End)) { Text("مشاهده دفترچه کامل ←", color = Blue) }
        }
    }
}

@Composable
private fun SignalJournalScreen(snapshot: WolfSignalOutcomeJournal.Snapshot) {
    var filter by remember { mutableStateOf("همه") }
    val visible = when (filter) {
        "باز" -> snapshot.records.filter { !it.closed }
        "بسته" -> snapshot.records.filter { it.closed }
        else -> snapshot.records
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("دفترچه دائمی سیگنال‌های گرگ", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("زمان ورود و خروج با ساعت ایران ثبت می‌شود. برخورد به حد سود/ضرر یا پایان اعتبار ۱۵ دقیقه‌ای، سیگنال را به دفترچه منتقل می‌کند و سوابق قابل مشاهده می‌مانند. اطلاعات فقط روی همین گوشی نگهداری می‌شود.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SmallStat("کل سیگنال‌ها", snapshot.total.toString(), Blue)
                        SmallStat("باز", snapshot.active.toString(), Gold)
                        SmallStat("TP3", snapshot.tp3.toString(), Green)
                        SmallStat("SL", snapshot.stopped.toString(), Red)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SmallStat("نرخ برد", String.format(Locale.US, "%.1f%%", snapshot.winRate), Green)
                        SmallStat("بازده تخمینی کل", String.format(Locale.US, "%+.2f%%", snapshot.totalPnlPct), if (snapshot.totalPnlPct >= 0) Green else Red)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SmallStat("میانگین برد", String.format(Locale.US, "%+.2f%%", snapshot.averageWinPct), Green)
                        SmallStat("میانگین باخت", String.format(Locale.US, "%+.2f%%", snapshot.averageLossPct), Red)
                    }
                    snapshot.bestRecord?.let { best ->
                        Text("بیشترین بازده: " + best.symbol + " • " + (if (best.side == "LONG_BIAS") "LONG" else "SHORT") +
                            " • " + String.format(Locale.US, "%+.2f%%", best.pnlPct), color = Green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("همه", "باز", "بسته").forEach { option ->
                            FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                        }
                    }
                    Text("نمایش " + visible.size + " مورد از حداکثر ۵۰۰۰ رکورد اخیر", color = Muted, fontSize = 9.sp)
                }
            }
        }
        if (visible.isEmpty()) {
            item { Text("هنوز سیگنال جهت‌داری ثبت نشده است. با ادامه اسکن خودکار، سیگنال‌ها اینجا ذخیره می‌شوند.", color = Muted, fontSize = 11.sp) }
        } else {
            items(visible, key = { it.created.toString() + "_" + it.symbol }) { record ->
                val tint = if (record.side == "LONG_BIAS") Green else Red
                val date = iranDateTime(record.created)
                Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(record.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(if (record.side == "LONG_BIAS") "LONG • صعودی" else "SHORT • نزولی", color = tint, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(date, color = Muted, fontSize = 9.sp)
                            Text(WolfSignalOutcomeJournal.statusLabel(record.status), color = when(record.status) {
                                "TP1", "TP2", "TP3" -> Green
                                "SL" -> Red
                                "EXPIRED" -> Muted
                                else -> Gold
                            }, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallStat("ورود", formatPrice(record.entry), Blue)
                            SmallStat("حد ضرر", formatPrice(record.stop), Red)
                            SmallStat("TP1", formatPrice(record.tp1), Green)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallStat("TP2", formatPrice(record.tp2), Green)
                            SmallStat("TP3", formatPrice(record.tp3), Green)
                            SmallStat("قدرت سیگنال", record.confidence.toString() + "%", tint)
                        }
                        Text("امتیاز: " + record.score + " • شبکه عصبی: " + record.neuralScore + "% • اهرم پیشنهادی: " + record.leverage + "x • تغییر ۲۴ساعت: " + String.format(Locale.US, "%.2f%%", record.change), color = Muted, fontSize = 9.sp)
                        if (record.closed) {
                            Text("قیمت خروج: " + formatPrice(record.exitPrice) + " • بازده تخمینی با اهرم: " +
                                String.format(Locale.US, "%+.2f%%", record.pnlPct), color = if (record.pnlPct >= 0) Green else Red,
                                fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            Text("زمان بسته‌شدن: " + iranDateTime(record.closedAt), color = Muted, fontSize = 9.sp)
                        }
                        if (record.reason.isNotBlank()) Text(record.reason, color = Muted, fontSize = 9.sp, maxLines = 3)
                    }
                }
            }
        }
        item { Text("سود/ضرر بر اساس قیمت عمومی و اهرم پیشنهادی به‌صورت تخمینی محاسبه می‌شود؛ کارمزد، فاندینگ، لغزش قیمت و اجرای سفارش واقعی لحاظ نشده‌اند. مدل کوچک داخلی پس از بسته‌شدن سیگنال‌ها به‌طور خودکار از نتایج برچسب‌دار یاد می‌گیرد.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp) }
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
    Surface(color = Color(0xFF101010), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.25f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp)) {
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
            Text("پوشش تحلیل: " + item.timeframeCoverage, color = Blue, fontSize = 9.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SmallStat("قیمت", formatPrice(item.price))
                SmallStat("تغییر ۲۴ساعت", String.format(Locale.US, "%.2f%%", item.change), if (item.change >= 0) Green else Red)
                SmallStat("RSI", String.format(Locale.US, "%.1f", item.rsi))
                SmallStat("امتیاز خام", item.score.toString())
                SmallStat("امتیاز عصبی", item.neuralScore.toString() + "/100")
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("قدرت هم‌جهتی تایم‌فریم‌ها", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(item.confidence.toString() + "%", color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { item.confidence / 100f },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = tint, trackColor = Color(0xFF3A3020)
            )
            if (item.direction != "NEUTRAL") {
                Spacer(Modifier.height(8.dp))
                Surface(color = Color(0xFF19150B), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.22f)), modifier = Modifier.fillMaxWidth()) {
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
                Surface(color = Color(0xFF19150B), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.22f)), modifier = Modifier.fillMaxWidth()) {
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
private fun SignalOutcomePanel(snapshot: WolfSignalOutcomeJournal.Snapshot) {
    Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("کارنامه سیگنال‌های برنامه (شبیه‌سازی، بدون سفارش واقعی)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SmallStat("کل", snapshot.total.toString())
                SmallStat("باز", snapshot.active.toString(), Blue)
                SmallStat("TP1", snapshot.tp1.toString(), Green)
                SmallStat("TP2", snapshot.tp2.toString(), Green)
                SmallStat("TP3", snapshot.tp3.toString(), Green)
                SmallStat("SL", snapshot.stopped.toString(), Red)
            }
            Text("آخرین نتایج", color = Muted, fontSize = 10.sp)
            if (snapshot.recent.isEmpty()) Text("هنوز نتیجه‌ای ثبت نشده؛ پس از سیگنال و دریافت کندل‌های بعدی، وضعیت به‌روزرسانی می‌شود.", color = Muted, fontSize = 10.sp)
            snapshot.recent.forEach { line -> Text(line, color = Color.White, fontSize = 10.sp) }
            Text("سیگنال با اولین برخورد قیمت به TP یا SL فوراً از مرکز فرماندهی به دفترچه منتقل می‌شود؛ در غیر این صورت تا پایان ۱۵ دقیقه فعال می‌ماند. خروجی‌ها بر اساس قیمت عمومی و اسکن دوره‌ای تخمین زده می‌شوند.", color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun SignalLevelsChart(signal: MarketSignal) {
    val candles = signal.candles.takeLast(64)
    val allPrices = candles.flatMap { listOf(it[1], it[2]) } + listOf(signal.entry, signal.stop, signal.tp1, signal.tp2, signal.tp3)
    val rawLow = allPrices.filter { it > 0.0 && it.isFinite() }.minOrNull() ?: signal.price * 0.98
    val rawHigh = allPrices.filter { it > 0.0 && it.isFinite() }.maxOrNull() ?: signal.price * 1.02
    val padding = (rawHigh - rawLow).coerceAtLeast(signal.price * 0.001) * 0.06
    val low = rawLow - padding
    val high = rawHigh + padding
    val range = (high - low).coerceAtLeast(signal.price * 0.001)
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("نقشه ورود و خروج سیگنال روی نمودار", color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("● ورود", color = Blue, fontSize = 10.sp)
                Text("━ حد سود", color = Green, fontSize = 10.sp)
                Text("━ حد ضرر", color = Red, fontSize = 10.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                val left = 8f
                val right = size.width - 8f
                val top = 8f
                val bottom = size.height - 8f
                fun y(price: Double) = bottom - (((price - low) / range).toFloat() * (bottom - top))
                val step = (right - left) / candles.size.coerceAtLeast(1)
                candles.forEachIndexed { index, candle ->
                    if (candle.size >= 4) {
                        val x = left + step * (index + 0.5f)
                        val color = if (candle[3] >= candle[0]) Green else Red
                        drawLine(color.copy(alpha = 0.8f), Offset(x, y(candle[1])), Offset(x, y(candle[2])), 1.5f)
                        val bodyTop = y(maxOf(candle[0], candle[3]))
                        val bodyBottom = y(minOf(candle[0], candle[3]))
                        drawLine(color, Offset(x, bodyTop), Offset(x, maxOf(bodyTop + 2f, bodyBottom)), (step * 0.58f).coerceAtLeast(2f))
                    }
                }
                fun level(price: Double, color: Color, width: Float = 1.5f) {
                    if (price > low && price < high && price.isFinite()) {
                        val yy = y(price)
                        drawLine(color, Offset(left, yy), Offset(right, yy), width)
                    }
                }
                level(signal.entry, Blue, 2.5f)
                level(signal.stop, Red, 2.5f)
                level(signal.tp1, Green, 2.5f)
                level(signal.tp2, Green.copy(alpha = 0.75f), 2f)
                level(signal.tp3, Green.copy(alpha = 0.5f), 1.5f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                PriceLevelRow("ورود مرجع", signal.entry, Blue)
                PriceLevelRow("حد سود ۱", signal.tp1, Green)
                PriceLevelRow("حد سود ۲", signal.tp2, Green)
                PriceLevelRow("حد سود ۳", signal.tp3, Green)
                PriceLevelRow("حد ضرر", signal.stop, Red)
            }
            Text("سطوح از تحلیل فعلی محاسبه شده‌اند؛ رسیدن قیمت به سطح در بازار واقعی تضمین نمی‌شود.", color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun PriceLevelRow(label: String, price: Double, tint: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(formatPrice(price), color = Color.White, fontSize = 10.sp)
    }
}

@Composable
private fun TradingViewPairChart(selectedSymbol: String, interval: String) {
    val symbol = "BINANCE:" + selectedSymbol.replace("/", "").replace(":USDT", "").replace("_", "") + ".P"
    val chartUrl = "https://www.tradingview.com/chart/?symbol=" +
        java.net.URLEncoder.encode(symbol, "UTF-8") + "&interval=" +
        java.net.URLEncoder.encode(interval, "UTF-8")
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("چارت آنلاین " + selectedSymbol + " • TradingView", color = Color.White, fontWeight = FontWeight.Bold)
            Text("فقط نمودار همان ارزی که سیگنال آن انتخاب شده نمایش داده می‌شود.", color = Muted, fontSize = 9.sp)
            AndroidView(modifier = Modifier.fillMaxWidth().height(320.dp),
                factory = { context -> WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(true)
                    webViewClient = WebViewClient()
                } },
                update = { view ->
                    // Avoid reloading the full chart on every Compose state update.
                    if (view.tag != chartUrl) {
                        view.tag = chartUrl
                        view.loadUrl(chartUrl)
                    }
                })
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
    val rAD = kotlin.math.abs(p[4] - p[0]) / xa
    fun near(v: Double, target: Double, tolerance: Double = 0.12) = kotlin.math.abs(v - target) <= tolerance
    val side = if (p[4] < p[3]) "صعودی" else "نزولی"
    return when {
        near(rAB, 0.618, 0.10) && rBC in 0.382..0.886 && rCD in 1.272..1.618 && near(rAD, 0.786, 0.10) -> "گارتلی " + side + " • ناحیه احتمالی تکمیل"
        rAB in 0.382..0.50 && rBC in 0.382..0.886 && rCD in 1.618..2.618 && near(rAD, 0.886, 0.12) -> "بت " + side + " • ناحیه احتمالی تکمیل"
        near(rAB, 0.786, 0.10) && rBC in 0.382..0.886 && rCD in 1.50..2.20 && near(rAD, 1.27, 0.16) -> "باترفلای " + side + " • ناحیه احتمالی تکمیل"
        rAB in 0.382..0.618 && rBC in 0.382..0.886 && rCD in 2.24..3.618 && near(rAD, 1.618, 0.18) -> "خرچنگ " + side + " • ناحیه احتمالی تکمیل"
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
    Surface(color = Color(0xFF100D07), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.28f)), modifier = Modifier.fillMaxWidth()) {
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


/** Binance USDⓈ-M public futures scanner; public endpoints only. */

internal object BinanceMonitor {
    // Use the documented canonical USDⓈ-M API endpoint only.
    // Alternate hostnames must not be used to evade an explicit regional-eligibility response.
    private const val BASE_URL = "https://fapi.binance.com"
    private val requestGate = Semaphore(permits = 8)

    private fun get(path: String): String {
        var lastError = "اتصال به API عمومی Binance برقرار نشد"
        for (attempt in 0..2) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 9000
                    setRequestProperty("User-Agent", "AmirArsalanTraderWolf/2.14")
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) return body

                val detail = body.take(180)
                when {
                    code == 451 -> throw IllegalStateException(
                        "Binance HTTP 451: دسترسی این سرویس از شبکه یا موقعیت فعلی مجاز نیست. " +
                        "این خطا با تکرار درخواست یا تعویض آدرس حل نمی‌شود. اسکن Binance متوقف شد؛ " +
                        "برای داده زنده باید از منبعی استفاده شود که ارائه‌دهنده‌اش دسترسی آن را برای محل اتصال شما مجاز کرده باشد. " +
                        detail
                    )
                    code == 418 -> throw IllegalStateException(
                        "Binance HTTP 418: آی‌پی موقتاً مسدود شده است؛ درخواست‌های خودکار متوقف شدند. $detail"
                    )
                    code == 403 -> throw IllegalStateException(
                        "Binance HTTP 403: درخواست توسط سیاست دسترسی یا WAF رد شد. $detail"
                    )
                    code == 429 || code in 500..599 -> {
                        lastError = "Binance HTTP $code: $detail"
                        if (attempt < 2) Thread.sleep(700L * (attempt + 1))
                    }
                    else -> throw IllegalStateException("Binance HTTP $code: $detail")
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                if (attempt < 2) Thread.sleep(500L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("دریافت داده Binance پس از تلاش محدود ناموفق بود: $lastError")
    }

    suspend fun scan(onProgress: suspend (List<MarketSignal>, Int, Int, Int) -> Unit = { _, _, _, _ -> }): List<MarketSignal> = withContext(Dispatchers.IO) {
        val exchangeInfo = JSONObject(get("/fapi/v1/exchangeInfo"))
        val allowed = mutableSetOf<String>()
        val symbols = exchangeInfo.getJSONArray("symbols")
        for (i in 0 until symbols.length()) {
            val s = symbols.getJSONObject(i)
            if (s.optString("status") == "TRADING" && s.optString("quoteAsset") == "USDT" && s.optString("contractType") == "PERPETUAL") allowed.add(s.optString("symbol"))
        }
        val tickers = JSONArray(get("/fapi/v1/ticker/24hr"))
        val ranked = (0 until tickers.length()).map { tickers.getJSONObject(it) }
            .filter { allowed.contains(it.optString("symbol")) && it.optDouble("quoteVolume", 0.0) > 0.0 }
            .sortedByDescending { it.optDouble("quoteVolume", 0.0) }
        if (ranked.isEmpty()) throw IllegalStateException("Binance هیچ قرارداد دائمی USDT قابل اسکن برنگرداند")
        val results = mutableListOf<MarketSignal>()
        var completed = 0
        var failed = 0
        for (batch in ranked.chunked(12)) {
            val batchResults = coroutineScope {
                batch.map { ticker -> async {
                    runCatching { analyze(ticker.optString("symbol"), ticker.optDouble("lastPrice"), ticker.optDouble("priceChangePercent"), ticker.optDouble("quoteVolume")) }
                } }.awaitAll()
            }
            batchResults.forEach { result -> result.onSuccess { results.add(it) }.onFailure { failed++ } }
            completed += batch.size
            onProgress(results.sortedByDescending { abs(it.score) }, completed, ranked.size, failed)
        }
        if (results.isEmpty()) throw IllegalStateException("هیچ کندلی دریافت نشد؛ خطا در " + failed + " نماد. اتصال اینترنت، محدودیت منطقه‌ای یا پاسخ Binance را بررسی کن.")

        // Deep-review only the strongest candidates on 5m and 1D. This preserves
        // broad universe coverage while keeping request volume bounded.
        val rankedResults = results.sortedByDescending { abs(it.score) }
        val deepReview = rankedResults.take(24)
        val refined = coroutineScope {
            deepReview.map { base -> async {
                runCatching { analyzeExtended(base.symbol, base.price, base.change, base.volume) }
            } }.awaitAll()
        }
        val replacements = mutableMapOf<String, MarketSignal>()
        refined.forEach { result -> result.onSuccess { replacements[it.symbol] = it }.onFailure { failed++ } }
        val finalResults = rankedResults.map { replacements[it.symbol] ?: it }.sortedByDescending { abs(it.score) }
        onProgress(finalResults, completed, ranked.size, failed)
        finalResults
    }

    private suspend fun analyze(symbol: String, price: Double, change: Double, volume: Double): MarketSignal {
        // Broad scan: 15m / 1h / 4h for every active USDT perpetual contract.
        val frames = coroutineScope {
            val short = async { candles(symbol, "15m") }
            val confirm = async { candles(symbol, "1h") }
            val trend = async { candles(symbol, "4h") }
            Triple(short.await(), confirm.await(), trend.await())
        }
        return analyzeWithCandles(symbol, price, change, volume, frames.first, frames.second, frames.third)
    }

    private suspend fun analyzeExtended(symbol: String, price: Double, change: Double, volume: Double): MarketSignal {
        // Bounded deep review for top-ranked candidates adds micro timing and daily context.
        val frames = coroutineScope {
            val micro = async { candles(symbol, "5m") }
            val short = async { candles(symbol, "15m") }
            val confirm = async { candles(symbol, "1h") }
            val trend = async { candles(symbol, "4h") }
            val macro = async { candles(symbol, "1d") }
            listOf(micro.await(), short.await(), confirm.await(), trend.await(), macro.await())
        }
        return analyzeWithCandles(symbol, price, change, volume, frames[1], frames[2], frames[3], frames[0], frames[4])
    }

    internal fun analyzeWithCandles(symbol: String, price: Double, change: Double, volume: Double,
                                    short: List<DoubleArray>, confirm: List<DoubleArray>,
                                    trend: List<DoubleArray>, micro: List<DoubleArray>? = null,
                                    macro: List<DoubleArray>? = null): MarketSignal {
        val a = indicators(short)
        val b = indicators(confirm)
        val c = indicators(trend)
        val microStats = micro?.let { indicators(it) }
        val macroStats = macro?.let { indicators(it) }
        val frames = buildList {
            microStats?.let { add(it.technical) }
            add(a.technical); add(b.technical); add(c.technical)
            macroStats?.let { add(it.technical) }
        }
        val score = (a.first + b.first + c.first + (microStats?.first ?: 0) * 0.35 + (macroStats?.first ?: 0) * 1.10).toInt()
        val wolf = WolfConsensusEngine.analyze(frames, price)
        val atrValue = atr(short).coerceAtLeast(price * 0.0001)
        val stretched = abs(price - a.technical.ema20) > atrValue * 2.2
        val extremeVolatility = a.technical.atrPercent > 10.0
        val fourHourConflict = (wolf.direction == "LONG_BIAS" && c.technical.marketStructure.contains("LH/LL")) ||
            (wolf.direction == "SHORT_BIAS" && c.technical.marketStructure.contains("HH/HL"))
        val dailyConflict = macroStats != null && (
            (wolf.direction == "LONG_BIAS" && (macroStats.technical.ema20 < macroStats.technical.ema50 ||
                macroStats.technical.marketStructure.contains("LH/LL"))) ||
            (wolf.direction == "SHORT_BIAS" && (macroStats.technical.ema20 > macroStats.technical.ema50 ||
                macroStats.technical.marketStructure.contains("HH/HL")))
        )
        val filtered = stretched || extremeVolatility || fourHourConflict || dailyConflict
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
            dailyConflict -> " | فیلتر: روند روزانه با جهت سیگنال تعارض دارد"
            else -> ""
        }
        return MarketSignal(
            symbol, price, change, volume, direction, score, a.third, confidence,
            "اجماع گرگ " + wolf.agreement + "% | قدرت اجماع (نه احتمال برد): " + wolf.strengthIndex +
                "% | ۱۰ تحلیلگر: " + agentSummary + " | ۱۵د: " + a.second + " | ۱س: " + b.second +
                " | ۴س: " + c.second +
                (microStats?.let { " | ۵د: " + it.second } ?: "") +
                (macroStats?.let { " | ۱روز: " + it.second } ?: "") + filterNote,
            price, stop, tp1, tp2, tp3, leverage, atrValue, a.technical, short.takeLast(80), wolf,
            timeframeCoverage = if (microStats != null && macroStats != null) "5m / 15m / 1h / 4h / 1D" else "15m / 1h / 4h"
        )
    }

    private suspend fun candles(symbol: String, interval: String): List<DoubleArray> {
        // 500 closed candles give the 200-period EMA a more useful warm-up window.
        val rawText = requestGate.withPermit {
            withContext(Dispatchers.IO) {
                get("/fapi/v1/klines?symbol=" + symbol + "&interval=" + interval + "&limit=500")
            }
        }
        val raw = JSONArray(rawText)
        val intervalMs = when (interval) {
            "5m" -> 5L * 60_000L
            "15m" -> 15L * 60_000L
            "1h" -> 60L * 60_000L
            "4h" -> 4L * 60L * 60_000L
            "1d" -> 24L * 60L * 60_000L
            else -> throw IllegalArgumentException("Unsupported Binance interval: $interval")
        }
        val closed = (0 until (raw.length() - 1).coerceAtLeast(0)).map { i ->
            val row = raw.getJSONArray(i)
            // Index 5 stores the candle open timestamp; analysis fields remain at indexes 0..4.
            doubleArrayOf(row.getString(1).toDouble(), row.getString(2).toDouble(), row.getString(3).toDouble(),
                row.getString(4).toDouble(), row.getString(5).toDouble(), row.getLong(0).toDouble())
        }
        require(closed.size >= 210) { "Insufficient closed candles for EMA200" }
        require(closed.all { candle ->
            candle.all { it.isFinite() } && candle[0] > 0.0 && candle[1] > 0.0 && candle[2] > 0.0 && candle[3] > 0.0 &&
                candle[4] >= 0.0 && candle[1] >= maxOf(candle[0], candle[3]) &&
                candle[2] <= minOf(candle[0], candle[3]) && candle[1] >= candle[2] && candle[5] > 0.0
        }) { "Invalid OHLCV candle data from Binance" }
        val timestamps = closed.map { it[5].toLong() }
        require(timestamps.zipWithNext().all { (a, b) -> b > a }) { "Duplicate or out-of-order candle timestamps" }
        require(timestamps.zipWithNext().all { (a, b) -> b - a <= intervalMs * 2L }) { "Candle history contains a material time gap" }
        val lastClosedAt = timestamps.last() + intervalMs
        require(System.currentTimeMillis() - lastClosedAt <= intervalMs * 2L) { "Stale candle data from Binance" }
        return closed
    }

    private fun atr(data: List<DoubleArray>, period: Int = 14): Double {
        if (data.size < period + 1) return data.last()[3] * 0.01
        val ranges = (1 until data.size).map { i ->
            val high = data[i][1]; val low = data[i][2]; val prevClose = data[i-1][3]
            maxOf(high-low, abs(high-prevClose), abs(low-prevClose))
        }
        return ranges.takeLast(period).average()
    }
    // Each candle stores open, high, low, close, base volume, open timestamp; indicators use indexes 0..4.
    private fun indicators(data: List<DoubleArray>): Quad {
        val closes = data.map { it[3] }
        if (closes.size < 210) throw IllegalStateException("برای محاسبه معتبر EMA200 حداقل ۲۱۰ کندل بسته‌شده لازم است")
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

        // Additional price/volume evidence: CCI, ROC, MFI, OBV, Supertrend,
        // liquidity sweep, three-candle imbalance and RSI divergence.
        val typical = data.map { (it[1] + it[2] + it[3]) / 3.0 }
        val cciWindow = typical.takeLast(20)
        val cciMean = cciWindow.average()
        val meanDeviation = cciWindow.map { abs(it - cciMean) }.average().coerceAtLeast(1e-12)
        val cci = (typical.last() - cciMean) / (0.015 * meanDeviation)
        val roc = if (closes.size > 12 && closes[closes.lastIndex - 12] > 0.0)
            (lastClose / closes[closes.lastIndex - 12] - 1.0) * 100.0 else 0.0
        var positiveFlow = 0.0
        var negativeFlow = 0.0
        for (i in (data.size - 14).coerceAtLeast(1) until data.size) {
            val moneyFlow = typical[i] * data[i][4]
            if (typical[i] > typical[i - 1]) positiveFlow += moneyFlow
            else if (typical[i] < typical[i - 1]) negativeFlow += moneyFlow
        }
        val mfi = if (negativeFlow <= 1e-12) 100.0 else 100.0 - 100.0 / (1.0 + positiveFlow / negativeFlow)
        var obv = 0.0
        var obvAbsVolume = 0.0
        for (i in (data.size - 21).coerceAtLeast(1) until data.size) {
            val signed = when { closes[i] > closes[i - 1] -> data[i][4]; closes[i] < closes[i - 1] -> -data[i][4]; else -> 0.0 }
            obv += signed
            obvAbsVolume += data[i][4]
        }
        val obvTrend = if (obvAbsVolume > 0.0) (obv / obvAbsVolume).coerceIn(-1.0, 1.0) else 0.0
        // Standardized ATR-based Supertrend band state, iterated candle by candle.
        val trSeries = (1 until data.size).map { i ->
            maxOf(data[i][1] - data[i][2], abs(data[i][1] - data[i - 1][3]), abs(data[i][2] - data[i - 1][3]))
        }
        var finalUpper = 0.0
        var finalLower = 0.0
        var supertrendBull = true
        for (i in 10 until data.size) {
            val atrIndex = i - 1
            val atrValue = trSeries.subList((atrIndex - 9).coerceAtLeast(0), atrIndex + 1).average()
                .coerceAtLeast(lastClose * 1e-8)
            val basicUpper = (data[i][1] + data[i][2]) / 2.0 + 3.0 * atrValue
            val basicLower = (data[i][1] + data[i][2]) / 2.0 - 3.0 * atrValue
            val previousClose = data[i - 1][3]
            finalUpper = if (i == 10 || basicUpper < finalUpper || previousClose > finalUpper) basicUpper else finalUpper
            finalLower = if (i == 10 || basicLower > finalLower || previousClose < finalLower) basicLower else finalLower
            supertrendBull = when {
                supertrendBull && data[i][3] < finalLower -> false
                !supertrendBull && data[i][3] > finalUpper -> true
                else -> supertrendBull
            }
        }
        val supertrendDirection = when {
            supertrendBull && lastClose > finalLower -> "صعودی"
            !supertrendBull && lastClose < finalUpper -> "نزولی"
            else -> "خنثی"
        }
        val prior = data.dropLast(1).takeLast(20)
        val priorHigh = prior.maxOfOrNull { it[1] } ?: data.last()[1]
        val priorLow = prior.minOfOrNull { it[2] } ?: data.last()[2]
        val lastCandle = data.last()
        val liquiditySweep = when {
            lastCandle[1] > priorHigh && lastClose < priorHigh -> "جمع‌آوری نقدینگی بالای سقف • احتمال برگشت نزولی"
            lastCandle[2] < priorLow && lastClose > priorLow -> "جمع‌آوری نقدینگی زیر کف • احتمال برگشت صعودی"
            else -> "شکار نقدینگی واضح دیده نشد"
        }
        val fairValueGap = when {
            data.size >= 3 && data.last()[2] > data[data.lastIndex - 2][1] ->
                "عدم‌تعادل صعودی سه‌کندلی"
            data.size >= 3 && data.last()[1] < data[data.lastIndex - 2][2] ->
                "عدم‌تعادل نزولی سه‌کندلی"
            else -> "گپ ارزش منصفانه تازه مشاهده نشد"
        }
        val rsiTrail = rsiSeries(closes, 14)
        val rsiDivergence = if (rsiTrail.size >= 28) {
            val recentPrice = closes.takeLast(14).average()
            val previousPrice = closes.takeLast(28).dropLast(14).average()
            val recentRsi = rsiTrail.takeLast(14).average()
            val previousRsi = rsiTrail.takeLast(28).dropLast(14).average()
            when {
                recentPrice < previousPrice && recentRsi > previousRsi + 3.0 -> "واگرایی مثبت احتمالی RSI"
                recentPrice > previousPrice && recentRsi < previousRsi - 3.0 -> "واگرایی منفی احتمالی RSI"
                else -> "واگرایی RSI تأیید نشد"
            }
        } else "داده واگرایی ناکافی"

        if (cci > 100.0) { score++; reasons.add("CCI مومنتوم مثبت") }
        else if (cci < -100.0) { score--; reasons.add("CCI مومنتوم منفی") }
        if (roc > 0.5) { score++; reasons.add("ROC مثبت") }
        else if (roc < -0.5) { score--; reasons.add("ROC منفی") }
        if (mfi > 60.0) { score++; reasons.add("جریان پول MFI مثبت") }
        else if (mfi < 40.0) { score--; reasons.add("جریان پول MFI منفی") }
        if (obvTrend > 0.15) { score++; reasons.add("OBV انباشت حجم") }
        else if (obvTrend < -0.15) { score--; reasons.add("OBV توزیع حجم") }
        if (supertrendDirection == "صعودی") { score++; reasons.add("Supertrend صعودی") }
        else if (supertrendDirection == "نزولی") { score--; reasons.add("Supertrend نزولی") }
        if (liquiditySweep.contains("برگشت نزولی")) { score--; reasons.add("sweep سقف نقدینگی") }
        else if (liquiditySweep.contains("برگشت صعودی")) { score++; reasons.add("sweep کف نقدینگی") }
        if (rsiDivergence.contains("مثبت")) reasons.add("هشدار واگرایی مثبت RSI")
        else if (rsiDivergence.contains("منفی")) reasons.add("هشدار واگرایی منفی RSI")

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
        val technical = TechnicalSnapshot(
            e9, e20, e50, e100, e200, adx, bbWidth, bbPosition, relativeVolume, vwap,
            stochRsi, atrPct, regime, pattern, structure, support, resistance,
            cci, roc, mfi, obvTrend, supertrendDirection, liquiditySweep, fairValueGap, rsiDivergence,
            macdValues.first, macdValues.second
        )
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
        val start = (data.size - 120).coerceAtLeast(0)
        val highs = mutableListOf<Pair<Int, Double>>()
        val lows = mutableListOf<Pair<Int, Double>>()
        for (i in maxOf(2, start) until data.size - 2) {
            val high = data[i][1]
            val low = data[i][2]
            if ((i - 2..i + 2).all { data[it][1] <= high }) highs.add(i to high)
            if ((i - 2..i + 2).all { data[it][2] >= low }) lows.add(i to low)
        }
        if (highs.size < 2 || lows.size < 2) return "در حال شکل‌گیری ساختار"
        val lastHighs = highs.takeLast(3).map { it.second }
        val lastLows = lows.takeLast(3).map { it.second }
        val higherHighs = lastHighs.size >= 2 && lastHighs.zipWithNext().all { it.second > it.first }
        val higherLows = lastLows.size >= 2 && lastLows.zipWithNext().all { it.second > it.first }
        val lowerHighs = lastHighs.size >= 2 && lastHighs.zipWithNext().all { it.second < it.first }
        val lowerLows = lastLows.size >= 2 && lastLows.zipWithNext().all { it.second < it.first }
        val bullishTrend = higherHighs && higherLows
        val bearishTrend = lowerHighs && lowerLows
        val close = data.last()[3]
        val brokenHigh = close > highs.last().second && data.lastIndex - highs.last().first >= 1
        val brokenLow = close < lows.last().second && data.lastIndex - lows.last().first >= 1
        return when {
            brokenHigh && bearishTrend -> "CHOCH صعودی • تغییر کاراکتر پس از LH/LL"
            brokenLow && bullishTrend -> "CHOCH نزولی • تغییر کاراکتر پس از HH/HL"
            brokenHigh -> "BOS صعودی • شکست سقف ساختاری" + if (bullishTrend) " • HH/HL" else ""
            brokenLow -> "BOS نزولی • شکست کف ساختاری" + if (bearishTrend) " • LH/LL" else ""
            bullishTrend -> "ساختار صعودی • HH/HL"
            bearishTrend -> "ساختار نزولی • LH/LL"
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
    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی پایش بازار"))
        if (worker?.isActive != true) {
            worker = serviceScope.launch {
                while (kotlin.coroutines.coroutineContext.isActive) {
                    val result = runCatching {
                        val selectedExchange = getSharedPreferences("wolf_app_settings", Context.MODE_PRIVATE).getString("market_exchange", "BINANCE") ?: "BINANCE"
                        val marketSignals = if (selectedExchange == "XT") XtFuturesMonitor.scan() else BinanceMonitor.scan()
                        val signals = applyWolfNeuralScoring(applicationContext, marketSignals)
                        val journalSnapshot = WolfSignalOutcomeJournal.processScan(applicationContext, signals)
                        WolfLearningMemory.processScan(applicationContext, signals, journalSnapshot.records)
                        WolfFlowNewsRepository.refreshForEngine(applicationContext, signals)
                        val active = signals.count { it.direction != "NEUTRAL" }
                        "اسکن ${signals.size} قرارداد $selectedExchange • سیگنال‌های جهت‌دار: $active • فقط تحلیل تکنیکال"
                    }.getOrElse { "خطای اسکن بازار: ${it.message ?: "نامشخص"}" }
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(result))
                    // Stop retrying an explicit regional/access denial; polling cannot fix HTTP 451.
                    if (result.contains("HTTP 451") || result.contains("HTTP 403") || result.contains("HTTP 418")) break
                    val intervalMinutes = getSharedPreferences("wolf_app_settings", Context.MODE_PRIVATE)
                        .getInt("scan_interval_minutes", 3).coerceIn(1, 10)
                    delay(intervalMinutes.toLong() * 60_000L)
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
