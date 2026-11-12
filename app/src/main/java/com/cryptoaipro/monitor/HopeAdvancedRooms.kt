package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

private val RG = Color(0xFFFFD45A)
private val RI = Color(0xFF070807)
private val RP = Color(0xFF121411)
private val RM = Color(0xFFB8B0A0)
private val RGreen = Color(0xFF39D98A)
private val RRed = Color(0xFFFF6470)

@Composable
internal fun HopeAdvancedRoomsScreen(context: Context, signals: List<MarketSignal>,
    scenarioScorecard: HopeScenarioScorecard?, onRescan: () -> Unit) {
    val rooms = listOf("مغز فرمانده", "تحلیل چارت", "سناریو", "کلان بازار", "اخبار و نهنگ", "یادگیری", "ابر HOPE")
    var room by remember { mutableStateOf(rooms.first()) }
    val selected = signals.firstOrNull { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" } ?: signals.firstOrNull()
    val modelStatus = WolfPretrainedEnsemble.status()
    var news by remember { mutableStateOf<List<MarketNewsItem>>(emptyList()) }
    var whale by remember { mutableStateOf<WhaleFlowReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    LaunchedEffect(room, selected?.symbol) {
        if (room == "اخبار و نهنگ") {
            busy = true; error = null
            try {
                val pair = coroutineScope {
                    val n = async { runCatching { WolfFlowNewsRepository.news() }.getOrDefault(emptyList()) }
                    val w = async { selected?.symbol?.let { runCatching { WolfFlowNewsRepository.whale(it) }.getOrNull() } }
                    n.await() to w.await()
                }
                news = pair.first; whale = pair.second
                if (news.isEmpty() && whale == null) error = "در این نوبت داده‌ای از منابع عمومی دریافت نشد."
            } catch (e: Exception) { error = e.message ?: "دریافت اطلاعات ناموفق بود." }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(RI).padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("HOPE HUNTER • ADVANCED ROOMS", color = RG, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Text("آزمایشگاه تخصصی و اتاق‌های فرماندهی", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("صفحه‌های مستقل؛ تصمیم‌گیری مشترک زیر نظر فرمانده کل.", color = RM, fontSize = 9.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rooms.forEach { item ->
                FilterChip(selected = room == item, onClick = { room = item }, label = { Text(item, fontSize = 9.sp, maxLines = 1) })
            }
        }
        when (room) {
            "مغز فرمانده" -> BrainRoom(selected, modelStatus)
            "تحلیل چارت" -> TechnicalRoom(selected, onRescan)
            "سناریو" -> ScenarioRoom(selected, scenarioScorecard)
            "کلان بازار" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                HopeMacroMarketDashboard()
                RoomNote("شفافیت شاخص‌ها", "TOTAL از منبع عمومی دریافت می‌شود. TOTAL2 و TOTAL3 در این نسخه برآورد محاسباتی‌اند، نه کندل رسمی TradingView.")
            }
            "اخبار و نهنگ" -> IntelRoom(selected, news, whale, busy, error, uri) {
                scope.launch {
                    busy = true; error = null
                    val n = runCatching { WolfFlowNewsRepository.news() }.getOrDefault(emptyList())
                    val w = selected?.symbol?.let { runCatching { WolfFlowNewsRepository.whale(it) }.getOrNull() }
                    news = n; whale = w
                    if (n.isEmpty() && w == null) error = "منابع عمومی داده‌ای برنگرداندند."
                    busy = false
                }
            }
            "یادگیری" -> LearningRoom(context, modelStatus)
            else -> CloudRoom()
        }
    }
}

@Composable
private fun BrainRoom(signal: MarketSignal?, status: WolfPretrainedEnsemble.Status) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        RoomCard("فرمانده کل • HOPE COMMANDER") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("مغز تصمیم‌گیر مرکزی", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("هماهنگ‌کنندهٔ تحلیل تکنیکال، مدل‌های عصبی، جریان سرمایه و سناریوها", color = RM, fontSize = 9.sp)
                    Text(signal?.agreementStatus ?: "در انتظار اسکن واقعی بازار", color = RG, fontSize = 9.sp)
                }
                HopeCommanderBrain(Modifier.size(108.dp))
            }
        }
        RoomCard("ده عضو شبکهٔ عصبی • رأی مستقل") {
            Text("هر خانه خروجی یکی از اعضای مدل را نشان می‌دهد؛ نام عضو به‌تنهایی به معنی تخصص مستقل در یک حوزه نیست.", color = RM, fontSize = 9.sp)
            val votes = signal?.neuralMemberVotes.orEmpty()
            for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (col in 0..4) {
                    val i = row * 5 + col
                    val vote = votes.getOrNull(i)
                    val label = when {
                        vote == null -> "بدون رأی"
                        vote.contains("LONG", true) || vote.contains("صعود", true) -> "صعودی"
                        vote.contains("SHORT", true) || vote.contains("نزول", true) -> "نزولی"
                        else -> "خنثی"
                    }
                    Column(Modifier.weight(1f).background(Color(0xFF090B09), RoundedCornerShape(8.dp))
                        .border(1.dp, RG.copy(alpha = .28f), RoundedCornerShape(8.dp)).padding(vertical = 8.dp, horizontal = 1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("N${i+1}", color = RG, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(label, color = if (label == "صعودی") RGreen else if (label == "نزولی") RRed else RM, fontSize = 8.sp)
                    }
                }
            }
            Text("نظر مستقل: ${signal?.neuralDirection ?: "بدون داده"} • امتیاز ${signal?.neuralScore ?: 50}/100", color = Color.White, fontSize = 10.sp)
            Text("منبع: ${signal?.neuralModelSource ?: "در انتظار اسکن"}", color = RM, fontSize = 9.sp)
            Text(if (status.validatedForScoring) "مدل از دروازهٔ اعتبارسنجی عبور کرده است." else "مدل هنوز مجوز اثرگذاری بر سیگنال نهایی ندارد؛ نظر پژوهشی آن نمایش داده می‌شود.",
                color = if (status.validatedForScoring) RGreen else RG, fontSize = 9.sp)
        }
        RoomCard("مقایسه و داوری") {
            MetricLine("تکنیکال", sideFa(signal?.technicalDirection ?: "NEUTRAL"))
            MetricLine("شبکه عصبی", sideFa(signal?.neuralDirection ?: "NEUTRAL"))
            MetricLine("فرمانده", sideFa(signal?.direction ?: "NEUTRAL"))
            MetricLine("اجماع متخصصان", signal?.consensus?.agreement?.let { "$it٪" } ?: "ناموجود")
            Text(signal?.agreementStatus ?: "گزارش داوری پس از اسکن ثبت می‌شود.", color = RM, fontSize = 9.sp)
        }
    }
}

@Composable
private fun TechnicalRoom(s: MarketSignal?, onRescan: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        RoomCard("تحلیل چارت واقعی") {
            if (s == null) {
                Text("داده‌ای برای تحلیل وجود ندارد؛ ابتدا اسکن بازار را اجرا کن.", color = RM, fontSize = 10.sp)
                Button(onClick = onRescan) { Text("اسکن بازار") }
            } else {
                Text("${s.symbol} • ${s.tradeTimeframe} • ${s.candles.size} کندل", color = RG, fontWeight = FontWeight.Bold)
                MetricLine("قیمت", price(s.price)); MetricLine("جهت تکنیکال", sideFa(s.technicalDirection))
                MetricLine("تصمیم فرمانده", sideFa(s.direction)); MetricLine("ورود", price(s.entry))
                MetricLine("حد ضرر", price(s.stop)); MetricLine("TP1", price(s.tp1))
                MetricLine("حمایت / کف", price(s.technical.support)); MetricLine("مقاومت / سقف", price(s.technical.resistance))
            }
        }
        s?.let {
            RoomCard("ساختار بازار و الگوها") {
                MetricLine("رژیم", it.technical.regime); MetricLine("ساختار", it.technical.marketStructure)
                MetricLine("الگوی شمعی", it.technical.candlePattern); MetricLine("شکار نقدینگی", it.technical.liquiditySweep)
                MetricLine("FVG", it.technical.fairValueGap); MetricLine("واگرایی RSI", it.technical.rsiDivergence)
                MetricLine("Supertrend", it.technical.supertrendDirection)
            }
            RoomCard("اندیکاتورها و مومنتوم") {
                MetricLine("RSI", fmt(it.rsi)); MetricLine("ADX", fmt(it.technical.adx))
                MetricLine("ATR٪", fmt(it.technical.atrPercent)); MetricLine("حجم نسبی", fmt(it.technical.relativeVolume))
                MetricLine("VWAP", price(it.technical.vwap))
                MetricLine("MACD / Signal", "${fmt(it.technical.macdLine)} / ${fmt(it.technical.macdSignalLine)}")
                MetricLine("CCI / MFI", "${fmt(it.technical.cci)} / ${fmt(it.technical.mfi)}")
            }
        }
        RoomNote("محدودیت", "سطوح، الگوها و نقاط ورود/خروج برآورد تحلیلی‌اند؛ تضمین برگشت یا ادامهٔ قیمت نیستند.")
    }
}

