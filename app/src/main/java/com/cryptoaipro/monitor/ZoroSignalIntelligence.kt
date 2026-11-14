package com.cryptoaipro.monitor

import kotlin.math.abs

/**
 * Deterministic, explainable post-model quality review.
 *
 * This layer can reduce confidence when evidence is weak or contradictory, but it
 * never manufactures a direction and never increases confidence. It is a safety
 * and ranking layer, not a claim of predictive accuracy.
 */
internal object ZoroSignalIntelligence {
    data class Assessment(
        val quality: Int,
        val penalty: Int,
        val warnings: List<String>
    )

    fun assess(signal: MarketSignal): Assessment {
        val warnings = mutableListOf<String>()
        var quality = 50
        val side = signal.direction.uppercase()
        val directional = side == "LONG_BIAS" || side == "SHORT_BIAS"

        if (!directional) {
            return Assessment(0, 0, listOf("جهت قطعی ندارد؛ بدون ارتقای هوشمند"))
        }

        val technical = signal.technical
        val technicalSide = signal.technicalDirection.uppercase()
        if (technicalSide == side) quality += 12
        else if (technicalSide != "NEUTRAL" && technicalSide.isNotBlank()) {
            quality -= 12
            warnings += "تعارض جهت تحلیل تکنیکال"
        } else {
            quality -= 3
            warnings += "تأیید تکنیکال محدود"
        }

        val neuralSide = signal.neuralDirection.uppercase()
        if (neuralSide == side) quality += 10
        else if (neuralSide == "LONG_BIAS" || neuralSide == "SHORT_BIAS") {
            quality -= 14
            warnings += "تعارض نظر شبکه عصبی"
        } else {
            quality -= 2
            warnings += "نظر شبکه عصبی خنثی"
        }

        val adx = technical.adx.takeIf { it.isFinite() } ?: 0.0
        when {
            adx >= 25.0 -> quality += 8
            adx < 15.0 -> { quality -= 6; warnings += "قدرت روند پایین" }
            else -> quality += 2
        }

        val relativeVolume = technical.relativeVolume.takeIf { it.isFinite() } ?: 0.0
        when {
            relativeVolume >= 1.5 -> quality += 7
            relativeVolume < 0.7 -> { quality -= 5; warnings += "حجم تأییدکننده ضعیف" }
            else -> quality += 2
        }

        val rsi = signal.rsi.takeIf { it.isFinite() } ?: 50.0
        if (side == "LONG_BIAS" && rsi >= 78.0) {
            quality -= 8; warnings += "احتمال ورود دیرهنگام لانگ"
        } else if (side == "SHORT_BIAS" && rsi <= 22.0) {
            quality -= 8; warnings += "احتمال ورود دیرهنگام شورت"
        } else if ((side == "LONG_BIAS" && rsi in 45.0..68.0) ||
            (side == "SHORT_BIAS" && rsi in 32.0..55.0)) {
            quality += 5
        }

        val rr = rewardRisk(signal)
        if (rr != null) {
            when {
                rr >= 2.0 -> quality += 8
                rr >= 1.5 -> quality += 3
                rr < 1.0 -> { quality -= 15; warnings += "نسبت سود به زیان نامناسب" }
                else -> { quality -= 6; warnings += "نسبت سود به زیان ضعیف" }
            }
        } else {
            quality -= 10
            warnings += "قیمت ورود یا حدضرر نامعتبر"
        }

        val atrPct = technical.atrPercent.takeIf { it.isFinite() } ?: 0.0
        if (atrPct > 8.0) {
            quality -= 8; warnings += "نوسان بسیار بالا"
        } else if (atrPct in 0.2..4.0) quality += 3

        if (technical.liquidityStrength >= 65) quality += 4
        else if (technical.liquidityStrength in 1..24) {
            quality -= 4; warnings += "تأیید نقدینگی ضعیف"
        }

        val uncertainty = signal.neuralUncertainty
        if (uncertainty.highDisagreement) {
            quality -= 8; warnings += "اختلاف زیاد بین اعضای شبکه عصبی"
        }

        val finalQuality = quality.coerceIn(0, 100)
        // Only reduce the displayed confidence; this layer never boosts a signal.
        val qualityPenalty = when {
            finalQuality < 30 -> 18
            finalQuality < 42 -> 12
            finalQuality < 52 -> 7
            finalQuality < 62 -> 3
            else -> 0
        }
        val evidencePenalty =
            (if (technicalSide != side && technicalSide != "NEUTRAL") 6 else 0) +
            (if (neuralSide == "LONG_BIAS" || neuralSide == "SHORT_BIAS") {
                if (neuralSide != side) 8 else 0
            } else 0) +
            (if (rr == null) 8 else if (rr < 1.0) 10 else if (rr < 1.5) 5 else 0) +
            (if (uncertainty.highDisagreement) 5 else 0)
        val penalty = maxOf(qualityPenalty, evidencePenalty).coerceIn(0, 20)
        return Assessment(finalQuality, penalty, warnings.distinct())
    }

    fun enrich(signals: List<MarketSignal>): List<MarketSignal> = signals.map { signal ->
        val assessment = assess(signal)
        val note = " | ارزیابی کیفیت ZORO: ${assessment.quality}/100" +
            if (assessment.warnings.isEmpty()) " • بدون هشدار ساختاری" else
                " • " + assessment.warnings.joinToString("، ")
        signal.copy(
            confidence = (signal.confidence - assessment.penalty).coerceIn(0, 100),
            reason = (signal.reason + note).take(1200)
        )
    }

    private fun rewardRisk(signal: MarketSignal): Double? {
        val entry = signal.entry
        val stop = signal.stop
        val target = signal.tp1
        if (!entry.isFinite() || !stop.isFinite() || !target.isFinite() ||
            entry <= 0.0 || stop <= 0.0 || target <= 0.0) return null
        val risk = abs(entry - stop)
        val reward = abs(target - entry)
        if (risk <= 0.0) return null
        return reward / risk
    }
}
