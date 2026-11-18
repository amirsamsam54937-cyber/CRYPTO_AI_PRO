package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * ZORO's adaptive trading supervisor.
 *
 * It combines explainable market-structure evidence with realized, fee-net paper outcomes.
 * It never invents a direction, never raises the configured risk budget, and never bypasses
 * XT account/preflight/position/TP-SL checks. Unvalidated neural votes are excluded.
 */
internal object ZoroLivingTraderBrain {
    data class Decision(
        val allowed: Boolean,
        val score: Int,
        val regime: String,
        val sampleCount: Int,
        val estimatedWinRate: Double?,
        val averageNetReturnPct: Double?,
        val reasons: List<String>
    )

    fun evaluate(context: Context, signal: MarketSignal): Decision {
        val reasons = mutableListOf<String>()
        val side = signal.direction.uppercase()
        if (side != "LONG_BIAS" && side != "SHORT_BIAS") {
            return Decision(false, 0, "NEUTRAL", 0, null, null, listOf("جهت بازار خنثی است"))
        }

        val tech = signal.technical
        val adx = tech.adx.takeIf { it.isFinite() } ?: 0.0
        val relativeVolume = tech.relativeVolume.takeIf { it.isFinite() } ?: 0.0
        val atrPct = tech.atrPercent.takeIf { it.isFinite() } ?: Double.POSITIVE_INFINITY
        val liquidity = tech.liquidityStrength
        val rr = rewardRisk(signal)
        val regime = tech.regime.ifBlank { "UNKNOWN" }.uppercase()
        var score = 50

        if (signal.technicalDirection.equals(side, true)) {
            score += 12
            reasons += "ساختار تکنیکال هم‌جهت"
        } else {
            score -= 20
            reasons += "تعارض جهت با ساختار تکنیکال"
        }

        if (adx >= 25.0) { score += 10; reasons += "روند قدرتمند" }
        else if (adx >= 18.0) { score += 5; reasons += "روند قابل قبول" }
        else { score -= 10; reasons += "قدرت روند ناکافی" }

        if (relativeVolume >= 1.5) { score += 8; reasons += "حجم نسبی تأییدکننده" }
        else if (relativeVolume >= 0.9) score += 3
        else { score -= 7; reasons += "حجم ضعیف" }

        when {
            atrPct.isFinite() && atrPct in 0.25..3.5 -> score += 6
            atrPct.isFinite() && atrPct <= 6.0 -> score += 1
            else -> { score -= 12; reasons += "نوسان خارج از محدودهٔ ریسک" }
        }

        when {
            rr != null && rr >= 2.0 -> { score += 10; reasons += "نسبت سود به زیان مطلوب" }
            rr != null && rr >= 1.5 -> score += 5
            else -> { score -= 18; reasons += "نسبت سود به زیان ناکافی" }
        }

        if (liquidity >= 65) score += 5
        else if (liquidity in 1..24) { score -= 6; reasons += "عمق/نقدینگی تأیید نمی‌کند" }

        val trendRegime = regime.contains("TREND") || regime.contains("BULL") || regime.contains("BEAR") ||
            regime.contains("UP") || regime.contains("DOWN") || regime.contains("روند")
        val rangeRegime = regime.contains("RANGE") || regime.contains("SIDE") || regime.contains("خنثی")
        if (trendRegime && adx >= 20.0) score += 4
        if (rangeRegime && adx < 18.0) { score -= 8; reasons += "بازار رنج؛ ورود تعقیبی ممنوع" }

        // A validated model may be one independent witness. An unvalidated model has zero authority.
        if (WolfPretrainedEnsemble.status().validatedForScoring) {
            val neural = signal.neuralDirection.uppercase()
            if (neural == side) { score += 4; reasons += "تأیید مدل عصبی اعتبارسنجی‌شده" }
            else if (neural == "LONG_BIAS" || neural == "SHORT_BIAS") {
                score -= 10
                reasons += "تعارض مدل عصبی اعتبارسنجی‌شده"
            }
        } else {
            reasons += "رأی عصبی صرفاً ثبت می‌شود؛ هنوز اعتبارسنجی مستقل ندارد"
        }

        // The paper journal currently evaluates a fixed 15-minute signal horizon.
        // Never compare its outcomes with a multi-timeframe coverage label.
        val historical = historicalEdge(context, side, PAPER_OUTCOME_TIMEFRAME)
        if (historical.count >= MIN_EDGE_SAMPLES) {
            if (historical.averageNetPct <= 0.0) {
                score -= 18
                reasons += "مزیت خالص تاریخی این جهت/تایم‌فریم منفی است"
            } else if (historical.winRate >= 0.58 && historical.averageNetPct > 0.0) {
                score += 5
                reasons += "نتایج کاغذی خالص این گروه مثبت بوده"
            } else if (historical.winRate < 0.42) {
                score -= 10
                reasons += "نرخ موفقیت تاریخی این گروه ضعیف است"
            }
        } else {
            reasons += "نمونهٔ کافی برای اثبات مزیت تاریخی وجود ندارد"
        }

        val lossCooldownActive = historical.lossStreak >= MAX_CONSECUTIVE_PAPER_LOSSES &&
            historical.lastLossAt > 0L && System.currentTimeMillis() - historical.lastLossAt < LOSS_STREAK_COOLDOWN_MS
        if (lossCooldownActive) {
            score -= 25
            reasons += "سه زیان پیاپی در سوابق کاغذی؛ دوره استراحت چهار ساعته فعال است"
        }
        val quality = score.coerceIn(0, 100)
        val hardGates = signal.technicalDirection.equals(side, true) &&
            signal.confidence >= 70 &&
            (signal.consensus?.agreement ?: 0) >= 58 &&
            adx >= 18.0 && relativeVolume >= 0.75 &&
            atrPct.isFinite() && atrPct in 0.2..6.0 &&
            rr != null && rr >= 1.5 &&
            signal.price.isFinite() && signal.price > 0.0 &&
            (!rangeRegime || adx >= 18.0) &&
            (historical.count < MIN_EDGE_SAMPLES || historical.averageNetPct > 0.0) &&
            !lossCooldownActive

        val allowed = hardGates && quality >= MIN_QUALITY
        val decision = Decision(
            allowed, quality, regime, historical.count,
            historical.takeIf { it.count >= MIN_EDGE_SAMPLES }?.winRate,
            historical.takeIf { it.count >= MIN_EDGE_SAMPLES }?.averageNetPct,
            reasons.distinct()
        )
        persist(context, signal, decision)
        return decision
    }