@Composable
private fun ScenarioRoom(s: MarketSignal?, scorecard: HopeScenarioScorecard?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (s == null) RoomNote("در انتظار داده", "برای شبیه‌سازی مسیرها، اسکن معتبر با حداقل ۸۰ کندل لازم است.")
        else {
            val result = scorecard?.takeIf { it.forecast?.symbol == s.symbol }
                ?: HopeScenarioLab.forecast(s)?.let { HopeScenarioScorecard(it, 0, 0, 0.0, 0.0) }
            HopeScenarioLabCard(result)
        }
        RoomNote("تفسیر", "فراوانی مسیرهای بازنمونه‌گیری‌شده، احتمال کالیبره‌شدهٔ بازار نیست. کارنامهٔ خارج از نمونه برای سنجش کیفیت لازم است.")
    }
}

@Composable
private fun IntelRoom(s: MarketSignal?, news: List<MarketNewsItem>, whale: WhaleFlowReport?,
    busy: Boolean, error: String?, uri: androidx.compose.ui.platform.UriHandler, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        RoomCard("جریان سرمایه • ${s?.symbol ?: "نماد انتخاب نشده"}") {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = RG)
            if (whale == null) Text("دادهٔ معاملات بزرگ برای این نماد در دسترس نیست.", color = RM, fontSize = 9.sp)
            else {
                MetricLine("خرید بزرگ برآوردی", usd(whale.buyUsd)); MetricLine("فروش بزرگ برآوردی", usd(whale.sellUsd))
                MetricLine("تعداد خرید / فروش", "${whale.largeBuys} / ${whale.largeSells}")
                MetricLine("عدم‌تعادل معاملات", fmt(whale.imbalance)); MetricLine("Open Interest", usd(whale.openInterest))
                MetricLine("Funding", fmt(whale.funding)); MetricLine("عدم‌تعادل دفتر سفارش", fmt(whale.bookImbalance))
                Text(if (whale.bookFresh) "دفتر سفارش تازه" else "دفتر سفارش کهنه یا ناموجود", color = if (whale.bookFresh) RGreen else RG, fontSize = 9.sp)
                Text(whale.summary, color = RM, fontSize = 9.sp)
            }
            Button(onClick = onRefresh, enabled = !busy) { Text("به‌روزرسانی") }
            error?.let { Text(it, color = RG, fontSize = 9.sp) }
            Text("معاملات بزرگ هویت مالک یا فعالیت یک نهنگ مشخص را اثبات نمی‌کنند؛ این‌ها برآورد جریان سفارش‌اند.", color = RM, fontSize = 8.sp)
        }
        RoomCard("اخبار بازار") {
            if (news.isEmpty()) Text("خبر تازه‌ای از منابع عمومی دریافت نشده است.", color = RM, fontSize = 9.sp)
            news.take(12).forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text("${item.source} • ${item.date}", color = RG, fontSize = 8.sp)
                    if (item.description.isNotBlank()) Text(item.description.take(220), color = RM, fontSize = 8.sp)
                    TextButton(onClick = { runCatching { uri.openUri(item.link) } }, contentPadding = PaddingValues(0.dp)) {
                        Text("باز کردن منبع خبر ←", color = RG, fontSize = 8.sp)
                    }
                }
                Divider(color = Color(0xFF393124))
            }
        }
    }
}

