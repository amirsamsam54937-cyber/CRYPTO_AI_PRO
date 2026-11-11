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

// Hope Hunter visual system: obsidian, warm ivory and refined champagne gold.
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
    val timeframeCoverage: String = "15m / 1h / 4h",
    val neuralDirection: String = "NEUTRAL",
    val neuralModelSource: String = "در انتظار مدل",
    val technicalDirection: String = "NEUTRAL",
    val tradeTimeframe: String = "در انتظار انتخاب",
    val signalTier: String = "بدون رتبه",
    val agreementStatus: String = "در انتظار تطبیق",
    // Dedicated 1h snapshot: the pretrained model was trained on 1h candles.
    val neuralTechnical: TechnicalSnapshot? = null,
    val neuralRsi: Double? = null,
    val neuralCandles: List<DoubleArray> = emptyList()
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
    // The pretrained model can influence signals only after a chronological holdout validates it.
    val pretrainedStatus = WolfPretrainedEnsemble.initialize(context)
    return signals.map { signal ->
        val candidate = when {
            signal.direction != "NEUTRAL" -> signal
            signal.score > 0 -> signal.copy(direction = "LONG_BIAS")
            signal.score < 0 -> signal.copy(direction = "SHORT_BIAS")
            else -> null
        }
        if (candidate == null) signal.copy(neuralScore = 50, neuralDirection = "NEUTRAL",
            neuralModelSource = if (pretrainedStatus.validatedForScoring) "۱۰مدلی • معتبر" else "مدل در انتظار اعتبارسنجی")
        else {
            // Never let unvalidated pretrained weights or randomly initialized online weights
            // decide a public signal. Until the chronological holdout beats its baseline,
            // the neural vote is explicitly neutral and the final gate returns NO TRADE.
            if (!pretrainedStatus.validatedForScoring) {
                signal.copy(
                    neuralScore = 50,
                    neuralDirection = "NEUTRAL",
                    neuralModelSource = "مدل آموزش دیده اما اعتبارسنجی نشده • رأی غیرفعال"
                )
            } else {
                val probability = WolfPretrainedEnsemble.predict(candidate)
                if (probability == null) {
                    signal.copy(
                        neuralScore = 50,
                        neuralDirection = "NEUTRAL",
                        neuralModelSource = "مدل معتبر در دسترس نیست"
                    )
                } else {
                    // Candidate-relative correctness mapped onto a fixed LONG/SHORT axis.
                    val candidateConfidence = (probability * 100.0).toInt().coerceIn(1, 99)
                    val directionalScore = if (candidate.direction == "LONG_BIAS")
                        candidateConfidence else 100 - candidateConfidence
                    val predictedSide = when {
                        directionalScore > 50 -> "LONG_BIAS"
                        directionalScore < 50 -> "SHORT_BIAS"
                        else -> "NEUTRAL"
                    }
                    val directionalStrength = maxOf(directionalScore, 100 - directionalScore)
                    val adjustedConfidence =
                        (signal.confidence * 0.90 + directionalStrength * 0.10).toInt().coerceIn(0, 100)
                    signal.copy(
                        neuralScore = directionalScore,
                        neuralDirection = predictedSide,
                        neuralModelSource = "۱۰مدلی • اعتبارسنجی‌شده",
                        confidence = adjustedConfidence
                    )
                }
            }
        }
    }.map { signal ->
        // Independent scenario analysis followed by a skeptical adversarial review.
        WolfSignalQualityEngine.apply(WolfForecastEngine.apply(WolfRegimeRiskEngine.apply(WolfTimeSeriesEngine.apply(WolfMathematicsEngine.apply(WolfAdversarialReviewEngine.apply(WolfQuantumScenarioEngine.apply(signal)))))))
    }.map { reviewed ->
        // A not-yet-validated neural model must never become a hard lock on all live signals.
        // Until it beats the chronological baseline, publish only independently strong
        // technical setups; keep the neural vote visibly disabled and advisory-only.
        val tech = reviewed.technicalDirection
        val neural = reviewed.neuralDirection
        val bothDirectional = (tech == "LONG_BIAS" || tech == "SHORT_BIAS") &&
            (neural == "LONG_BIAS" || neural == "SHORT_BIAS")
        val neuralStrength = maxOf(reviewed.neuralScore, 100 - reviewed.neuralScore)
        val neuralValidated = WolfPretrainedEnsemble.status().validatedForScoring
        val neuralAgreement = neuralValidated && bothDirectional && tech == neural && neuralStrength >= 55
        val technicalOnlyQualified = !neuralValidated &&
            (tech == "LONG_BIAS" || tech == "SHORT_BIAS") &&
            reviewed.direction == tech &&
            reviewed.confidence >= 68 &&
            (reviewed.consensus?.agreement ?: 0) >= 65 &&
            reviewed.technical.adx >= 18.0 &&
            reviewed.technical.relativeVolume >= 0.80
        val tier = when {
            reviewed.direction == "NEUTRAL" -> "بدون رتبه"
            neuralAgreement && reviewed.confidence >= 82 && neuralStrength >= 70 -> "نوع ۱ • ممتاز"
            neuralAgreement && reviewed.confidence >= 72 && neuralStrength >= 62 -> "نوع ۲ • تأییدشده"
            neuralAgreement && reviewed.confidence >= 62 && neuralStrength >= 55 -> "نوع ۳ • محتاطانه"
            technicalOnlyQualified -> "نوع ۳ • تکنیکال مستقل"
            else -> "بدون رتبه"
        }
        val promoted = (neuralAgreement || technicalOnlyQualified) &&
            tier != "بدون رتبه" && reviewed.direction == tech
        reviewed.copy(
            direction = if (promoted) tech else "NEUTRAL",
            score = if (promoted) reviewed.score else 0,
            confidence = if (promoted) reviewed.confidence else minOf(reviewed.confidence, 49),
            signalTier = if (promoted) tier else "بدون رتبه",
            agreementStatus = when {
                promoted && technicalOnlyQualified -> "تأیید مستقل تکنیکال؛ رأی شبکه عصبی تا اعتبارسنجی غیرفعال است"
                promoted -> "توافق دو موتور معتبر • مجاز به نمایش سیگنال"
                !neuralValidated -> "مدل عصبی هنوز اعتبارسنجی نشده؛ شرایط مستقل تکنیکال برای انتشار کافی نیست"
                !bothDirectional -> "داده کافی برای رأی دوگانه نیست"
                tech != neural -> "اختلاف تحلیل تکنیکال و شبکه عصبی • سیگنال رد شد"
                neuralStrength < 55 -> "جهت یکسان، قدرت عصبی ناکافی"
                else -> "توافق وجود دارد اما کیفیت به حد انتشار نرسید"
            }
        )
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
    var selectedExchange by remember(context) { mutableStateOf("XT") }
    var scanIntervalMinutes by remember(context) {
        mutableIntStateOf(appSettings.getInt("scan_interval_minutes", 3).coerceIn(1, 10))
    }
    var outcomeSnapshot by remember(context) { mutableStateOf(WolfSignalOutcomeJournal.snapshot(context)) }
    var backtestSnapshot by remember(context) { mutableStateOf(WolfHistoricalBacktestEngine.load(context)) }
    var backtestEnabled by remember(context) { mutableStateOf(appSettings.getBoolean("historical_backtest_enabled", true)) }
    var backtestLoading by remember { mutableStateOf(false) }
    var backtestStatus by remember { mutableStateOf("بک‌تست هنوز اجرا نشده است.") }
    var backtestJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val learningPrefs = remember(context) { context.getSharedPreferences("wolf_learning_memory", Context.MODE_PRIVATE) }
    var learningSamples by remember { mutableIntStateOf(learningPrefs.getInt("samples", 0)) }
    var learningWins by remember { mutableIntStateOf(learningPrefs.getInt("wins", 0)) }
    var learningLosses by remember { mutableIntStateOf(learningPrefs.getInt("losses", 0)) }
    var researchStats by remember { mutableStateOf(WolfMarketDataStore.Stats()) }
    LaunchedEffect(Unit) {
        researchStats = WolfMarketDataStore.importVerifiedSeed(context)
    }
    val voiceEngine = remember(context) { TextToSpeech(context) { } }
    DisposableEffect(voiceEngine) { onDispose { voiceEngine.stop(); voiceEngine.shutdown() } }
    LaunchedEffect(voiceReply) { if (voiceReply.isNotBlank()) { voiceEngine.language = Locale("fa", "IR"); voiceEngine.speak(voiceReply, TextToSpeech.QUEUE_FLUSH, null, "hope-hunter-voice-reply") } }
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
            val scanned = XtFuturesMonitor.scan(progress)
            researchStats = WolfMarketDataStore.recordLiveScan(context, selectedExchange, scanned)
            rows = applyWolfNeuralScoring(context, WolfFlowNewsRepository.enrichSignals(context, scanned))
            scannedCount = totalContracts
            scanProgress = "پایان اسکن • " + scanned.size + " نتیجه از " + totalContracts + " قرارداد"
            scope.launch { runCatching { WolfFlowNewsRepository.refreshForEngine(context, scanned, selectedExchange) } }
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
            val rawMessage = e.message ?: "خطا در دریافت اطلاعات بازار"
            error = if (rawMessage.contains("<html", ignoreCase = true) ||
                rawMessage.contains("<!doctype", ignoreCase = true) ||
                rawMessage.contains("<head", ignoreCase = true)) {
                "پاسخ وب به‌جای داده بازار دریافت شد؛ احتمالاً WAF یا محدودیت دسترسی صرافی است. HTML خام حذف شد. در صورت تداوم خطای XT، منبع XT را انتخاب کن."
            } else rawMessage.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(240)
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
                scanProgress = "اسکن خودکار XT متوقف شد؛ دسترسی به داده‌های عمومی XT برقرار نیست"
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
                    listOf("خانه", "بازار", "قدرت سیگنال", "دفترچه", "تنظیمات").forEach { entry ->
                        NavigationBarItem(
                            selected = selectedTab == entry,
                            onClick = { selectedTab = entry },
                            icon = { Icon(imageVector = when (entry) { "خانه" -> Icons.Outlined.Home; "بازار" -> Icons.Outlined.ShowChart; "قدرت سیگنال" -> Icons.Outlined.Insights; "دفترچه" -> Icons.Outlined.MenuBook; "هوش مصنوعی" -> Icons.Outlined.Psychology; else -> Icons.Outlined.Settings }, contentDescription = entry, modifier = Modifier.size(19.dp)) },
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
                            contentDescription = "نشان اتاق شکار امیدها",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("اتاق شکار امیدها", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        Text("HOPE HUNTER  •  INTELLIGENT MARKET ANALYSIS", color = Muted, fontSize = 8.sp, letterSpacing = 0.5.sp)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("XT FUTURES", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                                            HopeHunterHeroArt(Modifier.fillMaxSize())
                                            Box(Modifier.fillMaxSize().background(
                                                Brush.horizontalGradient(listOf(Color(0xEE050505), Color(0x66050505), Color(0x22050505)))
                                            ))
                                            Column(
                                                Modifier.align(Alignment.CenterStart).padding(start = 18.dp, end = 14.dp),
                                                verticalArrangement = Arrangement.spacedBy(7.dp)
                                            ) {
                                                Text("HOPE HUNTER", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                                                Text("اتاق شکار امیدها", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                                Text("هوشمندی در شکار فرصت‌ها", color = Gold, fontSize = 9.sp, letterSpacing = 2.sp)
                                                Text("تحلیل چندلایه • کشف فرصت • مدیریت ریسک", color = Color.White, fontSize = 10.sp)
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
                                                    Text("اتاق شکار امیدها", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                                                    Text("تحلیل تکنیکال زنده  •  XT Futures", color = Gold, fontSize = 10.sp)
                                                    Text("سازنده: amir arsalan samsami", color = Muted, fontSize = 9.sp)
                                                }
                                                Surface(color = Color(0xFF211805), shape = RoundedCornerShape(12.dp),
                                                    border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.55f))) {
                                                    Text("هوش شکار", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                                                }
                                            }
                                            // Market-session timing remains internal to the analysis engine.
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
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("فرصت‌های برتر بازار", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                    Text("آخرین بروزرسانی " + updated, color = Muted, fontSize = 9.sp)
                                }
                            }
                            if (loading && rows.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) } }
                            error?.let { msg -> item { Text(msg, color = Red, fontSize = 11.sp) } }
                            items(rows.take(5), key = { it.symbol }) { signal ->
                                SignalCard(signal, onSelect = { selectedSymbol = signal.symbol; selectedTab = "قدرت سیگنال" })
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
                                        SignalCard(signal, onSelect = { selectedSymbol = signal.symbol; selectedTab = "قدرت سیگنال" })
                                    }
                                }
                            }
                        }
                    }

                    "قدرت سیگنال" -> {
                        SignalStrengthScreen(signals = rows, loading = loading, progress = scanProgress,
                            researchStats = researchStats,
                            onSelect = { symbol -> selectedSymbol = symbol },
                            onOpenJournal = { selectedTab = "دفترچه" },
                            onRescan = { scope.launch { performScan() } })
                    }

                    "دفترچه" -> {
                        SignalJournalScreen(
                            outcomeSnapshot, backtestSnapshot, backtestEnabled, backtestLoading, backtestStatus,
                            onToggleBacktest = { enabled ->
                                backtestEnabled = enabled
                                appSettings.edit().putBoolean("historical_backtest_enabled", enabled).apply()
                                if (!enabled) {
                                    backtestJob?.cancel()
                                    backtestJob = null
                                    backtestLoading = false
                                    backtestStatus = "بک‌تست غیرفعال شد."
                                } else backtestStatus = "بک‌تست فعال است؛ فقط با فشردن دکمه اجرا می‌شود."
                            },
                            onRunBacktest = {
                                if (!backtestEnabled) backtestStatus = "ابتدا بک‌تست را فعال کن."
                                
                                else if (rows.isEmpty()) backtestStatus = "ابتدا اسکن بازار را اجرا کن."
                                else if (backtestJob?.isActive != true) {
                                    backtestJob = scope.launch {
                                        backtestLoading = true
                                        backtestStatus = "در حال انتخاب بازه تاریخی تازه و دریافت تاریخچه چندتایم‌فریمی..."
                                        try {
                                            backtestSnapshot = WolfHistoricalBacktestEngine.run(
                                                context, rows.sortedByDescending { it.confidence }.take(4).map { it.symbol }
                                            )
                                            backtestStatus = "پایان آزمایش • ${backtestSnapshot.samples} نقطه • ${backtestSnapshot.durationMs / 1000} ثانیه"
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            backtestStatus = "بک‌تست متوقف شد."
                                            throw e
                                        } catch (e: Exception) {
                                            backtestStatus = "بک‌تست کامل نشد: " + (e.message ?: e.javaClass.simpleName)
                                        } finally {
                                            backtestLoading = false
                                            backtestJob = null
                                        }
                                    }
                                }
                            },
                            onStopBacktest = {
                                backtestJob?.cancel()
                                backtestJob = null
                                backtestLoading = false
                                backtestStatus = "درخواست توقف ارسال شد."
                            }
                        )
                    }

                                        else -> {
                        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("تنظیمات و پایش پس‌زمینه", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("پایش زنده بازار", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("با شروع سرویس، اعلان دائمی اندروید نمایش داده می‌شود و اسکن بازار در پس‌زمینه ادامه می‌یابد. توقف اجباری، قطع اینترنت یا محدودیت باتری می‌تواند سرویس را متوقف کند.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                                    Text("پایش زنده هنگام باز بودن برنامه فعال است. برای دریافت داده‌های تازه، اسکن بازار را از صفحهٔ خانه اجرا کنید.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                                }
                            }
                            Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("پروفایل تحلیل", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("بازار: XT USDT-M Futures", color = Muted, fontSize = 11.sp)
                                    Text("اسکن پایه: ۱۵ دقیقه، ۱ ساعت و ۴ ساعت • بازبینی عمیق ۲۴ نامزد برتر: ۵ دقیقه و ۱ روز", color = Muted, fontSize = 11.sp)
                                    Text("ابزارها: ۱۰ متخصص تحلیلی، شبکه عصبی آزمایشی، چندتایم‌فریم، مدیریت ریسک و دفترچه سوابق", color = Muted, fontSize = 11.sp)
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
                                    Text("نسخه: 2.45.0 • XT Futures • تحلیل و سیگنال فقط", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
    val formatter = SimpleDateFormat("yyyy/MM/dd  HH:mm:ss", Locale.US)
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
private fun SignalStrengthScreen(
    signals: List<MarketSignal>, loading: Boolean, progress: String,
    researchStats: WolfMarketDataStore.Stats,
    onSelect: (String) -> Unit, onOpenJournal: () -> Unit, onRescan: () -> Unit
) {
    var strengthFilter by remember { mutableStateOf("همه") }
    val directional = signals.filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
    val visible = when (strengthFilter) {
        "صعودی" -> directional.filter { it.direction == "LONG_BIAS" }
        "نزولی" -> directional.filter { it.direction == "SHORT_BIAS" }
        "قوی" -> directional.filter { it.confidence >= 75 }
        else -> signals
    }.sortedWith(compareByDescending<MarketSignal> { it.confidence }.thenByDescending { it.score })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(color = Panel, shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.45f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("مرکز هوشمندی سیگنال", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("رتبه‌بندی زنده فرصت‌ها بر اساس هم‌جهتی تایم‌فریم‌ها، قدرت روند، حجم، ساختار بازار و امتیاز مدل عصبی", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
                    Text(progress, color = Muted, fontSize = 9.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        MetricCard("کل نمادها", signals.size.toString(), Modifier.weight(1f))
                        MetricCard("LONG", directional.count { it.direction == "LONG_BIAS" }.toString(), Modifier.weight(1f), Green)
                        MetricCard("SHORT", directional.count { it.direction == "SHORT_BIAS" }.toString(), Modifier.weight(1f), Red)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Button(onClick = onRescan, enabled = !loading, modifier = Modifier.weight(1f)) { Text("اسکن بازار") }
                        OutlinedButton(onClick = onOpenJournal, modifier = Modifier.weight(1f)) { Text("دفترچه سیگنال‌ها") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("همه", "صعودی", "نزولی", "قوی").forEach { option ->
                            FilterChip(selected = strengthFilter == option, onClick = { strengthFilter = option }, label = { Text(option, fontSize = 10.sp) })
                        }
                    }
                    Text("سیگنال قوی: قدرت ۷۵٪ به بالا؛ این امتیاز تضمین سود یا احتمال قطعی موفقیت نیست.", color = Gold, fontSize = 9.sp)
                    val model = WolfPretrainedEnsemble.status()
                    Text(
                        "مغز داده: ${if (researchStats.imported) "۵۰۰٬۰۰۰ ردیف تاریخی وارد پایگاه محلی شده" else "در حال آماده‌سازی پایگاه تاریخی"} • کندل زنده ذخیره‌شده: ${researchStats.liveCandles} • سوابق تحلیل: ${researchStats.signalSnapshots}",
                        color = Muted, fontSize = 9.sp, lineHeight = 14.sp
                    )
                    Text(
                        "شبکه عصبی: ${if (model.loaded) model.members.toString() + " مدل بارگذاری‌شده" else "مدل بارگذاری نشده"} • داده آموزش گزارش‌شده: ${model.samples} • پیش‌بینی مستقل در کارت‌ها فعال است؛ اثر بر رتبه‌بندی اصلی: ${if (model.validatedForScoring) "مجاز" else "غیرفعال تا اعتبارسنجی"}",
                        color = if (model.validatedForScoring) Green else Gold, fontSize = 9.sp, lineHeight = 14.sp
                    )
                    researchStats.error?.let { Text("وضعیت پایگاه داده: $it", color = Red, fontSize = 9.sp) }
                }
            }
        }
        if (visible.isEmpty()) item { Text(if (loading) "در حال دریافت داده‌های بازار..." else "در این فیلتر هنوز سیگنالی وجود ندارد. اسکن بازار را اجرا کن.", color = Muted, fontSize = 11.sp) }
        else items(visible, key = { it.symbol }) { signal -> SignalCard(signal, onSelect = { onSelect(signal.symbol) }) }
        item { Text("ابزارها: فیلتر LONG/SHORT، پیش‌بینی مستقل شبکه عصبی آزمایشی، شاخص‌های تکنیکال، هدف TP1، حد ضرر، اهرم پیشنهادی و ثبت خودکار سوابق.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp) }
    }
}

@Composable
private fun SignalJournalScreen(
    snapshot: WolfSignalOutcomeJournal.Snapshot,
    backtest: WolfHistoricalBacktestEngine.Snapshot,
    backtestEnabled: Boolean,
    backtestLoading: Boolean,
    backtestStatus: String,
    onToggleBacktest: (Boolean) -> Unit,
    onRunBacktest: () -> Unit,
    onStopBacktest: () -> Unit
) {
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
            val modelStatus = WolfPretrainedEnsemble.status()
            Surface(
                color = Color(0xFF0E0C08),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.72f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("HOPE HUNTER  •  PERFORMANCE LAB", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    Text("مرکز گزارش و ارزیابی عملکرد", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
                    Text("آمار زیر از سوابق ثبت‌شده روی همین دستگاه و آخرین اجرای بک‌تست خوانده می‌شود؛ هیچ داده نمایشی یا سود فرضی به‌عنوان نتیجه واقعی درج نمی‌شود.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SmallStat("کل سوابق", snapshot.total.toString(), Gold)
                        SmallStat("نرخ برد", String.format(Locale.US, "%.1f%%", snapshot.winRate), Green)
                        SmallStat("بازده ثبت‌شده", String.format(Locale.US, "%+.2f%%", snapshot.totalPnlPct), if (snapshot.totalPnlPct >= 0) Green else Red)
                    }
                    Divider(color = Gold.copy(alpha = 0.24f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("وضعیت شبکه عصبی", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                if (modelStatus.validatedForScoring) "اعتبارسنجی زمانی تأیید شده؛ رأی مدل مجاز است."
                                else "مدل بارگذاری‌شده تا عبور از اعتبارسنجی زمانی فقط پژوهشی است و در صدور سیگنال زنده دخالت ندارد.",
                                color = if (modelStatus.validatedForScoring) Green else Gold,
                                fontSize = 9.sp, lineHeight = 14.sp
                            )
                            Text("مدل‌های بارگذاری‌شده: ${modelStatus.members} از ۱۰  •  نمونه‌های آموزش گزارش‌شده: ${modelStatus.samples}", color = Muted, fontSize = 9.sp)
                        }
                        Surface(
                            color = if (modelStatus.validatedForScoring) Color(0xFF12351F) else Color(0xFF382A0B),
                            shape = RoundedCornerShape(30.dp)
                        ) {
                            Text(
                                if (modelStatus.validatedForScoring) "معتبر" else "پژوهشی",
                                color = if (modelStatus.validatedForScoring) Green else Gold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        item {
            Surface(
                color = Color(0xFF15120B), shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("آزمایشگاه بک‌تست تاریخی", color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("تایم‌فریم را موتور تحلیل انتخاب می‌کند. شبکه عصبی مستقل از شرط تأیید سیگنال زنده در داده تاریخی ارزیابی می‌شود؛ تا زمان عبور از اعتبارسنجی، نتیجه آن فقط پژوهشی است و اجازه صدور سیگنال زنده ندارد. نمونه‌گیری زمانی به‌صورت یکنواخت و قابل تکرار انجام می‌شود.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (backtestEnabled) "بک‌تست فعال" else "بک‌تست خاموش", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("فقط با لمس دکمه اجرا می‌شود؛ خاموش‌کردن، اجرا را متوقف می‌کند.", color = Muted, fontSize = 9.sp)
                        }
                        Switch(checked = backtestEnabled, onCheckedChange = onToggleBacktest)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRunBacktest, enabled = backtestEnabled && !backtestLoading,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)
                        ) { Text(if (backtestLoading) "در حال آزمایش..." else "اجرای بک‌تست") }
                        OutlinedButton(onClick = onStopBacktest, enabled = backtestLoading) {
                            Text("توقف", color = Red)
                        }
                    }
                    Text(backtestStatus, color = if (backtestLoading) Gold else Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    if (backtest.completedAt > 0L) {
                        Divider(color = Gold.copy(alpha = 0.22f))
                        Text("آخرین نتیجه • ${WolfHistoricalBacktestEngine.formatTime(backtest.completedAt)} • مدت: ${backtest.durationMs / 1000} ثانیه", color = Color.White, fontSize = 10.sp)
                        Text("نمادها: ${backtest.symbols.joinToString("، ")} • نقاط ارزیابی: ${backtest.samples} • توافق: ${backtest.agreements} • اختلاف: ${backtest.disagreements}", color = Muted, fontSize = 9.sp)
                        Text("مقایسه جداگانه دو موتور و روش توافقی", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        val winner = when {
                            backtest.technical.trades < 30 || backtest.neural.trades < 30 -> "نمونه معاملات برای انتخاب برنده کافی نیست؛ حداقل ۳۰ معامله برای هر موتور لازم است."
                            backtest.technical.netReturnPct > backtest.neural.netReturnPct -> "در این نمونه: سیستم تکنیکال بازده بیشتری داشت"
                            backtest.neural.netReturnPct > backtest.technical.netReturnPct -> "در این نمونه: شبکه عصبی بازده بیشتری داشت"
                            else -> "بازده دو موتور در این نمونه برابر بود"
                        }
                        Text(winner, color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        BacktestStrategyRow("سیستم تحلیل تکنیکال", backtest.technical)
                        BacktestStrategyRow("شبکه عصبی", backtest.neural)
                        BacktestStrategyRow("فقط سیگنال‌های مورد توافق", backtest.agreement)
                        Text("بازه کندل‌های دریافت‌شده: ${if (backtest.sampleWindowStart > 0L) WolfHistoricalBacktestEngine.formatTime(backtest.sampleWindowStart) else "—"} تا ${if (backtest.sampleWindowEnd > 0L) WolfHistoricalBacktestEngine.formatTime(backtest.sampleWindowEnd) else "—"}", color = Muted, fontSize = 9.sp)
                        if (backtest.errors.isNotEmpty()) Text("خطاهای دریافت: ${backtest.errors.joinToString(" | ")}", color = Red, fontSize = 9.sp)
                    }
                    Text("بک‌تست پژوهشی است، نه تضمین سود. نمونه‌ها در طول زمان پخش شده‌اند و مقایسه سه روش فقط وقتی معتبر تلقی می‌شود که هر موتور دست‌کم ۳۰ معامله داشته باشد. کارمزد و لغزش با فرض ۰٫۱۰٪ رفت‌وبرگشت روی ارزش اسمی لحاظ شده است؛ فاندینگ و اجرای واقعی سفارش شبیه‌سازی نمی‌شود. شبکه عصبی در این بخش مستقل ارزیابی می‌شود، اما تا عبور از اعتبارسنجی زمانی همچنان از سیگنال زنده کنار گذاشته می‌ماند.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
                }
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("دفترچه سوابق سیگنال‌ها", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("زمان ورود و خروج با ساعت ایران ثبت می‌شود. برخورد به حد سود/ضرر یا پایان اعتبار ۱۵ دقیقه‌ای، سیگنال را به دفترچه منتقل می‌کند و سوابق قابل مشاهده می‌مانند. اطلاعات فقط روی همین گوشی نگهداری می‌شود.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SmallStat("کل سیگنال‌ها", snapshot.total.toString(), Blue)
                        SmallStat("باز", snapshot.active.toString(), Gold)
                        SmallStat("TP1", snapshot.tp1.toString(), Green)
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
                            Text(record.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text(if (record.side == "LONG_BIAS") "LONG" else "SHORT", color = tint, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(date, color = Muted, fontSize = 9.sp)
                            Text(WolfSignalOutcomeJournal.statusLabel(record.status), color = when(record.status) {
                                "TP1" -> Green
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
                            SmallStat("قدرت سیگنال", record.confidence.toString() + "%", tint)
                            SmallStat("تایم‌فریم", record.timeframe, Blue)
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
private fun BacktestStrategyRow(title: String, stats: WolfHistoricalBacktestEngine.StrategyStats) {
    Surface(color = Color(0xFF0D0D0D), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("معامله: ${stats.trades}", color = Muted, fontSize = 9.sp)
                Text("برد: ${stats.wins}", color = Green, fontSize = 9.sp)
                Text("باخت: ${stats.losses}", color = Red, fontSize = 9.sp)
                Text("نرخ برد: ${String.format(Locale.US, "%.1f", stats.winRate)}٪", color = Color.White, fontSize = 9.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("بازده خالص: ${String.format(Locale.US, "%+.2f", stats.netReturnPct)}٪", color = if (stats.netReturnPct >= 0) Green else Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("PF: ${String.format(Locale.US, "%.2f", stats.profitFactor)}", color = Muted, fontSize = 9.sp)
                Text("افت سرمایه: ${String.format(Locale.US, "%.1f", stats.maxDrawdownPct)}٪", color = Red, fontSize = 9.sp)
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
        "LONG_BIAS" -> "LONG"
        "SHORT_BIAS" -> "SHORT"
        else -> "NO TRADE"
    }
    Surface(color = Color(0xFF101010), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.25f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onSelect, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text("جزئیات", color = Blue, fontSize = 10.sp)
                }
                Surface(color = tint.copy(alpha = .15f), shape = RoundedCornerShape(20.dp)) {
                    Text(label, color = tint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("تحلیل چندتایم‌فریمی: " + item.timeframeCoverage + "  |  تایم‌فریم پیشنهادی معامله: " + item.tradeTimeframe + "  |  " + item.signalTier, color = Blue, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SmallStat("قیمت", formatPrice(item.price))
                SmallStat("تغییر ۲۴ساعت", String.format(Locale.US, "%.2f%%", item.change), if (item.change >= 0) Green else Red)
                SmallStat("RSI", String.format(Locale.US, "%.1f", item.rsi))
                SmallStat("امتیاز خام", item.score.toString())
                SmallStat("امتیاز جهت‌دار", item.neuralScore.toString() + "/100")
            }
            val neuralValidated = WolfPretrainedEnsemble.status().validatedForScoring
            val neuralTint = when (item.neuralDirection) {
                "LONG_BIAS" -> Green
                "SHORT_BIAS" -> Red
                else -> Gold
            }
            val neuralOpinion = when {
                !neuralValidated -> "رأی مدل فعلاً غیرفعال"
                item.neuralDirection == "LONG_BIAS" -> "تمایل صعودی • LONG"
                item.neuralDirection == "SHORT_BIAS" -> "تمایل نزولی • SHORT"
                else -> "نظر خنثی • بدون جهت"
            }
            val neuralStrength = maxOf(item.neuralScore, 100 - item.neuralScore)
            Surface(
                color = Color(0xFF17130A),
                shape = RoundedCornerShape(13.dp),
                border = androidx.compose.foundation.BorderStroke(1.4.dp, Gold.copy(alpha = 0.62f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("نظر مستقل شبکه عصبی", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        Surface(
                            color = if (neuralValidated) Color(0xFF12351F) else Color(0xFF382A0B),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                if (neuralValidated) "اعتبارسنجی‌شده" else "پژوهشی • غیرفعال",
                                color = if (neuralValidated) Green else Gold,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("پیش‌بینی مدل", color = Muted, fontSize = 9.sp)
                            Text(neuralOpinion, color = neuralTint, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (neuralValidated && item.neuralDirection != "NEUTRAL") "قدرت جهت مدل" else "قدرت جهت مدل", color = Muted, fontSize = 9.sp)
                            Text(
                                if (neuralValidated && item.neuralDirection != "NEUTRAL") "$neuralStrength٪" else "قابل اعلام نیست",
                                color = neuralTint, fontSize = 15.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        "منبع: " + item.neuralModelSource +
                            (if (neuralValidated && item.neuralDirection != "NEUTRAL") " • امتیاز جهت‌دار: ${item.neuralScore}/100" else ""),
                        color = Muted, fontSize = 9.sp
                    )
                    Divider(color = Gold.copy(alpha = 0.22f))
                    Text(
                        "نظر تحلیل تکنیکال: " + (if (item.technicalDirection == "LONG_BIAS") "صعودی LONG" else if (item.technicalDirection == "SHORT_BIAS") "نزولی SHORT" else "خنثی"),
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "نتیجه مقایسه دو موتور: " + item.agreementStatus,
                        color = if (item.agreementStatus.contains("توافق") || item.agreementStatus.contains("تأیید مستقل")) Green else Gold,
                        fontSize = 9.sp, lineHeight = 14.sp
                    )
                    if (!neuralValidated) {
                        Text(
                            "این خروجی هنوز نظر معتبر مدل محسوب نمی‌شود و در صدور سیگنال زنده دخالت ندارد؛ تا عبور از آزمون زمانی، فقط وضعیت پژوهشی نمایش داده می‌شود.",
                            color = Gold, fontSize = 9.sp, lineHeight = 14.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("قدرت سیگنال", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(String.format(Locale.US, "%d%%", item.confidence), color = tint, fontSize = 16.sp, fontWeight = FontWeight.Black)
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
                        Text("ENTRY: " + formatPrice(item.entry) + "   |   SMART LEVERAGE: " + item.leverage + "x", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallStat("حد ضرر", formatPrice(item.stop), Red)
                            SmallStat("هدف نهایی TP1 • 1R", formatPrice(item.tp1), Green)
                        }
                        Text("حد ضرر و اهداف بر پایه ATR و ساختار حمایت/مقاومت محاسبه می‌شوند؛ اهرم با فاصله استاپ و ریسک نوسان تنظیم می‌شود، نه عدد ثابت.", color = Muted, fontSize = 9.sp)
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
                        Text("جزئیات تحلیل تکنیکال • تایم‌فریم منتخب " + item.tradeTimeframe, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
    val allPrices = candles.flatMap { listOf(it[1], it[2]) } + listOf(signal.entry, signal.stop, signal.tp1)
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
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                PriceLevelRow("ورود مرجع", signal.entry, Blue)
                PriceLevelRow("هدف نهایی TP1", signal.tp1, Green)
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
    val symbol = "XT:" + selectedSymbol.replace("/", "").replace(":USDT", "").replace("_", "") + ".P"
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


/** Shared technical-analysis engine for XT USDT perpetual futures candles. */

internal object MarketAnalysisEngine {
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
        // One realistic final target only: TP1 at 1R. No unreachable TP2/TP3 ladder.
        val tp1 = when { isLong -> price + stopDistance; isShort -> price - stopDistance; else -> price }
        val tp2 = tp1
        val tp3 = tp1
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
            "اجماع متخصصان " + wolf.agreement + "% | قدرت اجماع (نه احتمال برد): " + wolf.strengthIndex +
                "% | ۱۰ تحلیلگر: " + agentSummary + " | ۱۵د: " + a.second + " | ۱س: " + b.second +
                " | ۴س: " + c.second +
                (microStats?.let { " | ۵د: " + it.second } ?: "") +
                (macroStats?.let { " | ۱روز: " + it.second } ?: "") + filterNote,
            price, stop, tp1, tp2, tp3, leverage, atrValue, a.technical, short.takeLast(80), wolf,
            timeframeCoverage = when {
                microStats != null && macroStats != null -> "5m / 15m / 1h / 4h / 1D"
                microStats != null -> "5m / 15m / 1h / 4h"
                macroStats != null -> "15m / 1h / 4h / 1D"
                else -> "15m / 1h / 4h"
            },
            technicalDirection = direction,
            tradeTimeframe = when {
                direction == "NEUTRAL" -> "بدون معامله"
                wolf.direction == "LONG_BIAS" && c.first >= b.first && c.first >= a.first -> "4h"
                wolf.direction == "SHORT_BIAS" && c.first <= b.first && c.first <= a.first -> "4h"
                wolf.direction == "LONG_BIAS" && b.first >= a.first -> "1h"
                wolf.direction == "SHORT_BIAS" && b.first <= a.first -> "1h"
                else -> "15m"
            },
            neuralTechnical = b.technical,
            neuralRsi = b.third,
            neuralCandles = confirm.takeLast(80)
        )
    }

    internal fun analyzeHistoricalWindow(
        symbol: String, short15m: List<DoubleArray>, confirm1h: List<DoubleArray>,
        trend4h: List<DoubleArray>, micro5m: List<DoubleArray>, price: Double
    ): MarketSignal {
        val change = if (short15m.size >= 2 && short15m[short15m.lastIndex - 1][3] != 0.0)
            (short15m.last()[3] / short15m[short15m.lastIndex - 1][3] - 1.0) * 100.0 else 0.0
        return analyzeWithCandles(symbol, price, change, micro5m.last()[4],
            short15m, confirm1h, trend4h, micro5m, null)
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

// XT-only release: background monitoring is intentionally limited to the active app session.
@Composable
private fun HopeHunterHeroArt(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF050505), Color(0xFF241B0B), Color(0xFF080808)),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
        drawCircle(
            color = Color(0xFFFFC52E).copy(alpha = 0.16f),
            radius = size.minDimension * 0.42f,
            center = Offset(size.width * 0.82f, size.height * 0.48f)
        )
        drawCircle(
            color = Color(0xFFFFD45A).copy(alpha = 0.20f),
            radius = size.minDimension * 0.27f,
            center = Offset(size.width * 0.82f, size.height * 0.48f)
        )
        val path = Path().apply {
            moveTo(size.width * 0.58f, size.height * 0.78f)
            lineTo(size.width * 0.70f, size.height * 0.30f)
            lineTo(size.width * 0.82f, size.height * 0.65f)
            lineTo(size.width * 0.93f, size.height * 0.28f)
            lineTo(size.width, size.height * 0.76f)
        }
        drawPath(path, color = Color(0xFFFFD45A).copy(alpha = 0.62f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f))
    }
}
