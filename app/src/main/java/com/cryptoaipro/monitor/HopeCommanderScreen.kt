package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private val HGold = Color(0xFFC9A45B)
private val HGoldBright = Color(0xFFF1D78A)
private val HInk = Color(0xFF030303)
private val HPanel = Color(0xFF0B0B0B)
private val HPanel2 = Color(0xFF15120A)
private val HMint = Color(0xFF43E0A0)
private val HRed = Color(0xFFFF6474)
private val HMuted = Color(0xFFB2A58B)
private val HBlue = Color(0xFFD6A93E)
private val HPurple = Color(0xFFB78A38)

@Composable
fun HopeCommanderBrain(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hopeBrainPulse")
    val pulse by transition.animateFloat(.78f, 1.08f,
        infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * .34f
        drawCircle(Color(0x44D8B96E), r * 1.65f * pulse, Offset(cx, cy))
        drawCircle(HGold.copy(alpha = .9f), r * 1.28f, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        drawCircle(HGold.copy(alpha = .42f), r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        for (i in 0 until 24) {
            val a = Math.PI * 2 * i / 24
            val x = cx + kotlin.math.cos(a).toFloat() * r
            val y = cy + kotlin.math.sin(a).toFloat() * r
            val x2 = cx + kotlin.math.cos(a + 1.05).toFloat() * r * .82f
            val y2 = cy + kotlin.math.sin(a + 1.05).toFloat() * r * .82f
            drawLine(HGold.copy(alpha = .42f), Offset(x, y), Offset(x2, y2), 1.dp.toPx())
            drawCircle(HGold.copy(alpha = .9f), 2.5.dp.toPx() * pulse, Offset(x, y))
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
        drawCircle(Color(0xFFF1D78A), 4.dp.toPx()*pulse, Offset(cx, cy))
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
    val liquidityMap = remember(best?.symbol, best?.candles?.size, best?.price) {
        val rows = best?.candles.orEmpty().mapNotNull { row ->
            if (row.size < 6) null else runCatching {
                ZoroLiquidityMapEngine.Candle(
                    time = row[5].toLong(), open = row[0], high = row[1], low = row[2],
                    close = row[3], volume = row[4]
                )
            }.getOrNull()
        }
        ZoroLiquidityMapEngine.analyze(rows)
    }
    val marketFingerprint = remember(best?.symbol, best?.candles?.size, best?.price) {
        WolfMarketFingerprintEngine.analyze(best?.candles.orEmpty())
    }
    val chartForensics = remember(best?.symbol, best?.candles?.size, best?.price) {
        WolfChartForensicsEngine.analyze(best?.candles.orEmpty())
    }
    val quantDynamics = remember(best?.symbol, best?.candles?.size, best?.price) {
        WolfQuantDynamicsEngine.analyze(best?.candles.orEmpty())
    }
    // Read the model gate from the ensemble itself; display strings are not a source of truth.
    val validated = WolfPretrainedEnsemble.status().validatedForScoring
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
            Row(Modifier.fillMaxWidth().background(
                Brush.horizontalGradient(listOf(Color(0xFF1B1B1B), Color(0xFF050505), Color(0xFF24201A))),
                RoundedCornerShape(18.dp)
            ).border(1.dp, Color(0xFF8C6B32), RoundedCornerShape(18.dp)).padding(11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("T", color = HGoldBright, fontSize = 43.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.width(54.dp), textAlign = TextAlign.Center)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("TENAXIS", color = HGoldBright, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text("AI TRADING TERMINAL", color = HGold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                    Text("MARKET INTELLIGENCE  •  XT FUTURES", color = HMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("مغز تحلیل، سیگنال و اجرای معاملات", color = Color.White, fontSize = 14.sp)
                }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "تنظیمات", tint = HGold) }
                Icon(Icons.Outlined.Notifications, contentDescription = "اعلان‌ها", tint = HGoldBright, modifier = Modifier.padding(7.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MetricTile("سرمایه آزمایشی USDT",
                    paperSnapshot?.let { fmt(it.equity) } ?: "—",
                    HGoldBright, Modifier.weight(1f))
                MetricTile("سود امروز PAPER",
                    paperSnapshot?.let { String.format(java.util.Locale.US, "%+.2f", it.dailyPnl) } ?: "—",
                    if ((paperSnapshot?.dailyPnl ?: 0.0) >= 0.0) HMint else HRed, Modifier.weight(1f))
                MetricTile("پوزیشن‌های آزمایشی",
                    paperSnapshot?.positions?.size?.toString() ?: "۰",
                    HGold, Modifier.weight(1f))
            }
            Text("اعداد این سه کارت فقط دفترکل آزمایشی‌اند؛ موجودی و سود حساب واقعی XT نیستند.",
                color = HMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 3.dp))
        }
        item {
            HopeLivingBrainPanel(context)
        }
        item {
            XtLivePositionsPanel(context)
        }
        item {
            NeuralEnsemblePulse(
                probabilities = best?.neuralMemberProbabilities.orEmpty(),
                validated = validated,
                signalCount = signals.size
            )
        }
        item {
            TenaxisSignalCommandCard(best, validated)
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
                        color = HMuted, fontSize = 14.sp)
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
                        Text("فرمانده مرکزی سیستم", color = HMuted, fontSize = 14.sp)
                        Text("TENAXIS", color = HGoldBright, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text("مغز یکپارچهٔ تحلیل و تصمیم‌گیری", color = Color.White, fontSize = 14.sp)
                        Surface(color = Color(0xFF15291E), shape = RoundedCornerShape(30.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, HMint.copy(alpha = glow))) {
                            Text(if (signals.isEmpty()) "در انتظار دریافت داده…" else "در حال پایش و داوری شواهد",
                                color = HMint, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 7.dp))
                        }
                    }
                    HopeCommanderBrain(Modifier.size(116.dp))
                }
            }
        }
        item {
            HPanelCard(Modifier.fillMaxWidth(), "مغز تحلیل تکنیکال چندلایه • چارت و ساختار بازار") {
                val ta = best?.advancedTechnical
                if (ta == null || !ta.available) {
                    Text(ta?.warnings?.firstOrNull() ?: "پس از دریافت کندل‌های معتبر، تحلیل جامع چارت ساخته می‌شود",
                        color = HMuted, fontSize = 13.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(ta.bias, color = if (ta.evidenceScore > 0) HMint else if (ta.evidenceScore < 0) HRed else HGold,
                            fontSize = 14.sp, fontWeight = FontWeight.Black)
                        Text("قدرت شواهد " + ta.evidenceScore + "/100", color = HGoldBright, fontSize = 14.sp)
                    }
                    Text(ta.trend + " • " + ta.regime + " • " + ta.momentum, color = HMuted, fontSize = 13.sp)
                    Text(ta.structure + " • " + ta.higherTimeframe, color = Color.White, fontSize = 13.sp)
                    Text("حجم: " + ta.volumeState, color = HMuted, fontSize = 13.sp)
                    Divider(color = Color(0xFF46371D))
                    Text("الگوهای کندلی: " + ta.candlePatterns.joinToString(" • ").ifBlank { "الگوی قوی تأیید نشد" },
                        color = HGoldBright, fontSize = 13.sp)
                    Text("الگوهای نموداری: " + ta.chartPatterns.joinToString(" • "),
                        color = HGoldBright, fontSize = 13.sp, lineHeight = 13.sp)
                    Text("ساختار نقدینگی: " + ta.smartMoney, color = Color.White, fontSize = 13.sp, lineHeight = 13.sp)
                    Divider(color = Color(0xFF29445A))
                    Text("نقشه نقدینگی پویا • Liquidity Pools", color = HGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(liquidityMap.summary, color = Color.White, fontSize = 13.sp, lineHeight = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Buy-side / استاپ‌های احتمالی بالای سقف", color = HMuted, fontSize = 14.sp)
                            Text(liquidityMap.nearestBuySide?.let { String.format(java.util.Locale.US, "%.6f", it.price) + " • قدرت " + it.strength + "/100" } ?: "خوشه‌ای یافت نشد",
                                color = HGoldBright, fontSize = 13.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Sell-side / استاپ‌های احتمالی زیر کف", color = HMuted, fontSize = 14.sp)
                            Text(liquidityMap.nearestSellSide?.let { String.format(java.util.Locale.US, "%.6f", it.price) + " • قدرت " + it.strength + "/100" } ?: "خوشه‌ای یافت نشد",
                                color = HGoldBright, fontSize = 13.sp)
                        }
                    }
                    Text("جاروب سقف‌ها: " + (if (liquidityMap.sweptBuySide) "تأیید ساختاری" else "مشاهده نشد") +
                        " • جاروب کف‌ها: " + (if (liquidityMap.sweptSellSide) "تأیید ساختاری" else "مشاهده نشد"),
                        color = if (liquidityMap.sweptBuySide || liquidityMap.sweptSellSide) HGold else HMuted, fontSize = 14.sp)
                    Text("این سطوح تخمین خوشه‌های حدضرر از کندل‌ها هستند، نه مشاهده سفارش‌های مخفی یا داده قطعی لیکوییدیشن.",
                        color = HMuted, fontSize = 14.sp, lineHeight = 12.sp)
                    Divider(color = Color(0xFF29445A))
                    Text("پنل اندیکاتورها", color = HGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    ta.indicators.forEach { metric ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(metric.name, color = HMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(metric.value, color = HGoldBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(metric.interpretation, color = HMuted, fontSize = 14.sp, modifier = Modifier.weight(1.35f))
                        }
                    }
                    Divider(color = Color(0xFF29445A))
                    Text("سطوح و نواحی مهم", color = HGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    ta.levels.take(7).forEach { level ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(level.name, color = HMuted, fontSize = 14.sp)
                            Text(String.format(java.util.Locale.US, "%.6f", level.price), color = HGoldBright, fontSize = 14.sp)
                        }
                    }
                    if (ta.warnings.isNotEmpty()) {
                        Text("هشدارها: " + ta.warnings.take(4).joinToString(" • "), color = HGold, fontSize = 14.sp, lineHeight = 12.sp)
                    }
                    Text("راهنمای تحلیل", color = HGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    ta.education.forEach { Text("• " + it, color = HMuted, fontSize = 14.sp, lineHeight = 12.sp) }
                    Text("امتیاز این بخش شاخص قدرت شواهد است، نه احتمال برد. اصطلاح کوانتومی در TENAXIS به سناریوسازی چندحالته اشاره دارد؛ ادعای استفاده از سخت‌افزار کوانتومی نمی‌شود.",
                        color = HMuted, fontSize = 14.sp, lineHeight = 12.sp)
                }
            }
        }
        item {
            HPanelCard(Modifier.fillMaxWidth(), "شناسنامه بازار و رادار تله‌ها • WOLF MARKET FINGERPRINT") {
                if (!marketFingerprint.available) {
                    Text(marketFingerprint.decision, color = HMuted, fontSize = 13.sp)
                } else {
                    Text(marketFingerprint.identity + " • " + marketFingerprint.regime.name,
                        color = HGoldBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("تشخیص رفتار: " + marketFingerprint.deception.name +
                        " • شدت هشدار " + marketFingerprint.trapSeverity + "/100",
                        color = if (marketFingerprint.trapSeverity >= 60) HRed else HGold, fontSize = 13.sp)
                    Text("نقدینگی: " + marketFingerprint.liquiditySummary, color = Color.White, fontSize = 13.sp)
                    Text("ساختار: " + marketFingerprint.marketStructure, color = HMuted, fontSize = 13.sp)
                    marketFingerprint.behaviorTraits.forEach { Text("• " + it, color = HGoldBright, fontSize = 14.sp) }
                    Text(marketFingerprint.decision, color = HGoldBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Divider(color = Color(0xFF46371D))
                Text("آزمایشگاه جرم‌شناسی چارت • WOLF CHART FORENSICS",
                    color = HGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (!chartForensics.available) {
                    Text("برای تحلیل عمیق، حداقل ۸۰ کندل معتبر لازم است.", color = HMuted, fontSize = 13.sp)
                } else {
                    Text(chartForensics.profile, color = Color.White, fontSize = 13.sp)
                    Text("VWAP: " + (chartForensics.vwap?.let { String.format(java.util.Locale.US, "%.6f", it) } ?: "—") +
                        " • فاصله: " + (chartForensics.vwapDistancePct?.let { String.format(java.util.Locale.US, "%+.2f%%", it) } ?: "—"),
                        color = HGoldBright, fontSize = 13.sp)
                    Text("RSI: " + (chartForensics.rsi?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—") +
                        " • " + chartForensics.rsiDivergence, color = HMuted, fontSize = 13.sp)
                    Text(chartForensics.obvDivergence + " • " + chartForensics.structure,
                        color = Color.White, fontSize = 13.sp)
                    Text(chartForensics.volatility + " • " + chartForensics.candleRead,
                        color = HGoldBright, fontSize = 13.sp)
                    if (chartForensics.gaps.isNotEmpty()) {
                        Text("شکاف‌های ارزش منصفانه:", color = HGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        chartForensics.gaps.forEach { z ->
                            Text(z.label + ": " + String.format(java.util.Locale.US, "%.6f–%.6f", z.low, z.high),
                                color = HMuted, fontSize = 14.sp)
                        }
                    }
                    if (chartForensics.supplyDemand.isNotEmpty()) {
                        Text("نواحی عرضه/تقاضای احتمالی:", color = HGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        chartForensics.supplyDemand.forEach { z ->
                            Text(z.label + ": " + String.format(java.util.Locale.US, "%.6f–%.6f", z.low, z.high) +
                                " • قدرت نسبی " + z.strength, color = HMuted, fontSize = 14.sp)
                        }
                    }
                    chartForensics.cautions.forEach { Text("• " + it, color = HMuted, fontSize = 14.sp, lineHeight = 11.sp) }
                }
                Text("تمام برآوردها از کندل‌های OHLCV هستند؛ دفتر سفارش پنهان یا لیکوییدیشن قطعی را نشان نمی‌دهند.",
                    color = HMuted, fontSize = 14.sp, lineHeight = 11.sp)
            }
        }
        item {
            HPanelCard(Modifier.fillMaxWidth(), "موتور دینامیک و ریاضیات بازار • WOLF QUANT DYNAMICS") {
                if (!quantDynamics.available) {
                    Text(quantDynamics.riskDirective, color = HRed, fontSize = 13.sp)
                } else {
                    Text("رژیم: " + quantDynamics.state.name + " • اطمینان طبقه‌بندی: " +
                        String.format(java.util.Locale.US, "%.0f%%", quantDynamics.stateConfidence * 100),
                        color = HGoldBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("سرعت: " + String.format(java.util.Locale.US, "%+.4f%%", quantDynamics.trendVelocityPct) +
                        " • شتاب: " + String.format(java.util.Locale.US, "%+.4f", quantDynamics.accelerationPct) +
                        " • نوسان تحقق‌یافته: " + String.format(java.util.Locale.US, "%.3f%%", quantDynamics.realizedVolPct),
                        color = Color.White, fontSize = 13.sp)
                    Text("انرژی مومنتوم: " + String.format(java.util.Locale.US, "%.2f", quantDynamics.momentumEnergy) +
                        " • کارایی مسیر: " + String.format(java.util.Locale.US, "%.2f", quantDynamics.efficiencyRatio) +
                        " • آنتروپی: " + String.format(java.util.Locale.US, "%.2f", quantDynamics.returnEntropy),
                        color = HMuted, fontSize = 13.sp)
                    Text("نسبت نوسان اخیر/گذشته: " + String.format(java.util.Locale.US, "%.2f", quantDynamics.volatilityRatio) +
                        " • نسبت واریانس: " + String.format(java.util.Locale.US, "%.2f", quantDynamics.varianceRatio),
                        color = HGoldBright, fontSize = 13.sp)
                    Text("فیلتر روند: " + String.format(java.util.Locale.US, "%.6f", quantDynamics.kalmanPrice) +
                        " • شیب: " + String.format(java.util.Locale.US, "%+.4f", quantDynamics.kalmanTrendPct),
                        color = HMuted, fontSize = 13.sp)
                    Text("تمایل: " + quantDynamics.directionalBias, color = Color.White, fontSize = 13.sp)
                    Text("احتمال‌های نسبی رژیم — روند " +
                        String.format(java.util.Locale.US, "%.0f%%", quantDynamics.trendProbability*100) +
                        " | تعادل " + String.format(java.util.Locale.US, "%.0f%%", quantDynamics.rangeProbability*100) +
                        " | آشفتگی " + String.format(java.util.Locale.US, "%.0f%%", quantDynamics.chaosProbability*100) +
                        " | فشردگی " + String.format(java.util.Locale.US, "%.0f%%", quantDynamics.compressionProbability*100),
                        color = HGold, fontSize = 14.sp, lineHeight = 11.sp)
                    Text("سناریوهای آماری مشروط:", color = HGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    quantDynamics.scenarios.forEach { scenario ->
                        Text(scenario.horizonBars.toString() + " کندل: مرکز " +
                            String.format(java.util.Locale.US, "%+.2f%%", scenario.expectedMovePct) +
                            " • باند " + String.format(java.util.Locale.US, "%+.2f تا %+.2f%%",
                                scenario.lowerBandPct, scenario.upperBandPct),
                            color = HMuted, fontSize = 14.sp)
                    }
                    Text(quantDynamics.riskDirective, color = HGoldBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    quantDynamics.notes.forEach { Text("• " + it, color = HMuted, fontSize = 14.sp, lineHeight = 11.sp) }
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
                                .background(Color(0xFF0D0C0A), RoundedCornerShape(9.dp))
                                .border(1.dp, if (neuralActive && active) HGold else Color(0xFF554323), RoundedCornerShape(9.dp))
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
                    color = HMuted, fontSize = 14.sp)
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
                    Text(best?.technical?.regime ?: "در انتظار تشخیص رژیم", color = HGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(best?.technical?.marketStructure ?: "ساختار بازار پس از دریافت کندل مشخص می‌شود",
                        color = Color.White, fontSize = 13.sp, maxLines = 3)
                    Text("تایم‌فریم: " + (best?.tradeTimeframe ?: "—"), color = HMuted, fontSize = 13.sp)
                    Text("استراتژی برنده فقط پس از بک‌تست مستقل انتخاب می‌شود", color = HMuted, fontSize = 14.sp)
                    OutlinedButton(onClick = onOpenJournal, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("نتایج و ارزیابی", fontSize = 13.sp, color = HGold)
                    }
                }
                HPanelCard(Modifier.weight(1f), "سیگنال جاری") {
                    val isLong = best?.direction == "LONG_BIAS"
                    val isShort = best?.direction == "SHORT_BIAS"
                    Text(if (isLong) "↗ LONG" else if (isShort) "↘ SHORT" else "NO TRADE",
                        color = if (isLong) HMint else if (isShort) HRed else HGold, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Text("اعتماد: " + (best?.confidence?.toString() ?: "—") + "%", color = Color.White, fontSize = 14.sp)
                    Text("R:R " + (rr?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"),
                        color = HGold, fontSize = 14.sp)
                    Text(best?.signalTier ?: "بدون رتبه", color = HMuted, fontSize = 14.sp)
                }
            }
        }
        item {
            HPanelCard(Modifier.fillMaxWidth(), "نمودار تحلیل حرفه‌ای") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(best?.symbol ?: "در انتظار نماد", color = HGold, fontSize = 13.sp, fontWeight = FontWeight.Black)
                        Text(best?.timeframeCoverage ?: "دادهٔ چندتایم‌فریمی پس از اسکن نمایش داده می‌شود", color = HMuted, fontSize = 14.sp)
                    }
                    TextButton(onClick = onOpenChart) { Text("ابزارهای کامل چارت ←", color = HGold, fontSize = 13.sp) }
                }
                if (best != null) CommanderCandlestickChart(best)
                else Box(Modifier.fillMaxWidth().height(145.dp), contentAlignment = Alignment.Center) {
                    Text("پس از دریافت کندل‌های واقعی، نمودار اینجا نمایش داده می‌شود", color = HMuted, fontSize = 14.sp)
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
            val humanRead = remember(best?.symbol, best?.candles?.size, best?.price) {
                best?.let { ZoroHumanMarketReader.analyze(it.candles) }
            }
            HPanelCard(Modifier.fillMaxWidth(), "خوانش رفتاری چارت • تحلیل ساختار مثل تریدر") {
                if (humanRead == null || !humanRead.available) {
                    Text(humanRead?.warnings?.firstOrNull() ?: "برای خوانش رفتار قیمت حداقل ۸۰ کندل معتبر لازم است.",
                        color = HMuted, fontSize = 13.sp, lineHeight = 13.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(humanRead.phase, color = HGoldBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("شاخص شواهد ${humanRead.score}/100",
                            color = if (humanRead.score > 15) HMint else if (humanRead.score < -15) HRed else HGold,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("ساختار سوئینگ: ${humanRead.structure}", color = Color.White, fontSize = 13.sp, lineHeight = 13.sp)
                    Text("حراج و پذیرش قیمت: ${humanRead.auction}", color = HMuted, fontSize = 13.sp, lineHeight = 13.sp)
                    Text("جریان حجم: ${humanRead.flow}", color = HMuted, fontSize = 13.sp, lineHeight = 13.sp)
                    Text("الگوهای مشاهده‌شده: ${humanRead.patterns.take(8).joinToString(" • ").ifBlank { "الگوی معتبر کافی نیست" }}",
                        color = HGoldBright, fontSize = 13.sp, lineHeight = 13.sp)
                    Divider(color = Color(0xFF46371D))
                    Text("سناریوی صعودی", color = HMint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(humanRead.bullishScenario, color = Color.White, fontSize = 13.sp, lineHeight = 13.sp)
                    Text("سناریوی نزولی", color = HRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(humanRead.bearishScenario, color = Color.White, fontSize = 13.sp, lineHeight = 13.sp)
                    if (humanRead.warnings.isNotEmpty()) {
                        Text("احتیاط: ${humanRead.warnings.take(3).joinToString(" • ")}",
                            color = HGold, fontSize = 14.sp, lineHeight = 12.sp)
                    }
                    Text("خوانش از توالی OHLCV و رفتار قیمت انجام می‌شود؛ دلتا و استخر نقدینگی در صورت نبود داده صرافی، تخمینی‌اند.",
                        color = HMuted, fontSize = 14.sp, lineHeight = 12.sp)
                }
            }
        }
        item {
            HPanelCard(Modifier.fillMaxWidth(), "پیش‌بینی چندافقی • Truth Engine") {
                val outlook = best?.multiHorizonOutlook
                if (outlook == null || !outlook.available) {
                    Text(outlook?.truthStatus ?: "پس از دریافت کندل‌های معتبر، سناریوها محاسبه می‌شوند",
                        color = HMuted, fontSize = 13.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("کیفیت داده: ${outlook.dataQuality}/100", color = if (outlook.dataQuality >= 80) HMint else HGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(outlook.trendState, color = HGoldBright, fontSize = 13.sp)
                    }
                    Text("رژیم: ${outlook.regime} • ${outlook.flowState}", color = HMuted, fontSize = 14.sp)
                    outlook.horizons.forEach { horizon ->
                        val tint = when (horizon.direction) {
                            "LONG_BIAS" -> HMint
                            "SHORT_BIAS" -> HRed
                            else -> HGold
                        }
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFF0D0C0A), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(horizon.label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(sideFa(horizon.direction), color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text("امتیاز ${horizon.evidenceScore}", color = HGold, fontSize = 14.sp)
                            Spacer(Modifier.width(10.dp))
                            Text("دامنه ±${String.format(java.util.Locale.US, "%.2f", horizon.volatilityBandPct)}٪",
                                color = HMuted, fontSize = 14.sp)
                        }
                        Text(horizon.explanation, color = HMuted, fontSize = 14.sp)
                    }
                    Text(outlook.truthStatus, color = HGoldBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (outlook.warnings.isNotEmpty()) {
                        Text(outlook.warnings.take(4).joinToString(" • "), color = HMuted, fontSize = 14.sp, lineHeight = 12.sp)
                    }
                    Text("امتیازها قدرت شواهد سناریو هستند، نه درصد احتمال برد یا تضمین حرکت قیمت. ${if (outlook.neuralSixHourUsed) "نظر عصبی معتبر در افق ۶ساعته لحاظ شده" else "نظر عصبی تا اعتبارسنجی مستقل در امتیازها دخالت ندارد"}.",
                        color = HMuted, fontSize = 14.sp, lineHeight = 12.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HPanelCard(Modifier.weight(1f), "پایش پس‌زمینه") {
                    Text(if (backgroundMonitoring) "● فعال" else "● متوقف", color = if (backgroundMonitoring) HMint else HGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("اسکن بازار، خبر و جریان سفارش در سرویس پس‌زمینه", color = HMuted, fontSize = 14.sp)
                    TextButton(onClick = onToggleBackground, contentPadding = PaddingValues(0.dp)) {
                        Text(if (backgroundMonitoring) "توقف پایش" else "شروع پایش", color = HGold, fontSize = 13.sp)
                    }
                }
                HPanelCard(Modifier.weight(1f), "آخرین تحلیل") {
                    Text(best?.reason ?: "هنوز تحلیلی ثبت نشده", color = Color.White, fontSize = 13.sp, maxLines = 4)
                    Text(best?.agreementStatus ?: "فرمانده منتظر دادهٔ معتبر است", color = HMuted, fontSize = 14.sp, maxLines = 3)
                }
                HPanelCard(Modifier.weight(1f), "چرخهٔ یادگیری") {
                    Text(if (validated) "مدل معتبر" else "در انتظار اعتبارسنجی", color = if (validated) HMint else HGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("به‌روزرسانی مدل فقط پس از ارزیابی خارج از نمونه", color = HMuted, fontSize = 14.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenMarket, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4B3715))) { Text("بازارها", color = HGoldBright) }
                OutlinedButton(onClick = onOpenJournal, modifier = Modifier.weight(1f)) { Text("دفترچه و نتایج", color = HGold) }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("تنظیمات", color = HGold) }
            }
        }
        item {
            paperSnapshot?.let { HopePaperTradingDashboard(it) }
        }
        item {
            PaperTradingSafetyPanel(
                context,
                context.getSharedPreferences("hope_paper_ledger", Context.MODE_PRIVATE)
            )
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16414A))) {
                    Text(if (isLoading) "در حال اسکن…" else "اسکن مجدد بازار", color = HGoldBright)
                }
            }
        }
        item {
            Text("TENAXIS • مغز تحلیل بازار، اجرای XT و حفاظت پویا از پوزیشن",
                color = HMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}


@Composable
private fun TenaxisSignalCommandCard(best: MarketSignal?, neuralValidated: Boolean) {
    val neuralSide = best?.neuralDirection ?: "NEUTRAL"
    val systemSide = best?.direction ?: "NEUTRAL"
    val neuralColor = when (neuralSide) {
        "LONG_BIAS" -> HMint
        "SHORT_BIAS" -> HRed
        else -> HGold
    }
    val systemColor = when (systemSide) {
        "LONG_BIAS" -> HMint
        "SHORT_BIAS" -> HRed
        else -> HGold
    }
    val neuralLabel = when (neuralSide) {
        "LONG_BIAS" -> "LONG • صعودی"
        "SHORT_BIAS" -> "SHORT • نزولی"
        else -> "NEUTRAL • بدون جهت"
    }
    val systemLabel = when (systemSide) {
        "LONG_BIAS" -> "LONG • مجاز از نظر سیستم"
        "SHORT_BIAS" -> "SHORT • مجاز از نظر سیستم"
        else -> "WAIT • فعلاً بدون ورود"
    }
    val rr = best?.let {
        val risk = kotlin.math.abs(it.entry - it.stop)
        val reward = kotlin.math.abs(it.tp1 - it.entry)
        if (risk > 0.0 && reward.isFinite() && risk.isFinite()) reward / risk else null
    }
    HPanelCard(Modifier.fillMaxWidth(), "سیگنال یکپارچه • نظر شبکه عصبی + تصمیم سیستم") {
        if (best == null) {
            Text("پس از دریافت دادهٔ زنده، نظر شبکه و تصمیم سیستم در این قسمت کنار هم نمایش داده می‌شوند.",
                color = HMuted, fontSize = 13.sp, lineHeight = 14.sp)
            return@HPanelCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).background(Color(0xFF17130D), RoundedCornerShape(11.dp)).padding(10.dp)) {
                Text("نظر شبکه عصبی", color = HMuted, fontSize = 14.sp)
                Text(neuralLabel, color = neuralColor, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Text("امتیاز جهت خام ${best.neuralScore}/100", color = Color.White, fontSize = 13.sp)
                Text(if (neuralValidated) "مدل اعتبارسنجی‌شده" else "مدل پژوهشی • بدون ادعای احتمال برد",
                    color = if (neuralValidated) HMint else HGold, fontSize = 14.sp)
            }
            Column(Modifier.weight(1f).background(Color(0xFF17130D), RoundedCornerShape(11.dp)).padding(10.dp)) {
                Text("تصمیم نهایی سیستم", color = HMuted, fontSize = 14.sp)
                Text(systemLabel, color = systemColor, fontSize = 14.sp, fontWeight = FontWeight.Black)
                Text("قدرت شواهد ${best.confidence}٪ • ${best.signalTier}", color = Color.White, fontSize = 13.sp)
                Text(if (neuralSide == systemSide && systemSide != "NEUTRAL") "هم‌جهتی دو خروجی" else "اختلاف/عدم تأیید؛ ورود نکن",
                    color = if (neuralSide == systemSide && systemSide != "NEUTRAL") HMint else HGold, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(best.symbol, color = HGoldBright, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text("${best.tradeTimeframe} • اهرم پیشنهادی ${best.leverage}x", color = HGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (systemSide == "LONG_BIAS" || systemSide == "SHORT_BIAS") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PriceTag("ENTRY", best.entry, HGoldBright, Modifier.weight(1f))
                PriceTag("STOP LOSS", best.stop, HRed, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PriceTag("TP1", best.tp1, HMint, Modifier.weight(1f))
                PriceTag("TP2", best.tp2, HMint, Modifier.weight(1f))
                PriceTag("TP3", best.tp3, HMint, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ریسک قیمت تا SL: ${if (best.entry > 0.0) String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(best.entry-best.stop)/best.entry*100.0) else "—"}٪",
                    color = HRed, fontSize = 13.sp)
                Text("R/R تا TP1: ${rr?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"}",
                    color = HMint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text("سیستم هنوز مجوز صدور سیگنال جهت‌دار نداده است؛ رأی خام شبکه را به‌تنهایی مبنای سفارش قرار نده.",
                color = HGold, fontSize = 13.sp, lineHeight = 14.sp)
        }
        Text("مدل ۱۰ متخصصِ ازپیش‌آموزش‌دیده فعلاً ۷ ویژگی دارد: روند EMA، مومنتوم RSI، قدرت ADX، حجم نسبی، فشار بدنه کندل، نوسان ATR و مومنتوم ۶ کندلی. این مدل هنوز به‌دلیل نداشتن کالیبراسیون مستقل، walk-forward و آزمون نمادهای دیده‌نشده، فقط پژوهشی است. خبر، احساسات اجتماعی، دفتر سفارش و جریان سفارش ورودی این مدل نیستند.",
            color = HMuted, fontSize = 14.sp, lineHeight = 12.sp)
        Text("مغز آنلاین ۶۷ ویژگی یک مسیر یادگیری جداگانه دارد؛ تعداد نمونه‌های بررسی‌شده در پایین نمایش داده می‌شود و تا ارزیابی خارج از نمونه، مجوز مستقل معامله ندارد.",
            color = HGold, fontSize = 14.sp, lineHeight = 12.sp)
        Text("نمونه‌های نتیجهٔ بررسی‌شده: ${best.deepNeuralReviewedSamples} • ویژگی‌های مغز چنددامنه: ${WolfDeepNeuralNetwork.FEATURE_COUNT}",
            color = HBlue, fontSize = 14.sp)
    }
}

@Composable
private fun NeuralEnsemblePulse(
    probabilities: List<Double>,
    validated: Boolean,
    signalCount: Int
) {
    val transition = rememberInfiniteTransition(label = "neuralPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1250), RepeatMode.Reverse),
        label = "neuralPulseAlpha"
    )
    val usable = probabilities.take(10).map { it.takeIf { p -> p.isFinite() }?.coerceIn(0.0, 1.0) }
    HPanelCard(Modifier.fillMaxWidth(), "نقشهٔ زندهٔ خروجی ۱۰ متخصص عصبی") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (usable.size == 10) "۱۰ خروجی مستقل دریافت شد" else "در انتظار خروجی کامل متخصصان",
                    color = HGoldBright, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (validated) "اعتبارسنجی مستقل تأیید شده" else "حالت پژوهشی • بدون وزن در مجوز معامله",
                    color = if (validated) HMint else HGold, fontSize = 14.sp
                )
            }
            Surface(
                color = Color(0xFF0B1B2B),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, HGold.copy(alpha = pulse))
            ) {
                Text(
                    "$signalCount نماد",
                    color = HGoldBright,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    fontSize = 14.sp
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
            val count = 10
            val gap = 7.dp.toPx()
            val barWidth = (size.width - gap * (count - 1)) / count
            val base = size.height - 4.dp.toPx()
            for (i in 0 until count) {
                val x = i * (barWidth + gap) + barWidth / 2f
                val p = usable.getOrNull(i) ?: 0.5
                val magnitude = if (usable.getOrNull(i) == null) 0.12f else
                    (kotlin.math.abs(p - 0.5) * 2.0).toFloat().coerceIn(0.08f, 1f)
                val top = base - (size.height - 10.dp.toPx()) * magnitude
                drawLine(
                    color = Color(0xFF554323),
                    start = Offset(x, 3.dp.toPx()),
                    end = Offset(x, base),
                    strokeWidth = barWidth.coerceAtLeast(2.dp.toPx()),
                    cap = StrokeCap.Round
                )
                val tint = if (usable.getOrNull(i) == null) HMuted
                    else if (p >= 0.55) HMint else if (p <= 0.45) HRed else HGold
                drawLine(
                    color = tint.copy(alpha = if (usable.getOrNull(i) == null) 0.45f else pulse),
                    start = Offset(x, top),
                    end = Offset(x, base),
                    strokeWidth = (barWidth * 0.72f).coerceAtLeast(2.dp.toPx()),
                    cap = StrokeCap.Round
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("نزولی", color = HRed, fontSize = 14.sp)
            Text("خنثی", color = HGold, fontSize = 14.sp)
            Text("صعودی", color = HMint, fontSize = 14.sp)
        }
        Text(
            "ارتفاع هر ستون شدت فاصلهٔ رأی همان متخصص از حالت خنثی را نشان می‌دهد؛ این نمودار احتمال برد یا تضمین سود نیست.",
            color = HMuted, fontSize = 14.sp, lineHeight = 11.sp
        )
    }
}

@Composable
private fun HPanelCard(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(Brush.verticalGradient(listOf(Color(0xFF191919), Color(0xFF090909))), RoundedCornerShape(15.dp))
        .border(1.dp, Color(0xFF59421F), RoundedCornerShape(15.dp)).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (title.isNotBlank()) Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun MacroLine(label: String, value: String, change: Double?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = HMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (change != null) Text(" " + String.format(java.util.Locale.US, "%+.2f%%", change),
            color = if (change >= 0) HMint else HRed, fontSize = 14.sp)
    }
}

@Composable
private fun StatusLine(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (good) HMint else HGold, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, color = HMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (good) HMint else HGold, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MetricTile(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.background(Brush.verticalGradient(listOf(Color(0xFF211A0E), Color(0xFF100D08))), RoundedCornerShape(11.dp)).border(1.dp, Color(0xFF59421F), RoundedCornerShape(11.dp)).padding(10.dp)) {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(label, color = HMuted, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun ScoreRing(score: Int, label: String) {
    Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(66.dp)) {
            drawArc(Color(0xFF263B31), -90f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(6.dp.toPx()))
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
    Column(modifier.background(Color(0xFF171208), RoundedCornerShape(7.dp)).padding(6.dp)) {
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
            Text("دادهٔ کندلی کافی برای رسم نمودار موجود نیست", color = HMuted, fontSize = 13.sp)
        }
        return
    }
    val levels = (listOf(signal.entry, signal.stop, signal.tp1, signal.tp2, signal.tp3) + signal.advancedTechnical?.levels.orEmpty().map { it.price }).filter { it.isFinite() && it > 0 }
    val low = min(candles.minOf { it[2] }, levels.minOrNull() ?: candles.minOf { it[2] })
    val high = max(candles.maxOf { it[1] }, levels.maxOrNull() ?: candles.maxOf { it[1] })
    val span = (high-low).takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(160.dp).background(Color(0xFF17130D), RoundedCornerShape(9.dp))) {
        val left = 5.dp.toPx()
        val right = size.width-5.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height-8.dp.toPx()
        fun y(price: Double) = bottom - (((price-low)/span).toFloat()*(bottom-top))
        for (i in 0..4) {
            val yy = top + (bottom-top)*i/4
            drawLine(Color(0xFF302718), Offset(left,yy), Offset(right,yy), 1.dp.toPx())
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
        val lineLevels = listOf(signal.entry to HGold, signal.stop to HRed, signal.tp1 to HMint) +
            signal.advancedTechnical?.levels.orEmpty().take(4).map { it.price to Color(0xFF91A9BD) }
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