@Composable
private fun LearningRoom(context: Context, s: WolfPretrainedEnsemble.Status) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        HopeLivingBrainPanel(context)
        RoomCard("اعتبارسنجی مدل‌ها") {
            MetricLine("مدل بارگذاری‌شده", if (s.loaded) "بله" else "خیر")
            MetricLine("اعضای مدل", "${s.members}/10"); MetricLine("نمونه‌های آموزش", s.samples.toString())
            MetricLine("ردیف‌های دیتاست", s.corpusRows.toString())
            MetricLine("دقت آزمون", fmt(s.testAccuracy * 100) + "%")
            MetricLine("دقت متوازن", fmt(s.balancedAccuracy * 100) + "%")
            MetricLine("ROC AUC", fmt(s.rocAuc)); MetricLine("Brier", fmt(s.brierScore))
            MetricLine("مجوز اثرگذاری", if (s.validatedForScoring) "تأییدشده" else "تأییدنشده")
            Text("خالی بودن آمار یا مدل بارگذاری‌نشده به معنی آموزش موفق نیست. مدل جدید باید پیش از انتشار اعتبارسنجی شود.", color = RM, fontSize = 8.sp)
        }
        RoomNote("یادگیری پیوسته", "چرخهٔ نظارت و حافظهٔ نتایج با آموزش مجدد وزن‌های شبکه متفاوت است. بازآموزی و انتشار مدل جدید باید جداگانه و پس از آزمون انجام شود.")
    }
}

