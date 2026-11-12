package com.cryptoaipro.monitor

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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val CommanderGold = Color(0xFFE7BD72)
private val CommanderInk = Color(0xFF080A0A)
private val CommanderPanel = Color(0xFF141716)
private val CommanderMint = Color(0xFF55D6A0)
private val CommanderRed = Color(0xFFFF6470)

/** Animated neural command-core artwork drawn natively; no static mockup bitmap. */
@Composable
fun HopeCommanderBrain(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "commanderPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.72f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "pulse"
    )
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.34f
        drawCircle(Color(0x183FCFA1), radius * 1.55f * pulse, Offset(cx, cy))
        drawCircle(Color(0x224FCB9B), radius * 1.16f, Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        drawCircle(CommanderGold.copy(alpha = 0.75f), radius, Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        val nodes = (0 until 18).map { i ->
            val angle = (Math.PI * 2.0 * i / 18.0) - Math.PI / 2.0
            Offset(cx + cos(angle).toFloat() * radius, cy + sin(angle).toFloat() * radius)
        }
        nodes.forEachIndexed { index, point ->
            val next = nodes[(index + 5) % nodes.size]
            drawLine(CommanderGold.copy(alpha = 0.30f), point, next, strokeWidth = 1.dp.toPx())
            drawCircle(CommanderMint.copy(alpha = 0.85f), 3.2.dp.toPx() * pulse, point)
        }
        // Two hemispheres and central neural stem.
        drawLine(CommanderGold, Offset(cx, cy - radius * .72f), Offset(cx, cy + radius * .72f), 1.8.dp.toPx())
        drawLine(CommanderMint.copy(alpha = .8f), Offset(cx - radius*.72f, cy), Offset(cx + radius*.72f, cy), 1.dp.toPx())
        drawCircle(Color(0xFFFAE4B5), 5.dp.toPx() * pulse, Offset(cx, cy))
    }
}

@Composable
fun HopeCommanderScreen(signals: List<MarketSignal>) {
    val transition = rememberInfiniteTransition(label = "commanderStatus")
    val glow by transition.animateFloat(.35f, .9f,
        infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "glow")
    val active = signals.filter { it.direction != "NEUTRAL" }
    val longCount = active.count { it.direction == "LONG_BIAS" }
    val shortCount = active.count { it.direction == "SHORT_BIAS" }
    val best = active.maxByOrNull { it.confidence }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CommanderInk),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("مرکز فرماندهی HOPE", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
                Text("یک مغز مرکزی برای داوری تمام تحلیل‌ها", color = CommanderGold, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(190.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF17251F), Color(0xFF090C0B))), RoundedCornerShape(24.dp))
                    .border(1.dp, CommanderGold.copy(alpha = glow), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center) {
                    HopeCommanderBrain(Modifier.size(174.dp))
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("HOPE COMMANDER", color = CommanderGold, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("ANALYZE • CHALLENGE • DECIDE", color = Color.LightGray, fontSize = 8.sp)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommanderMetric("بازارهای تحلیل‌شده", signals.size.toString(), Modifier.weight(1f))
                CommanderMetric("لانگ", longCount.toString(), Modifier.weight(1f), CommanderMint)
                CommanderMetric("شورت", shortCount.toString(), Modifier.weight(1f), CommanderRed)
            }
        }
        item {
            CommanderSection("فرمانده چه چیزهایی را بررسی می‌کند")
            val checks = listOf(
                "تحلیل تکنیکال و ساختار بازار" to "EMA • RSI • MACD • ADX • BOS/CHoCH",
                "نظر شبکه عصبی" to "فقط پس از عبور از اعتبارسنجی مستقل مؤثر است",
                "اجماع تایم‌فریم‌ها" to "۵ دقیقه تا روزانه؛ بررسی هم‌جهتی و تعارض",
                "کیفیت معامله و ریسک" to "هندسه حدضرر، اهداف و نسبت سود به زیان",
                "شرایط بازار" to "روند، رنج، نوسان و احتمال تغییر رژیم"
            )
            checks.forEach { (title, subtitle) ->
                Row(Modifier.fillMaxWidth().background(CommanderPanel, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(CommanderMint, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(subtitle, color = Color(0xFFB5BDB8), fontSize = 9.sp)
                    }
                }
            }
        }
        item {
            CommanderSection("بهترین فرصت فعلی")
            if (best == null) {
                Text("در حال حاضر هیچ سیگنال تأییدشده‌ای وجود ندارد. فرمانده در حالت انتظار است.",
                    color = Color(0xFFD9CBAE), fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().background(CommanderPanel, RoundedCornerShape(16.dp)).padding(14.dp))
            } else {
                Column(Modifier.fillMaxWidth().background(CommanderPanel, RoundedCornerShape(16.dp)).padding(14.dp)) {
                    Text(best.symbol, color = CommanderGold, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(if (best.direction == "LONG_BIAS") "تمایل صعودی" else "تمایل نزولی",
                        color = if (best.direction == "LONG_BIAS") CommanderMint else CommanderRed, fontWeight = FontWeight.Bold)
                    Text("اعتماد ثبت‌شده: ${best.confidence}٪  •  ${best.signalTier}", color = Color.White, fontSize = 10.sp)
                    Text(best.agreementStatus, color = Color(0xFFB5BDB8), fontSize = 10.sp)
                }
            }
        }
        item {
            Text("فرمانده سود را تضمین نمی‌کند؛ وظیفه‌اش ارزیابی شواهد، کنترل ریسک و رد فرصت‌های نامعتبر است.",
                color = Color(0xFF9CA49F), fontSize = 9.sp)
        }
    }
}

@Composable
private fun CommanderMetric(title: String, value: String, modifier: Modifier, accent: Color = CommanderGold) {
    Column(modifier.background(CommanderPanel, RoundedCornerShape(15.dp)).padding(11.dp)) {
        Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(title, color = Color(0xFFB5BDB8), fontSize = 9.sp)
    }
}

@Composable
private fun CommanderSection(title: String) {
    Text(title, color = CommanderGold, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 3.dp, bottom = 2.dp))
}
