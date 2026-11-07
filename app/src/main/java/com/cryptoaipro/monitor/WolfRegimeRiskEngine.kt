package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Market-regime and structural-break diagnostics.
 * Uses EWMA volatility, robust MAD outlier checks and a two-sided CUSUM.
 * It is a risk gate, not a predictive oracle or a trained hidden-Markov model.
 */
internal object WolfRegimeRiskEngine {
    data class Regime(
        val label: String,
        val ewmaVolatility: Double,
        val volatilityRatio: Double,
        val robustShockScore: Double,
        val positiveCusum: Double,
        val negativeCusum: Double,
        val structuralBreak: Boolean,
        val directionalEfficiency: Double
    )

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
    }

    private fun sampleStd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    private fun ewmaVolatility(returns: List<Double>, lambda: Double = 0.94): Double {
        if (returns.size < 2) return 0.0
        var variance = returns.take(10).let { sampleStd(it).let { s -> s * s } }
        for (r in returns.drop(10)) variance = lambda * variance + (1.0 - lambda) * r * r
        return sqrt(variance.coerceAtLeast(0.0))
    }

    private fun cusum(returns: List<Double>, threshold: Double): Pair<Double, Double> {
        val baseline = median(returns)
        val scale = (1.4826 * median(returns.map { abs(it - baseline) })).coerceAtLeast(1e-7)
        var positive = 0.0
        var negative = 0.0
        for (r in returns.takeLast(48)) {
            val z = (r - baseline) / scale
            positive = max(0.0, positive + z - 0.5)
            negative = max(0.0, negative - z - 0.5)
        }
        return positive to negative
    }

    fun analyze(candles: List<DoubleArray>): Regime? {
        val closes = candles.filter { it.size >= 4 && it[3].isFinite() && it[3] > 0.0 }.map { it[3] }
        if (closes.size < 80) return null
        val returns = closes.zipWithNext { a, b -> ln(b / a) }.filter { it.isFinite() }
        if (returns.size < 64) return null
        val recent = returns.takeLast(16)
        val baseline = returns.takeLast(64)
        val recentVol = ewmaVolatility(recent)
        val baselineVol = ewmaVolatility(baseline).coerceAtLeast(1e-8)
        val ratio = (recentVol / baselineVol).coerceIn(0.0, 8.0)
        val center = median(baseline)
        val mad = (1.4826 * median(baseline.map { abs(it - center) })).coerceAtLeast(1e-8)
        val lastShock = abs((returns.last() - center) / mad)
        val (pos, neg) = cusum(returns, 8.0)
        val breakDetected = pos >= 8.0 || neg >= 8.0
        val pathLength = recent.sumOf { abs(it) }.coerceAtLeast(1e-9)
        val netMove = abs(recent.sum())
        val efficiency = (netMove / pathLength).coerceIn(0.0, 1.0)
        val label = when {
            breakDetected -> "تغییر ساختاری احتمالی"
            ratio >= 1.8 && efficiency < 0.35 -> "نوسان آشفته"
            ratio >= 1.8 -> "نوسان بالا"
            ratio <= 0.62 && efficiency < 0.30 -> "فشردگی / رنج"
            efficiency >= 0.62 -> "رونددار"
            else -> "متعادل"
        }
        return Regime(label, recentVol, ratio, lastShock, pos, neg, breakDetected, efficiency)
    }

    /** Penalizes unstable or structurally broken environments; never creates a direction. */
    fun apply(signal: MarketSignal): MarketSignal {
        val r = analyze(signal.candles) ?: return signal
        if (signal.direction != "LONG_BIAS" && signal.direction != "SHORT_BIAS") {
            return signal.copy(reason = signal.reason + " | رژیم بازار: " + r.label)
        }
        var penalty = 0
        val warnings = mutableListOf<String>()
        if (r.structuralBreak) {
            penalty += 12
            warnings += "CUSUM تغییر ساختاری احتمالی را شناسایی کرد"
        }
        if (r.volatilityRatio >= 2.2) {
            penalty += 8
            warnings += "نوسان EWMA نسبت به خط مبنا جهش کرده است"
        } else if (r.volatilityRatio >= 1.7) {
            penalty += 4
            warnings += "نوسان کوتاه‌مدت بالاتر از حالت معمول است"
        }
        if (r.robustShockScore >= 5.0) {
            penalty += 5
            warnings += "آخرین بازده یک شوک آماری مقاوم است"
        }
        if (r.directionalEfficiency < 0.18 && r.volatilityRatio > 1.2) {
            penalty += 5
            warnings += "حرکت رفت‌وبرگشتی با کارایی جهت پایین"
        }
        val veto = penalty >= 20
        val note = " | فیلتر رژیم: ${r.label}، EWMA-vol=${"%.3f".format(r.ewmaVolatility)}، نسبت نوسان=${"%.2f".format(r.volatilityRatio)}، شوک مقاوم=${"%.1f".format(r.robustShockScore)} MAD، کارایی جهت=${"%.2f".format(r.directionalEfficiency)}"
        val caution = if (warnings.isEmpty()) "؛ شکست ساختاری یا شوک پرریسک ثبت نشد" else "؛ " + warnings.joinToString("، ")
        return signal.copy(
            direction = if (veto) "NEUTRAL" else signal.direction,
            confidence = (signal.confidence - penalty).coerceIn(0, 95),
            score = if (veto) 0 else (signal.score - penalty / 2).coerceIn(-100, 100),
            reason = signal.reason + note + caution
        )
    }
}