    private data class Edge(val count: Int, val winRate: Double, val averageNetPct: Double, val lossStreak: Int, val lastLossAt: Long)

    private fun historicalEdge(context: Context, side: String, timeframe: String): Edge {
        val records = runCatching { WolfSignalOutcomeJournal.snapshot(context).records }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.closed && it.side.equals(side, true) && it.timeframe.equals(timeframe, true) }
            .sortedBy { it.closedAt }
            .takeLast(120)
            .toList()
        if (records.isEmpty()) return Edge(0, 0.5, 0.0, 0, 0L)
        val wins = records.count { it.pnlPct > 0.0 }
        val newestFirst = records.asReversed()
        val lossStreak = newestFirst.takeWhile { it.pnlPct < 0.0 }.size
        val lastLossAt = newestFirst.firstOrNull()?.takeIf { it.pnlPct < 0.0 }?.closedAt ?: 0L
        return Edge(records.size, wins.toDouble() / records.size,
            records.map { it.pnlPct }.average(), lossStreak, lastLossAt)
    }

    private fun rewardRisk(signal: MarketSignal): Double? {
        val entry = signal.entry
        val stop = signal.stop
        val target = signal.tp1
        if (!entry.isFinite() || !stop.isFinite() || !target.isFinite() ||
            entry <= 0.0 || stop <= 0.0 || target <= 0.0) return null
        val risk = abs(entry - stop)
        if (risk <= 0.0) return null
        return abs(target - entry) / risk
    }

    private fun persist(context: Context, signal: MarketSignal, decision: Decision) {
        context.getSharedPreferences("zoro_living_trader_brain", Context.MODE_PRIVATE).edit()
            .putLong("last_decision_at", System.currentTimeMillis())
            .putString("last_symbol", signal.symbol)
            .putString("last_side", signal.direction)
            .putInt("last_score", decision.score)
            .putBoolean("last_allowed", decision.allowed)
            .putString("last_regime", decision.regime)
            .putString("last_reasons", decision.reasons.joinToString(" • ").take(1800))
            .putInt("last_edge_samples", decision.sampleCount)
            .putFloat("last_win_rate", (decision.estimatedWinRate ?: 0.0).toFloat())
            .putFloat("last_average_net_pct", (decision.averageNetReturnPct ?: 0.0).toFloat())
            .apply()
    }

    // Capital-growth principles: protect the bankroll, wait for positive net expectancy,
    // never martingale, and pause after a losing streak instead of forcing recovery trades.
    private const val MIN_EDGE_SAMPLES = 24
    private const val MIN_QUALITY = 74
    private const val MAX_CONSECUTIVE_PAPER_LOSSES = 3
    private const val LOSS_STREAK_COOLDOWN_MS = 4L * 60L * 60L * 1000L
    private const val PAPER_OUTCOME_TIMEFRAME = "15m"
}


