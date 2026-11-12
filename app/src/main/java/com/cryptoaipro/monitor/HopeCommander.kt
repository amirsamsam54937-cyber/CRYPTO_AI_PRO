package com.cryptoaipro.monitor

import kotlin.math.abs

/**
 * Central decision authority for the signal pipeline.
 * Neural output receives directional weight only after the ensemble passes
 * chronological holdout validation. Unvalidated neural opinions remain visible
 * but cannot authorize a live directional signal.
 */
internal object HopeCommander {
    data class Decision(
        val direction: String,
        val score: Int,
        val confidence: Int,
        val neuralInfluence: Int,
        val status: String,
        val explanation: String
    )

    fun decide(signal: MarketSignal, neuralValidated: Boolean): Decision {
        val technicalDirection = signal.technicalDirection
        val technicalSign = when (technicalDirection) {
            "LONG_BIAS" -> 1.0
            "SHORT_BIAS" -> -1.0
            else -> 0.0
        }
        val consensus = signal.consensus
        val technicalEvidence = if (consensus != null) {
            (consensus.score * 100.0).coerceIn(-100.0, 100.0) *
                (consensus.agreement / 100.0)
        } else technicalSign * signal.confidence.toDouble() * 0.55

        val neuralSign = when (signal.neuralDirection) {
            "LONG_BIAS" -> 1.0
            "SHORT_BIAS" -> -1.0
            else -> 0.0
        }
        val neuralStrength = (abs(signal.neuralScore - 50) * 2).coerceIn(0, 100)
        val neuralEvidence = if (neuralValidated && neuralSign != 0.0)
            neuralSign * neuralStrength.toDouble() else 0.0

        // Data-quality, market-structure and execution geometry are hard authority gates.
        val longGeometry = signal.stop < signal.entry && signal.tp1 > signal.entry
        val shortGeometry = signal.stop > signal.entry && signal.tp1 < signal.entry
        val geometryValid = signal.entry.isFinite() && signal.stop.isFinite() &&
            signal.tp1.isFinite() && signal.entry > 0.0 &&
            (longGeometry || shortGeometry)
        if (!geometryValid) return Decision("NEUTRAL", 0, minOf(signal.confidence, 35), 0,
            "VETO_INVALID_GEOMETRY", "فرمانده: هندسه ورود، حدضرر یا هدف نامعتبر است")

        val riskDistance = abs(signal.entry - signal.stop)
        val rewardDistance = abs(signal.tp1 - signal.entry)
        if (riskDistance <= 0.0 || rewardDistance / riskDistance < 1.8)
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 39), 0,
                "VETO_REWARD_RISK", "فرمانده: نسبت سود به زیان کمتر از حداقل ۱٫۸ است")

        if (technicalSign == 0.0 || signal.direction == "NEUTRAL")
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 45), 0,
                "ABSTAIN_NO_TECHNICAL_DIRECTION", "فرمانده: جهت تکنیکال شواهد کافی ندارد")

        val neuralWeight = if (neuralValidated) 0.25 else 0.0
        val technicalWeight = 1.0 - neuralWeight
        val combined = technicalEvidence * technicalWeight + neuralEvidence * neuralWeight
        val technicalConflict = technicalEvidence * technicalSign < 0.0
        val neuralConflict = neuralValidated && neuralSign != 0.0 && neuralSign != technicalSign
        val agreement = consensus?.agreement ?: 0
        val adx = signal.technical.adx
        val volume = signal.technical.relativeVolume

        if (neuralConflict && neuralStrength >= 65 && agreement < 78)
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 48), neuralStrength,
                "ABSTAIN_NEURAL_CONFLICT", "فرمانده: اختلاف معنادار شبکه عصبی و تکنیکال؛ سیگنال متوقف شد")

        if (technicalConflict || abs(combined) < 18.0 || agreement < 55 || adx < 14.0 || volume < 0.55)
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 49), neuralStrength,
                "ABSTAIN_WEAK_EVIDENCE", "فرمانده: شواهد، اجماع یا مشارکت بازار برای صدور سیگنال کافی نیست")

        val chosen = if (combined > 0.0) "LONG_BIAS" else "SHORT_BIAS"
        if (chosen != technicalDirection)
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 45), neuralStrength,
                "ABSTAIN_DIRECTION_CONFLICT", "فرمانده: جهت نهایی با ساختار تکنیکال همسو نیست")

        val confidenceAdjustment = if (neuralValidated) {
            when {
                neuralConflict -> -minOf(8, neuralStrength / 12)
                neuralSign == technicalSign && neuralStrength >= 60 -> minOf(5, neuralStrength / 18)
                else -> -2
            }
        } else 0
        val finalConfidence = (signal.confidence + confidenceAdjustment).coerceIn(0, 95)
        val neuralImpact = if (neuralValidated) confidenceAdjustment else 0
        val status = if (neuralValidated) "COMMANDER_TECH_AI_FUSION" else "COMMANDER_TECH_ONLY_AI_UNVALIDATED"
        val explanation = if (neuralValidated)
            "فرمانده: تکنیکال ۷۵٪ + شبکه عصبی ۲۵٪؛ اجماع $agreement٪؛ اثر عصبی $neuralImpact امتیاز"
        else "فرمانده: مدل عصبی هنوز از آزمون مستقل عبور نکرده؛ تصمیم فقط بر پایه تکنیکال معتبر است"
        return Decision(chosen, signal.score, finalConfidence, neuralImpact, status, explanation)
    }
}
