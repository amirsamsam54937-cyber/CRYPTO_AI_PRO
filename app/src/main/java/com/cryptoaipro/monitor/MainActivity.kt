package com.cryptoaipro.monitor

import android.os.Bundle
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
import androidx.compose.ui.geometry.Offset
import java.time.ZoneId
import java.time.ZonedDateTime
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
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private val Navy = Color(0xFF0A1020)
private val Panel = Color(0xFF151F33)
private val Muted = Color(0xFF9AAAC2)
private val Green = Color(0xFF27D6A0)
private val Red = Color(0xFFFF657A)
private val Blue = Color(0xFFD8B56A)

data class MarketSignal(
    val symbol: String, val price: Double, val change: Double, val volume: Double,
    val direction: String, val score: Int, val rsi: Double, val confidence: Int,
    val reason: String, val entry: Double, val stop: Double, val tp1: Double, val tp2: Double, val tp3: Double, val leverage: Int, val atr: Double,
    val technical: TechnicalSnapshot,
    val candles: List<DoubleArray> = emptyList()
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
    var orderContracts by remember { mutableStateOf("1") }
    var orderLeverage by remember { mutableStateOf("2") }
    var liveTradingArmed by remember { mutableStateOf(false) }
    var pendingTrade by remember { mutableStateOf<MarketSignal?>(null) }
    var tradeBusy by remember { mutableStateOf(false) }
    var tradeMessage by remember { mutableStateOf<String?>(null) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var connectionVerified by remember { mutableStateOf(false) }
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
            rows = if (exchange == "XT") XTMonitor.scan() else BinanceMonitor.scan()
            updated = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        } catch (e: Exception) {
            error = e.message ?: "خطا در دریافت اطلاعات بازار"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(exchange) { while (true) { performScan(); delay(180_000) } }
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
                    Text("تحلیل چندتایم‌فریمی بازار فیوچرز", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { showConnection = !showConnection }) {
                    Text(if (showConnection) "بستن تنظیمات" else "اتصال و کلیدها", color = Blue, fontSize = 12.sp)
                }
                Surface(color = if (liveTradingArmed) Color(0xFF4A241D) else Color(0xFF123B36), shape = RoundedCornerShape(20.dp)) {
                    Text(if (liveTradingArmed) "● سفارش واقعی مسلح" else "● معاملات با تأیید", color = if (liveTradingArmed) Red else Green, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            MarketSessionsPanel()
            Spacer(Modifier.height(10.dp))
            if (showConnection) {
                ConnectionCard(
                    apiKey = apiKeyInput,
                    onApiKeyChange = { apiKeyInput = it },
                    secretKey = secretKeyInput,
                    onSecretKeyChange = { secretKeyInput = it },
                    saved = credentialsSaved,
                    message = credentialMessage,
                    connectionStatus = connectionStatus,
                    onTest = {
                        scope.launch {
                            connectionVerified = false
                            connectionStatus = "در حال بررسی اتصال امن XT..."
                            val result = runCatching {
                                val creds = credentialVault.load() ?: error("ابتدا کلیدها را ذخیره کن")
                                XTOrderExecutor.checkConnection(creds)
                            }
                            connectionVerified = result.isSuccess
                            connectionStatus = result.fold(
                                { "اتصال احراز هویت‌شده XT موفق بود." },
                                { "اتصال ناموفق: " + (it.message ?: "خطای ناشناخته") }
                            )
                        }
                    },
                    onSave = {
                        runCatching {
                            credentialVault.save(ExchangeCredentialVault.Credentials(apiKeyInput.trim(), secretKeyInput))
                        }.onSuccess {
                            credentialsSaved = true
                            connectionVerified = false
                            connectionStatus = "برای امنیت، پس از ذخیره کلید جدید دوباره اتصال را بررسی کن."
                            apiKeyInput = ""
                            secretKeyInput = ""
                            credentialMessage = "کلیدها به‌صورت رمزگذاری‌شده روی همین دستگاه ذخیره شدند."
                        }.onFailure { credentialMessage = it.message ?: "ذخیره‌سازی ناموفق بود." }
                    },
                    onClear = {
                        runCatching { credentialVault.clear() }
                            .onSuccess {
                                credentialsSaved = false
                                connectionVerified = false
                                connectionStatus = null
                                liveTradingArmed = false
                                apiKeyInput = ""
                                secretKeyInput = ""
                                credentialMessage = "کلیدهای ذخیره‌شده از این دستگاه پاک شدند."
                            }
                            .onFailure { credentialMessage = it.message ?: "پاک‌سازی ناموفق بود." }
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = exchange == "XT", onClick = { if (exchange != "XT") exchange = "XT" }, label = { Text("XT Futures") })
                FilterChip(selected = exchange == "BINANCE", onClick = { if (exchange != "BINANCE") exchange = "BINANCE" }, label = { Text("Binance Futures") })
                Spacer(Modifier.weight(1f))
                Text(if (exchange == "XT") "منبع داده: XT" else "منبع داده: Binance", color = Blue, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            TradeControls(
                contracts = orderContracts, onContracts = { orderContracts = it.filter(Char::isDigit).take(8) },
                leverage = orderLeverage, onLeverage = { orderLeverage = it.filter(Char::isDigit).take(2) },
                armed = liveTradingArmed, onArmed = { liveTradingArmed = it },
                message = tradeMessage
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("قراردادهای بررسی‌شده", rows.size.toString(), Modifier.weight(1f))
                MetricCard("تمایل صعودی", rows.count { it.direction == "LONG_BIAS" }.toString(), Modifier.weight(1f), Green)
                MetricCard("تمایل نزولی", rows.count { it.direction == "SHORT_BIAS" }.toString(), Modifier.weight(1f), Red)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("۱۰۰ قرارداد پرحجم " + exchange + " Futures", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
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
                    items(shown, key = { it.symbol }) { item ->
                        SignalCard(item, onTrade = { pendingTrade = item }, canTrade = exchange == "XT" && credentialsSaved && connectionVerified && liveTradingArmed)
                    }
                }
            }
            Text(
                "اسکن خودکار هر ۳ دقیقه • تایم‌فریم ۱۵ دقیقه، ۱ ساعت و ۴ ساعت • برای سفارش واقعی تأیید جداگانه لازم است",
                color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )
        }
        if (pendingTrade != null) {
            val item = pendingTrade!!
            AlertDialog(
                onDismissRequest = { if (!tradeBusy) pendingTrade = null },
                title = { Text("تأیید نهایی سفارش واقعی") },
                text = {
                    Text("صرافی: XT Futures\nنماد: ${item.symbol}\nجهت: ${if (item.direction == "LONG_BIAS") "LONG / BUY" else "SHORT / SELL"}\nتعداد قرارداد: ${orderContracts}\nاهرم: ${orderLeverage}x\nحد ضرر: ${formatPrice(item.stop)}\nحد سود اولیه: ${formatPrice(item.tp1)}\n\nاین سفارش با سرمایه واقعی ارسال می‌شود. قیمت اجرا و لغزش ممکن است با قیمت تحلیل فرق داشته باشد.")
                },
                confirmButton = {
                    Button(enabled = !tradeBusy && orderContracts.toIntOrNull() != null && orderContracts.toInt() > 0 && (orderLeverage.toIntOrNull() ?: 0) in 1..20,
                        onClick = {
                            scope.launch {
                                tradeBusy = true
                                tradeMessage = "در حال ارسال سفارش به XT..."
                                val result = runCatching {
                                    val creds = credentialVault.load() ?: error("کلیدهای XT ذخیره نشده‌اند")
                                    XTOrderExecutor.placeProtectedMarketOrder(
                                        creds, item, orderContracts.toInt(), orderLeverage.toInt()
                                    )
                                }
                                tradeMessage = result.fold(
                                    { "پاسخ XT: " + it },
                                    { "سفارش ارسال نشد: " + (it.message ?: "خطای ناشناخته") }
                                )
                                tradeBusy = false
                                pendingTrade = null
                            }
                        }) { Text(if (tradeBusy) "در حال ارسال..." else "تأیید و ارسال واقعی") }
                },
                dismissButton = { TextButton(enabled = !tradeBusy, onClick = { pendingTrade = null }) { Text("انصراف") } }
            )
        }
    }
}

@Composable
private fun TradeControls(
    contracts: String, onContracts: (String) -> Unit,
    leverage: String, onLeverage: (String) -> Unit,
    armed: Boolean, onArmed: (Boolean) -> Unit,
    message: String?
) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("پنل معاملات XT", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (armed) "ارسال واقعی فعال" else "ارسال واقعی خاموش", color = if (armed) Red else Muted, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = contracts, onValueChange = onContracts, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("تعداد قرارداد") }, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White))
                OutlinedTextField(value = leverage, onValueChange = onLeverage, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("اهرم ۱ تا ۲۰") }, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("اجازه ارسال سفارش واقعی", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Switch(checked = armed, onCheckedChange = onArmed)
            }
            Text("هر سیگنال فقط با دکمه خودش و تأیید نهایی ارسال می‌شود. حد ضرر و حد سود به سفارش پیوست می‌شوند؛ تعداد قرارداد را خودت تعیین می‌کنی.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            message?.let { Text(it, color = if (it.startsWith("سفارش ارسال نشد")) Red else Green, fontSize = 10.sp) }
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
    connectionStatus: String?,
    onTest: () -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = saved, modifier = Modifier.fillMaxWidth()) { Text("بررسی اتصال واقعی به حساب XT") }
            }
            connectionStatus?.let { Text(it, color = if (it.startsWith("اتصال ناموفق")) Red else Green, fontSize = 10.sp) }
            Text(
                "کلیدها با Android Keystore و AES-GCM رمزگذاری می‌شوند. برای ارسال سفارش، ابتدا اتصال حساب را بررسی کن؛ سفارش فقط با تأیید جداگانه تو ارسال می‌شود.",
                color = Muted, fontSize = 10.sp, lineHeight = 15.sp
            )
            Text(
                "امنیت: مجوز برداشت وجه را هرگز فعال نکن. کلیدهای قبلاً افشاشده را باطل کن و کلید جدید را فقط با مجوز خواندن و معامله بساز.",
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
private fun SignalCard(item: MarketSignal, onTrade: () -> Unit, canTrade: Boolean) {
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
            if (item.direction != "NEUTRAL") {
                Button(onClick = onTrade, enabled = canTrade, modifier = Modifier.fillMaxWidth()) {
                    Text(if (canTrade) "معامله واقعی این سیگنال در XT" else "برای معامله: XT را انتخاب کن، کلید را ذخیره و ارسال واقعی را فعال کن")
                }
            }
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
        }.sortedByDescending { it.third }.take(100)
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
private object XTOrderExecutor {
    private const val BASE = "https://fapi.xt.com"

    private fun signature(secret: String, key: String, timestamp: String, path: String, body: String): String {
        val header = "validate-appkey=$key&validate-timestamp=$timestamp"
        val original = header + "#" + path + "#" + body
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(original.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun post(creds: ExchangeCredentialVault.Credentials, path: String, body: JSONObject): JSONObject {
        val bodyText = body.toString()
        val timestamp = System.currentTimeMillis().toString()
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 18000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("validate-appkey", creds.apiKey)
            setRequestProperty("validate-timestamp", timestamp)
            setRequestProperty("validate-signature", signature(creds.secretKey, creds.apiKey, timestamp, path, bodyText))
        }
        try {
            c.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("XT HTTP $code: $text")
            val json = JSONObject(text)
            val rc = json.optString("returnCode", "0")
            if (rc != "0" && rc != "200") {
                val detail = json.optJSONObject("error")?.optString("msg")
                    ?: json.optString("msgInfo", "XT درخواست را رد کرد")
                throw IllegalStateException("XT: $detail")
            }
            return json
        } finally { c.disconnect() }
    }

    suspend fun checkConnection(creds: ExchangeCredentialVault.Credentials) = withContext(Dispatchers.IO) {
        val path = "/future/user/v1/account/info"
        val timestamp = System.currentTimeMillis().toString()
        val header = "validate-appkey=${creds.apiKey}&validate-timestamp=$timestamp"
        val original = header + "#" + path
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(creds.secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal(original.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12000; readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("validate-algorithms", "HmacSHA256")
            setRequestProperty("validate-appkey", creds.apiKey)
            setRequestProperty("validate-recvwindow", "5000")
            setRequestProperty("validate-timestamp", timestamp)
            setRequestProperty("validate-signature", sig)
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val raw = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("XT HTTP $code: $raw")
            val json = JSONObject(raw)
            if (json.optString("returnCode", "0") !in setOf("0", "200")) throw IllegalStateException(json.optString("msgInfo", "احراز هویت رد شد"))
        } finally { c.disconnect() }
    }

    suspend fun placeProtectedMarketOrder(
        creds: ExchangeCredentialVault.Credentials, signal: MarketSignal, contracts: Int, leverage: Int
    ): String = withContext(Dispatchers.IO) {
        require(contracts in 1..100000000) { "تعداد قرارداد نامعتبر است" }
        require(leverage in 1..20) { "اهرم باید بین ۱ تا ۲۰ باشد" }
        require(signal.direction == "LONG_BIAS" || signal.direction == "SHORT_BIAS") { "سیگنال خنثی قابل معامله نیست" }
        val symbol = signal.symbol.lowercase(Locale.US).let {
            if (it.endsWith("_usdt")) it else if (it.endsWith("usdt")) it.dropLast(4) + "_usdt" else it
        }
        val positionSide = if (signal.direction == "LONG_BIAS") "LONG" else "SHORT"
        val side = if (positionSide == "LONG") "BUY" else "SELL"
        post(creds, "/future/user/v1/position/adjust-leverage",
            JSONObject().put("symbol", symbol).put("positionSide", positionSide).put("leverage", leverage))
        val order = JSONObject()
            .put("clientOrderId", "CAP-" + UUID.randomUUID().toString().replace("-", "").take(24))
            .put("symbol", symbol)
            .put("orderSide", side)
            .put("orderType", "MARKET")
            .put("origQty", contracts)
            .put("positionSide", positionSide)
            .put("triggerStopPrice", signal.stop)
            .put("triggerProfitPrice", signal.tp1)
        val response = post(creds, "/future/trade/v1/order/create", order)
        "درخواست سفارش به XT پذیرفته شد. پاسخ: " + response.optString("msgInfo", "success")
    }
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
            .take(100)
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
            price, stop, tp1, tp2, tp3, leverage, atrValue, a.technical, short.takeLast(80)
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
