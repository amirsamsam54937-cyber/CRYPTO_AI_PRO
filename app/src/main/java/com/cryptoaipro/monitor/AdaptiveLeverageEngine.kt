package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Signal-adaptive leverage recommendation.
 * Leverage is a margin-efficiency choice, never a substitute for risk-based position sizing.
 * Live execution must pass the contract's verified XT maximum; an unknown venue limit fails closed.
 */
internal object AdaptiveLeverageEngine {
    data class Input(
        val direction: String,
        val confidence: Int,
        val consensusStrength: Int,
        val neuralStrength: Int,
        val neuralValidated: Boolean,
        val neuralDisagreement: Boolean,
        val outOfDistribution: Boolean,
        val entry: Double,
        val stop: Double,
        val tp1: Double,
        val atrPercent: Double,
        val venueMaxLeverage: Int?,
        val maintenanceMarginRate: Double = 0.01,
        val simulation: Boolean = false,
        val simulationMaxLeverage: Int = 20
    )

    data class Decision(
        val leverage: Int,
        val strength: Int,
        val stopPercent: Double,
        val rewardRisk: Double,
        val reason: String
    )

    fun recommend(input: Input): Decision {
        val stopPct = if (input.entry.isFinite() && input.entry > 0.0 && input.stop.isFinite())
            abs(input.entry - input.stop) / input.entry * 100.0 else Double.NaN
        val risk = abs(input.entry - input.stop)
        val reward = abs(input.tp1 - input.entry)
        val rr = if (risk > 0.0 && reward.isFinite()) reward / risk else 0.0
        val venueCap = when {
            input.simulation -> input.simulationMaxLeverage.coerceIn(1, 50)
            input.venueMaxLeverage != null -> input.venueMaxLeverage.coerceIn(1, 125)
            else -> 1
        }
        val geometryValid = input.direction in setOf("LONG_BIAS", "SHORT_BIAS") &&
            input.entry.isFinite() && input.entry > 0.0 && input.stop.isFinite() &&
            input.tp1.isFinite() && stopPct.isFinite() && stopPct > 0.0 && rr >= 1.0
        if (!geometryValid) return Decision(1, 0, stopPct, rr,
            "هندسه ورود/حدضرر/هدف معتبر نیست؛ ارتقای اهرم ممنوع")

        val neuralContribution = if (input.neuralValidated && !input.neuralDisagreement &&
            !input.outOfDistribution) input.neuralStrength.coerceIn(0, 100) else 0
        val technical = input.consensusStrength.coerceIn(0, 100)
        val confidence = input.confidence.coerceIn(0, 100)
        val strength = (confidence * 0.35 + technical * 0.35 + neuralContribution * 0.30)
            .roundToInt().coerceIn(0, 100)

        val rrFactor = when {
            rr >= 3.0 -> 1.10
            rr >= 2.0 -> 1.0
            rr >= 1.5 -> 0.85
            else -> 0.65
        }
        val volatility = input.atrPercent.takeIf { it.isFinite() && it >= 0.0 } ?: 99.0
        val volatilityFactor = when {
            volatility >= 8.0 -> 0.35
            volatility >= 5.0 -> 0.55
            volatility >= 3.0 -> 0.75
            volatility >= 1.5 -> 0.90
            else -> 1.0
        }
        val evidenceFactor = if (input.neuralValidated && !input.neuralDisagreement &&
            !input.outOfDistribution) 1.0 else 0.70

        // Nonlinear signal-strength ladder; no artificial 3x ceiling.
        val raw = (1.0 + (strength / 100.0).let { it * it } * 19.0 *
            rrFactor * volatilityFactor * evidenceFactor).roundToInt()
        val maintenance = input.maintenanceMarginRate.takeIf { it.isFinite() && it in 0.0..0.25 } ?: 0.25
        val liquidationBuffer = 0.005
        val liquidationSafeCap = (1.0 / (stopPct / 100.0 + maintenance + liquidationBuffer))
            .toInt().coerceAtLeast(1)
        val leverage = raw.coerceIn(1, minOf(venueCap, liquidationSafeCap))
        val rrText = "%.2f".format(java.util.Locale.US, rr)
        val atrText = "%.2f".format(java.util.Locale.US, volatility)
        val reason = "قدرت تطبیقی $strength/100 • R:R $rrText • نوسان ATR $atrText٪" +
            if (!input.neuralValidated) " • نظر عصبی هنوز اعتبار معاملاتی ندارد" else " • اجماع عصبی معتبر لحاظ شد"
        return Decision(leverage, strength, stopPct, rr, reason)
    }
}