@Composable
private fun CloudRoom() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        RoomCard("زیرساخت ابری HOPE") {
            Text("وضعیت فعلی: سرور ابری عملیاتی به برنامه متصل نیست.", color = RG, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            MetricLine("پردازش ابری", "راه‌اندازی نشده"); MetricLine("پایگاه داده", "اتصال تأیید نشده")
            MetricLine("همگام‌سازی مدل‌ها", "فعال نیست"); MetricLine("آموزش روی سرور", "فعال نیست")
            Text("سورس اولیهٔ سرویس ابری وجود دارد، اما تا استقرار، تنظیم endpoint امن و تست اتصال، نباید آن را فعال اعلام کرد.", color = RM, fontSize = 9.sp)
        }
        RoomNote("معماری هدف", "دریافت داده، ذخیرهٔ تاریخچه، صف آموزش، نسخه‌بندی مدل و همگام‌سازی امن اپلیکیشن؛ بدون ارسال سفارش معاملاتی.")
    }
}

@Composable
private fun RoomCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = RP, shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RG.copy(alpha = .25f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = RG, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
@Composable private fun RoomNote(title: String, body: String) = RoomCard(title) {
    Text(body, color = RM, fontSize = 9.sp, lineHeight = 15.sp)
}
@Composable private fun MetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = RM, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}
private fun sideFa(v: String) = when (v) { "LONG_BIAS" -> "صعودی"; "SHORT_BIAS" -> "نزولی"; else -> "خنثی / بدون معامله" }
private fun fmt(v: Double) = if (v.isFinite()) String.format(Locale.US, "%.2f", v) else "—"
private fun price(v: Double) = if (v.isFinite() && v > 0) String.format(Locale.US, "%.6f", v) else "—"
private fun usd(v: Double) = if (v.isFinite()) String.format(Locale.US, "$%,.0f", v) else "—"
