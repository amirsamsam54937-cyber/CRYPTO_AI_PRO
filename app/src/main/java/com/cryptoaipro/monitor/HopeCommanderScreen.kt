package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private val HGold = Color(0xFFD5DCE5)
private val HGoldBright = Color(0xFFF4F6FA)
private val HInk = Color(0xFF050606)
private val HPanel = Color(0xFF101211)
private val HPanel2 = Color(0xFF171A18)
private val HMint = Color(0xFF25D978)
private val HRed = Color(0xFFFF5361)
private val HMuted = Color(0xFFB8B0A0)
private val HBlue = Color(0xFFFF5361)
private val HPurple = Color(0xFFC7CDD4)

@Composable
fun HopeCommanderBrain(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hopeBrainPulse")
    val pulse by transition.animateFloat(.78f, 1.08f,
        infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * .34f
        drawCircle(Color(0x443A9B63), r * 1.65f * pulse, Offset(cx, cy))
        drawCircle(HGold.copy(alpha = .9f), r * 1.28f, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        drawCircle(HGold.copy(alpha = .42f), r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        for (i in 0 until 24) {
            val a = Math.PI * 2 * i / 24
            val x = cx + kotlin.math.cos(a).toFloat() * r
            val y = cy + kotlin.math.sin(a).toFloat() * r
            val x2 = cx + kotlin.math.cos(a + 1.05).toFloat() * r * .82f
            val y2 = cy + kotlin.math.sin(a + 1.05).toFloat() * r * .82f
            drawLine(HGold.copy(alpha = .42f), Offset(x, y), Offset(x2, y2), 1.dp.toPx())
            drawCircle(HMint.copy(alpha = .9f), 2.5.dp.toPx() * pulse, Offset(x, y))
        }
        // Neural hemispheres: a native vector treatment, not a wolf or imported mascot.
        for (side in listOf(-1f, 1f)) {
            val p = Path().apply {
                moveTo(cx, cy - r * .70f)
                cubicTo(cx + side*r*.68f, cy-r*.85f, cx+side*r*.82f, cy+r*.42f, cx, cy+r*.65f)
            }
            drawPath(p, HGold, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            for (j in 0..4) {
                val yy = cy - r*.42f + j*r*.20f
                drawLine(HGold.copy(alpha=.7f), Offset(cx, yy), Offset(cx+side*r*(.18f+j*.075f), yy-r*.06f), 1.dp.toPx())
            }
        }
        drawCircle(Color(0xFFFFE6A8), 4.dp.toPx()*pulse, Offset(cx, cy))
    }
}

@Composable
internal fun HopeCommanderScreen(
    signals: List<MarketSignal>,
    onOpenMarket: () -> Unit = {},
    onOpenChart: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onToggleBackground: () -> Unit = {},
    backgroundMonitoring: Boolean = false,
    paperSnapshot: HopePaperTradingEngine.Snapshot? = null,
    scenarioScorecard: HopeScenarioScorecard? = null,
    activeOutcomeRecords: List<WolfSignalOutcomeJournal.Record> = emptyList(),
    isLoading: Boolean = false,
    onRescan: () -> Unit = {}
) {
    val context = LocalContext.current
    var macro by remember { mutableStateOf<MacroMarketContext?>(null) }
    var macroLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        macro = HopeMacroMarketEngine.fetch()
        macroLoading = false
    }
    val best = signals.firstOrNull { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
        ?: signals.firstOrNull()
    val validated = best?.neuralModelSource?.contains("اعتبارسنجی‌شده") == true
    val votes = best?.neuralMemberVotes.orEmpty()
    val neuralActive = validated && votes.size == 10
    val longCount = signals.count { it.direction == "LONG_BIAS" }
    val shortCount = signals.count { it.direction == "SHORT_BIAS" }
    val agreement = best?.consensus?.agreement
    val rr = best?.let {
        val risk = kotlin.math.abs(it.entry-it.stop)
        val reward = kotlin.math.abs(it.tp1-it.entry)
        if (risk > 0.0 && risk.isFinite() && reward.isFinite()) reward/risk else null
    }
    val transition = rememberInfiniteTransition(label = "statusGlow")
    val glow by transition.animateFloat(.45f, .95f,
        infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow")

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(HInk),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Color(0xFF30230B), HInk)), CircleShape)
                    .border(1.dp, HGold, CircleShape), contentAlignment = Alignment.Center) {
                    HopeCommanderBrain(Modifier.size(38.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("ZORO", color = HGoldBright, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                    Text("مرکز هوشمند تحلیل و سیگنال بازار", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "تنظیمات", tint = HGold) }
                Icon(Icons.Outlined.Notifications, contentDescription = "اعلان‌ها", tint = HGoldBright, modifier = Modifier.padding(8.dp))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HPanelCard(Modifier.weight(1.08f), "نمای کلان بازار") {
                    MacroLine("TOTAL", macro?.let { "$" + compactUsd(it.totalUsd) } ?: "—", macro?.total24hPct)
                    MacroLine("TOTAL2", macro?.let { "$" + compactUsd(it.total2Usd) } ?: "—", macro?.total2_24hPct)
                    MacroLine("TOTAL3", macro?.let { "$" + compactUsd(it.total3Usd) } ?: "—", macro?.total3_24hPct)
                    MacroLine("BTC.D", macro?.let { fmt(it.btcDominance) + "%" } ?: "—", null)
                    MacroLine("ETH.D", macro?.let { fmt(it.ethDominance) + "%" } ?: "—", null)
                    MacroLine("USDT.D", macro?.let { fmt(it.usdtDominance) + "%" } ?: "—", null)
                    Text(if (macroLoading) "در حال دریافت داده…" else macro?.sourceLabel ?: "دادهٔ کلان در دسترس نیست",
                        color = HMuted, fontSize = 8.sp)
                }
                HPanelCard(Modifier.weight(.92f), "وضعیت سیستم") {
                    StatusLine("فرمانده مرکزی", "فعال", true)
                    StatusLine("متخصصان عصبی", if (neuralActive) "۱۰/۱۰ معتبر" else "۱۰ نظر / اعتبارسنجی نشده", neuralActive)
                    val onlineSamples = WolfDeepNeuralNetwork.reviewedSamples(context.getSharedPreferences("wolf_signal_outcomes", Context.MODE_PRIVATE))
                    StatusLine("یادگیری زنده", "$onlineSamples نتیجهٔ بررسی‌شده", onlineSamples >= 30)
                    StatusLine("تحلیل تکنیکال", if (signals.isNotEmpty()) "فعال" else "در انتظار داده", signals.isNotEmpty())
                    StatusLine("پایش پس‌زمینه", if (backgroundMonitoring) "روشن" else "خاموش", backgroundMonitoring)
                    StatusLine("دادهٔ کلان", if (macro?.fresh == true) "تازه" else "ناموجود/قدیمی", macro?.fresh == true)
                    StatusLine("سیگنال معتبر", if (best?.direction == "LONG_BIAS" || best?.direction == "SHORT_BIAS") "موجود" else "ندارد",
                        best?.direction == "LONG_BIAS" || best?.direction == "SHORT_BIAS")
                }
            }
        }
        item {
            HPanelCard(Modifier.fillMaxWidth(), "") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("فرمانده مرکزی سیستم", color = HMuted, fontSize = 10.sp)
                        Text("HOPE COMMANDER", color = HGoldBright, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text("مغز یکپارچهٔ تحلیل و تصمیم‌گیری", color = Color.White, fontSize = 10.sp)
                        Surface(color = Color(0xFF092315), shape = RoundedCornerShape(30.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, HMint.copy(alpha = glow))) {
                            Text(if (signals.isEmpty()) "در انتظار دریافت داده…" else "در حال پایش و داوری شواهد",
                                color = HMint, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 7.dp))
                        }
                    }
                    HopeCommanderBrain(Modifier.size(116.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MetricTile("نمادهای اسکن‌شده", signals.size.toString(), HGold, Modifier.weight(1f))
                MetricTile("تمایل صعودی", longCount.toString(), HMint, Modifier.weight(1f))
                MetricTile("تمایل نزولی", shortCount.toString(), HRed, Modifier.weight(1f))
            }
        }
        item {
            HPanelCard(Modifier.fillMaxWidth(), "ده متخصص هوش مصنوعی") {
                val names = listOf("ساختار بازار","مومنتوم","حجم","نوسان","نقدینگی","جریان سفارش","مشتقات","اخبار","رژیم بازار","پیش‌بینی")
                for (row in 0..1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (col in 0..4) {
                            val index = row*5+col
                            val vote = votes.getOrNull(index)
                            val active = vote != null && vote.isNotBlank() && vote != "NEUTRAL"
                            Column(Modifier.weight(1f).height(82.dp)
                                .background(Color(0xFF090B0A), RoundedCornerShape(9.dp))
                                .border(1.dp, if (neuralActive && active) HGold else Color(0xFF51401F), RoundedCornerShape(9.dp))
                                .padding(horizontal = 2.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(listOf("⌁","ϟ","▤","∿","◉","⇄","◇","▧","⟳","◎")[index],
                                    color = listOf(HGold, HGoldBright, HGold, HGoldBright, HBlue, HBlue, HBlue, HGold, HBlue, HPurple)[index],
                                    fontSize = 18.sp, fontWeight = FontWeight.Black)
                                Text(names[index], color = Color.White, fontSize = 7.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 9.sp)
                                Text(vote?.let { shortVote(it) } ?: "بدون رأی",
                                    color = if (neuralActive && active) HMint else HMuted, fontSize = 7.sp, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                    if (row == 0) Spacer(Modifier.height(5.dp))
                }
                Text(if (neuralActive) "خروجی مدل‌های اعتبارسنجی‌شده در داوری فرمانده لحاظ می‌شود"
                    else "نظر مدل‌ها صرفاً پژوهشی است؛ تا عبور از آزمون مستقل، وزن تصمیم‌گیری آن‌ها صفر است",
                    color = HMuted, fontSize = 8.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HPanelCard(Modifier.weight(1f), "تحلیل چندلایه") {
                    ScoreRing(agreement ?: 0, "اجماع")
                    StatusLine("تکنیکال", best?.technicalDirection?.let { sideFa(it) } ?: "بدون داده", best != null)
                    StatusLine("نظر مستقل شبکه", best?.neuralDirection?.let { sideFa(it) + " • ${best.neuralScore}%" } ?: "بدون داده", neuralActive)
                    StatusLine("فرمانده", best?.direction?.let { sideFa(it) } ?: "انتظار", best?.direction != "NEUTRAL")
                }
                HPanelCard(Modifier.weight(1f), "انتخاب استراتژی") {
                    Text(best?.technical?.regime ?: "در انتظار تشخیص رژیم", color = HGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(best?.technical?.marketStructure ?: "ساختار بازار پس از دریافت کندل مشخص می‌شود",
                        color = Color.White, fontSize = 9.sp, maxLines = 3)
                    Text("تایم‌فریم: " + (best?.tradeTimeframe ?: "—"), color = HMuted, fontSize = 9.sp)
                    Text("استراتژی برنده فقط پس از بک‌تست مستقل انتخاب می‌شود", color = HMuted, fontSize = 8.sp)
                    OutlinedButton(onClick = onOpenJournal, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("نتایج و ارزیابی", fontSize = 9.sp, color = HGold)
                    }
                }
                HPanelCard(Modifier.weight(1f), "سیگنال جاری") {
                    val isLong = best?.direction == "LONG_BIAS"
                    val isShort = best?.direction == "SHORT_BIAS"
                    Text(if (isLong) "↗ LONG" else if (isShort) "↘ SHORT" else "NO TRADE",
                        color = if (isLong) HMint else if (isShort) HRed else HGold, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Text("اعتماد: " + (best?.confidence?.toString() ?: "—") + "%", color = Color.White, fontSize = 10.sp)
                    Text("R:R " + (rr?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"),
                        color = HGold, fontSize = 10.sp)
                    Text(best?.signalTier ?: "بدون رتبه", color = HMuted, fontSize = 8.sp)
                }
            }
        }
        item {
            HPanelCard(Modifier.fillMaxWidth(), "نمودار تحلیل حرفه‌ای") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(best?.symbol ?: "در انتظار نماد", color = HGold, fontSize = 13.sp, fontWeight = FontWeight.Black)
                        Text(best?.timeframeCoverage ?: "دادهٔ چندتایم‌فریمی پس از اسکن نمایش داده می‌شود", color = HMuted, fontSize = 8.sp)
                    }
                    TextButton(onClick = onOpenChart) { Text("ابزارهای کامل چارت ←", color = HGold, fontSize = 9.sp) }
                }
                if (best != null) CommanderCandlestickChart(best)
                else Box(Modifier.fillMaxWidth().height(145.dp), contentAlignment = Alignment.Center) {
                    Text("پس از دریافت کندل‌های واقعی، نمودار اینجا نمایش داده می‌شود", color = HMuted, fontSize = 10.sp)
                }
                best?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        PriceTag("ENTRY", it.entry, HGold, Modifier.weight(1f))
                        PriceTag("SL", it.stop, HRed, Modifier.weight(1f))
                        PriceTag("TP1", it.tp1, HMint, Modifier.weight(1f))
                        PriceTag("TP2", it.tp2, HMint, Modifier.weight(1f))
                        PriceTag("TP3", it.tp3, HMint, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HPanelCard(Modifier.weight(1f), "پایش پس‌زمینه") {
                    Text(if (backgroundMonitoring) "● فعال" else "● متوقف", color = if (backgroundMonitoring) HMint else HGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("اسکن بازار، خبر و جریان سفارش در سرویس پس‌زمینه", color = HMuted, fontSize = 8.sp)
                    TextButton(onClick = onToggleBackground, contentPadding = PaddingValues(0.dp)) {
                        Text(if (backgroundMonitoring) "توقف پایش" else "شروع پایش", color = HGold, fontSize = 9.sp)
                    }
                }
                HPanelCard(Modifier.weight(1f), "آخرین تحلیل") {
                    Text(best?.reason ?: "هنوز تحلیلی ثبت نشده", color = Color.White, fontSize = 9.sp, maxLines = 4)
                    Text(best?.agreementStatus ?: "فرمانده منتظر دادهٔ معتبر است", color = HMuted, fontSize = 8.sp, maxLines = 3)
                }
                HPanelCard(Modifier.weight(1f), "چرخهٔ یادگیری") {
                    Text(if (validated) "مدل معتبر" else "در انتظار اعتبارسنجی", color = if (validated) HMint else HGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("به‌روزرسانی مدل فقط پس از ارزیابی خارج از نمونه", color = HMuted, fontSize = 8.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenMarket, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF342607))) { Text("بازارها", color = HGoldBright) }
                OutlinedButton(onClick = onOpenJournal, modifier = Modifier.weight(1f)) { Text("دفترچه و نتایج", color = HGold) }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("تنظیمات", color = HGold) }
            }
        }
        item {
            paperSnapshot?.let { HopePaperTradingDashboard(it) }
        }
        item {
            HopeScenarioLabCard(scenarioScorecard)
        }
        item {
            ActiveCommandSignals(records = activeOutcomeRecords, onOpenJournal = onOpenJournal)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRescan, enabled = !isLoading, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF342607))) {
                    Text(if (isLoading) "در حال اسکن…" else "اسکن مجدد بازار", color = HGoldBright)
                }
            }
        }
        item {
            Text("HOPE HUNTER • تحلیل و سیگنال‌دهی فقط؛ بدون ارسال سفارش واقعی به صرافی",
                color = HMuted, fontSize = 8.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HPanelCard(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(Brush.verticalGradient(listOf(Color(0xFF151715), Color(0xFF080A09))), RoundedCornerShape(15.dp))
        .border(1.dp, HGold.copy(alpha = .78f), RoundedCornerShape(15.dp)).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (title.isNotBlank()) Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun MacroLine(label: String, value: String, change: Double?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = HMuted, fontSize = 8.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        if (change != null) Text(" " + String.format(java.util.Locale.US, "%+.2f%%", change),
            color = if (change >= 0) HMint else HRed, fontSize = 8.sp)
    }
}

@Composable
private fun StatusLine(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (good) HMint else HGold, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, color = HMuted, fontSize = 8.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (good) HMint else HGold, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MetricTile(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.background(HPanel, RoundedCornerShape(11.dp)).border(1.dp, HGold.copy(alpha=.55f), RoundedCornerShape(11.dp)).padding(9.dp)) {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(label, color = HMuted, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
private fun ScoreRing(score: Int, label: String) {
    Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(66.dp)) {
            drawArc(Color(0xFF30342F), -90f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(6.dp.toPx()))
            drawArc(HMint, -90f, score.coerceIn(0,100)*3.6f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (score in 1..100) "$score%" else "—", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(label, color = HMuted, fontSize = 7.sp)
        }
    }
}

@Composable
private fun PriceTag(label: String, value: Double, color: Color, modifier: Modifier) {
    Column(modifier.background(Color(0xFF080A09), RoundedCornerShape(7.dp)).padding(5.dp)) {
        Text(label, color = color, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        Text(if (value.isFinite() && value > 0) String.format(java.util.Locale.US, "%.5f", value) else "—", color = Color.White, fontSize = 7.sp, maxLines = 1)
    }
}

@Composable
private fun CommanderCandlestickChart(signal: MarketSignal) {
    val candles = signal.candles.filter { c -> c.size >= 6 && c.take(6).all { it.isFinite() } &&
        c[0] > 0 && c[1] >= max(c[0],c[3]) && c[2] <= min(c[0],c[3]) && c[2] > 0 }.takeLast(72)
    if (candles.size < 3) {
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            Text("دادهٔ کندلی کافی برای رسم نمودار موجود نیست", color = HMuted, fontSize = 9.sp)
        }
        return
    }
    val levels = listOf(signal.entry, signal.stop, signal.tp1, signal.tp2, signal.tp3).filter { it.isFinite() && it > 0 }
    val low = min(candles.minOf { it[2] }, levels.minOrNull() ?: candles.minOf { it[2] })
    val high = max(candles.maxOf { it[1] }, levels.maxOrNull() ?: candles.maxOf { it[1] })
    val span = (high-low).takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(160.dp).background(Color(0xFF050706), RoundedCornerShape(9.dp))) {
        val left = 5.dp.toPx()
        val right = size.width-5.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height-8.dp.toPx()
        fun y(price: Double) = bottom - (((price-low)/span).toFloat()*(bottom-top))
        for (i in 0..4) {
            val yy = top + (bottom-top)*i/4
            drawLine(Color(0xFF252B27), Offset(left,yy), Offset(right,yy), 1.dp.toPx())
        }
        val step = (right-left)/candles.size
        candles.forEachIndexed { index, c ->
            val x = left + step*(index+.5f)
            val up = c[3] >= c[0]
            val color = if (up) HMint else HRed
            drawLine(color, Offset(x,y(c[1])), Offset(x,y(c[2])), 1.dp.toPx())
            val bodyTop = min(y(c[0]),y(c[3]))
            val bodyBottom = max(y(c[0]),y(c[3]))
            drawRect(color, Offset(x-step*.29f,bodyTop), androidx.compose.ui.geometry.Size(max(1.dp.toPx(),step*.58f), max(1.dp.toPx(),bodyBottom-bodyTop)))
        }
        val lineLevels = listOf(signal.entry to HGold, signal.stop to HRed, signal.tp1 to HMint)
        lineLevels.forEach { (price,color) ->
            if (price.isFinite() && price in low..high) {
                val yy = y(price)
                drawLine(color.copy(alpha=.9f), Offset(left,yy), Offset(right,yy), 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7f,5f)))
            }
        }
    }
}

private fun sideFa(direction: String): String = when(direction) {
    "LONG_BIAS" -> "صعودی"
    "SHORT_BIAS" -> "نزولی"
    else -> "خنثی"
}
private fun shortVote(vote: String): String = when {
    vote.contains("LONG", true) || vote.contains("صعود") -> "صعودی"
    vote.contains("SHORT", true) || vote.contains("نزول") -> "نزولی"
    vote.contains("NEUTRAL", true) || vote.contains("خنثی") -> "خنثی"
    else -> vote.take(8)
}
private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
private fun compactUsd(value: Double): String = when {
    value >= 1e12 -> String.format(java.util.Locale.US, "%.2fT", value/1e12)
    value >= 1e9 -> String.format(java.util.Locale.US, "%.2fB", value/1e9)
    else -> String.format(java.util.Locale.US, "%.0fM", value/1e6)
}