@androidx.compose.runtime.Composable
internal fun ZoroLivingTraderBrainPanel(context: Context) {
    var snapshot by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(readBrainSnapshot(context))
    }
    androidx.compose.runtime.LaunchedEffect(context) {
        while (true) {
            snapshot = readBrainSnapshot(context)
            kotlinx.coroutines.delay(2500)
        }
    }
    val gold = androidx.compose.ui.graphics.Color(0xFFE8B85B)
    val muted = androidx.compose.ui.graphics.Color(0xFFB8B0A0)
    androidx.compose.material3.Surface(
        color = androidx.compose.ui.graphics.Color(0xFF101010),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, gold.copy(alpha = 0.45f)),
        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
    ) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.padding(14.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp)
        ) {
            androidx.compose.material3.Text("مغز زنده معامله‌گر ZORO", color = gold, fontSize = 14.sp)
            androidx.compose.material3.Text(
                if (snapshot.symbol.isBlank()) "در انتظار بررسی نامزدهای بازار؛ تصمیمی برای معامله صادر نشده است."
                else "${snapshot.symbol} • ${snapshot.side} • رژیم: ${snapshot.regime}",
                color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp
            )
            androidx.compose.material3.Text(
                if (snapshot.symbol.isBlank()) "پس از اجرای موتور ارزیابی، امتیاز و دلایل تصمیم در این بخش نمایش داده می‌شود."
                else "امتیاز مغز: ${snapshot.score}/100 • ${if (snapshot.allowed) "عبور از فیلتر مغز" else "رد شده توسط فیلتر مغز"}",
                color = if (snapshot.allowed) androidx.compose.ui.graphics.Color(0xFF79D6A0) else muted,
                fontSize = 11.sp
            )
            if (snapshot.sampleCount > 0) {
                androidx.compose.material3.Text(
                    "سوابق خالص هم‌جهت/تایم‌فریم: ${snapshot.sampleCount} نمونه • برد: ${snapshot.winRate}% • میانگین خالص: ${snapshot.averageNetPct}%",
                    color = muted, fontSize = 10.sp
                )
            }
            if (snapshot.reasons.isNotBlank()) {
                androidx.compose.material3.Text(snapshot.reasons, color = muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
            androidx.compose.material3.Text(
                "مغز فقط نامزدها را رتبه‌بندی و فیلتر می‌کند؛ مجوز حساب، اندازه پوزیشن، حدضرر/حدسود و توقف اضطراری همچنان مستقل و اجباری‌اند.",
                color = muted, fontSize = 9.sp, lineHeight = 13.sp
            )
        }
    }
}

private data class BrainSnapshot(
    val symbol: String = "",
    val side: String = "",
    val regime: String = "—",
    val score: Int = 0,
    val allowed: Boolean = false,
    val sampleCount: Int = 0,
    val winRate: String = "—",
    val averageNetPct: String = "—",
    val reasons: String = ""
)

private fun readBrainSnapshot(context: Context): BrainSnapshot {
    val p = context.getSharedPreferences("zoro_living_trader_brain", Context.MODE_PRIVATE)
    val winRate = if (p.contains("last_win_rate")) p.getFloat("last_win_rate", 0f).toString() else "—"
    val average = if (p.contains("last_average_net_pct")) p.getFloat("last_average_net_pct", 0f).toString() else "—"
    return BrainSnapshot(
        symbol = p.getString("last_symbol", "") ?: "",
        side = p.getString("last_side", "") ?: "",
        regime = p.getString("last_regime", "—") ?: "—",
        score = p.getInt("last_score", 0),
        allowed = p.getBoolean("last_allowed", false),
        sampleCount = p.getInt("last_edge_samples", 0),
        winRate = winRate,
        averageNetPct = average,
        reasons = p.getString("last_reasons", "") ?: ""
    )
}
