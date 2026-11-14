package com.cryptoaipro.monitor

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Describes disagreement and uncertainty among the ten neural specialists.
 * These are diagnostics, not calibrated probabilities of profit or trade authorization.
 */
object WolfEnsembleUncertainty {
    private const val EXPECTED_MEMBERS = 10
    private const val LONG_THRESHOLD = 0.55
    private const val SHORT_THRESHOLD = 0.45

    data class Summary(
        val available: Boolean,
        val upVotes: Int,
        val downVotes: Int,
        val neutralVotes: Int,
        val directionalVotes: Int,
        val agreementRatio: Double,
        val meanProbability: Double,
        val standardDeviation: Double,
        val normalizedEntropy: Double,
        val highDisagreement: Boolean,
        val assessment: String
    )

    fun analyze(probabilities: List<Double>): Summary {
        if (probabilities.size != EXPECTED_MEMBERS ||
            probabilities.any { !it.isFinite() || it !in 0.0..1.0 }
        ) return unavailable()

        val up = probabilities.count { it >= LONG_THRESHOLD }
        val down = probabilities.count { it <= SHORT_THRESHOLD }
        val neutral = EXPECTED_MEMBERS - up - down
        val directional = up + down
        val agreement = if (directional == 0) 0.0 else maxOf(up, down).toDouble() / directional
        val mean = probabilities.average()
        val variance = probabilities.sumOf { (it - mean) * (it - mean) } / EXPECTED_MEMBERS
        val deviation = sqrt(variance)
        val entropy = probabilities.map { p ->
            val safe = p.coerceIn(1e-9, 1.0 - 1e-9)
            -(safe * ln(safe) + (1.0 - safe) * ln(1.0 - safe)) / ln(2.0)
        }.average().coerceIn(0.0, 1.0)

        val assessment = when {
            directional < 5 -> "رأی جهت‌دار ناکافی"
            up == down -> "تقسیم آرا"
            agreement < 0.70 -> "اختلاف بالای متخصصان"
            entropy >= 0.90 -> "ابهام بالا در خروجی شبکه‌ها"
            agreement >= 0.80 && deviation <= 0.15 -> "همگرایی مناسب متخصصان"
            else -> "همگرایی متوسط"
        }
        val disagreement = directional < 5 || up == down || agreement < 0.70 ||
            entropy >= 0.90 || deviation >= 0.22

        return Summary(
            available = true,
            upVotes = up,
            downVotes = down,
            neutralVotes = neutral,
            directionalVotes = directional,
            agreementRatio = agreement,
            meanProbability = mean,
            standardDeviation = deviation,
            normalizedEntropy = entropy,
            highDisagreement = disagreement,
            assessment = assessment
        )
    }

    private fun unavailable() = Summary(
        available = false,
        upVotes = 0,
        downVotes = 0,
        neutralVotes = 0,
        directionalVotes = 0,
        agreementRatio = 0.0,
        meanProbability = 0.5,
        standardDeviation = 0.0,
        normalizedEntropy = 1.0,
        highDisagreement = true,
        assessment = "داده معتبر برای ممیزی ۱۰ شبکه کافی نیست"
    )
}
