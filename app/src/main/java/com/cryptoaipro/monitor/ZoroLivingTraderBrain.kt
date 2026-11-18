package com.cryptoaipro.monitor

import android.content.Context
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

        val historical = historicalEdge(context, side, signal.tradeTimeframe)
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

        val quality = score.coerceIn(0, 100)
        val hardGates = signal.technicalDirection.equals(side, true) &&
            signal.confidence >= 70 &&
            (signal.consensus?.agreement ?: 0) >= 58 &&
            adx >= 18.0 && relativeVolume >= 0.75 &&
            atrPct.isFinite() && atrPct in 0.2..6.0 &&
            rr != null && rr >= 1.5 &&
            signal.price.isFinite() && signal.price > 0.0 &&
            (!rangeRegime || adx >= 18.0) &&
            (historical.count < MIN_EDGE_SAMPLES || historical.averageNetPct > 0.0)

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

    private data class Edge(val count: Int, val winRate: Double, val averageNetPct: Double)

    private fun historicalEdge(context: Context, side: String, timeframe: String): Edge {
        val records = runCatching { WolfSignalOutcomeJournal.snapshot(context).records }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.closed && it.side.equals(side, true) && it.timeframe.equals(timeframe, true) }
            .takeLast(120)
            .toList()
        if (records.isEmpty()) return Edge(0, 0.5, 0.0)
        val wins = records.count { it.pnlPct > 0.0 }
        return Edge(records.size, wins.toDouble() / records.size,
            records.map { it.pnlPct }.average())
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
            .apply()
    }

    private const val MIN_EDGE_SAMPLES = 24
    private const val MIN_QUALITY = 74
}
