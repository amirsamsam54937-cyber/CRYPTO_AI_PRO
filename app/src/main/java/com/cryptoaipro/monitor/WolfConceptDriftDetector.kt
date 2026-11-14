package com.cryptoaipro.monitor

import kotlin.math.ln

/**
 * Prequential performance monitor for chronological neural forecasts.
 * Compares a trailing window with the immediately preceding reference window.
 * A warning is diagnostic only; it never changes weights or authorizes signals.
 */
internal object WolfConceptDriftDetector {
    data class Report(
        val available: Boolean,
        val referenceSamples: Int,
        val recentSamples: Int,
        val referenceBrier: Double,
        val recentBrier: Double,
        val brierIncrease: Double,
        val referenceLogLoss: Double,
        val recentLogLoss: Double,
        val driftDetected: Boolean,
        val status: String
    )

    fun analyze(
        probabilities: List<Double>,
        labels: List<Int>,
        referenceWindow: Int = 40,
        recentWindow: Int = 20
    ): Report {
        require(referenceWindow >= 10 && recentWindow >= 10)
        val count = minOf(probabilities.size, labels.size)
        if (count < referenceWindow + recentWindow) return unavailable(referenceWindow, recentWindow)
        val p = probabilities.takeLast(count)
        val y = labels.takeLast(count)
        if (p.any { !it.isFinite() || it !in 0.0..1.0 } || y.any { it != 0 && it != 1 }) {
            return unavailable(referenceWindow, recentWindow)
        }
        val split = count - recentWindow
        val referenceP = p.subList(split - referenceWindow, split)
        val referenceY = y.subList(split - referenceWindow, split)
        val recentP = p.subList(split, count)
        val recentY = y.subList(split, count)

        fun brier(ps: List<Double>, ys: List<Int>) =
            ps.indices.map { (ps[it] - ys[it]) * (ps[it] - ys[it]) }.average()

        fun logLoss(ps: List<Double>, ys: List<Int>): Double =
            ps.indices.map { i ->
                val safe = ps[i].coerceIn(1e-6, 1.0 - 1e-6)
                -(ys[i] * ln(safe) + (1 - ys[i]) * ln(1.0 - safe))
            }.average()

        val baseBrier = brier(referenceP, referenceY)
        val recentScore = brier(recentP, recentY)
        val increase = recentScore - baseBrier
        val baseLog = logLoss(referenceP, referenceY)
        val recentLog = logLoss(recentP, recentY)
        val threshold = maxOf(0.025, baseBrier * 0.20)
        val drift = increase >= threshold && recentScore > baseBrier
        val status = when {
            drift -> "هشدار افت عملکرد؛ بازار یا رابطهٔ داده‌ها تغییر کرده است"
            increase <= -0.025 -> "عملکرد اخیر بهتر از دورهٔ مرجع"
            else -> "افت معنادار عملکرد شناسایی نشد"
        }
        return Report(
            true, referenceWindow, recentWindow, baseBrier, recentScore, increase,
            baseLog, recentLog, drift, status
        )
    }

    private fun unavailable(reference: Int, recent: Int) = Report(
        false, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, false,
        "نمونهٔ زمانی کافی برای تشخیص تغییر رفتار بازار وجود ندارد"
    )
}
