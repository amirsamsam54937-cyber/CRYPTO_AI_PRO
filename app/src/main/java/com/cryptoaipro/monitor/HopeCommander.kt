package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.roundToInt

internal object HopeCommander {
    data class Decision(
        val direction: String, val score: Int, val confidence: Int,
        val neuralInfluence: Int, val status: String, val explanation: String
    )

    fun decide(signal: MarketSignal, neuralValidated: Boolean): Decision {
        val technicalDirection = signal.technicalDirection
        val technicalSign = when (technicalDirection) {
            "LONG_BIAS" -> 1.0
            "SHORT_BIAS" -> -1.0
            else -> 0.0
        }
        val consensus = signal.consensus
        val directionalVotes = consensus?.votes.orEmpty().filter {
            it.direction == "LONG" || it.direction == "SHORT"
        }
        val totalWeight = directionalVotes.sumOf { it.weight.coerceAtLeast(0.0) }
        val alignedWeight = directionalVotes.filter {
            (it.direction == "LONG" && technicalSign > 0.0) ||
                (it.direction == "SHORT" && technicalSign < 0.0)
        }.sumOf { it.weight.coerceAtLeast(0.0) }
        val technicalAgreement = if (totalWeight > 0.0) alignedWeight / totalWeight else 0.0
        val weightedTechnicalVote = if (totalWeight > 0.0) directionalVotes.sumOf {
            val signed = if (it.direction == "LONG") abs(it.score) else -abs(it.score)
            signed * it.weight.coerceAtLeast(0.0)
        } / totalWeight else 0.0
        val aggregateTechnical = (consensus?.score ?: technicalSign * 0.35).coerceIn(-1.0, 1.0)
        val technicalEvidence = ((weightedTechnicalVote * 0.65 + aggregateTechnical * 0.35) * 100.0)
            .coerceIn(-100.0, 100.0)

        val memberProbabilities = signal.neuralMemberProbabilities.filter { it.isFinite() && it in 0.0..1.0 }
        val neuralMembersMean = memberProbabilities.map { (it - 0.5) * 200.0 }.average()
            .takeIf { it.isFinite() } ?: 0.0
        val neuralEnsembleSigned = (signal.neuralScore - 50) * 2.0
        val neuralEvidence = (neuralMembersMean * 0.60 + neuralEnsembleSigned * 0.40).coerceIn(-100.0, 100.0)
        val neuralLong = memberProbabilities.count { it >= 0.55 }
        val neuralShort = memberProbabilities.count { it <= 0.45 }
        val neuralNeutral = memberProbabilities.size - neuralLong - neuralShort
        val neuralAligned = if (technicalSign > 0.0) neuralLong else if (technicalSign < 0.0) neuralShort else 0
        val neuralOpposed = if (technicalSign > 0.0) neuralShort else if (technicalSign < 0.0) neuralLong else 0
        val neuralSign = when {
            neuralEvidence >= 10.0 -> 1.0
            neuralEvidence <= -10.0 -> -1.0
            else -> 0.0
        }
        val neuralStrength = abs(neuralEvidence).roundToInt().coerceIn(0, 100)

        val longGeometry = signal.stop < signal.entry && signal.tp1 > signal.entry
        val shortGeometry = signal.stop > signal.entry && signal.tp1 < signal.entry
        val geometryValid = signal.entry.isFinite() && signal.stop.isFinite() &&
            signal.tp1.isFinite() && signal.entry > 0.0 && (longGeometry || shortGeometry)
        if (!geometryValid) return Decision("NEUTRAL", 0, minOf(signal.confidence, 35), 0,
            "VETO_INVALID_GEOMETRY", "فرمانده: هندسه ورود، حدضرر یا هدف نامعتبر است")

        val riskDistance = abs(signal.entry - signal.stop)
        val rewardDistance = abs(signal.tp1 - signal.entry)
        // Scalping accepts a small first target when it is directionally valid.
        // The live XT executor separately checks actual taker fees + slippage and
        // refuses any TP1 whose estimated net return is not positive.
        val scalpMode = signal.tradeTimeframe.startsWith("5m")
        if (riskDistance <= 0.0 || rewardDistance <= 0.0 ||
            (!scalpMode && rewardDistance / riskDistance < 1.8))
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 39), 0,
                "VETO_REWARD_RISK", if (scalpMode)
                    "فرمانده: هندسه هدف اسکالپ معتبر نیست"
                else "فرمانده: نسبت سود به زیان کمتر از حداقل ۱٫۸ است")
        if (technicalSign == 0.0)
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 45), 0,
                "ABSTAIN_NO_TECHNICAL_DIRECTION", "فرمانده: جهت تکنیکال شواهد کافی ندارد")

        val neuralWeight = if (neuralValidated && memberProbabilities.size == 10) 0.25 else 0.0
        val combined = technicalEvidence * (1.0 - neuralWeight) + neuralEvidence * neuralWeight
        val technicalConflict = technicalEvidence * technicalSign < -8.0
        val neuralConflict = neuralValidated && neuralSign != 0.0 && neuralSign != technicalSign
        val agreement = consensus?.agreement ?: 0
        if (neuralConflict && neuralOpposed >= 7 && neuralStrength >= 18 && technicalAgreement < 0.80)
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 45), neuralStrength,
                "ABSTAIN_NEURAL_PANEL_CONFLICT",
                "فرمانده: تعارض پنل عصبی؛ صعود " + neuralLong + "، نزول " + neuralShort + "، خنثی " + neuralNeutral)
        if (technicalConflict || abs(combined) < 18.0 || agreement < 58 ||
            directionalVotes.size < 3 || signal.technical.adx < 16.0 || signal.technical.relativeVolume < 0.65)
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 49), neuralStrength,
                "ABSTAIN_WEAK_EVIDENCE",
                "فرمانده: شواهد ناکافی؛ رأی تکنیکال همسو " + alignedWeight.roundToInt() +
                    " از " + totalWeight.roundToInt() + "؛ اجماع " + agreement + "٪")

        val chosen = if (combined > 0.0) "LONG_BIAS" else "SHORT_BIAS"
        if (chosen != technicalDirection)
            return Decision("NEUTRAL", 0, minOf(signal.confidence, 45), neuralStrength,
                "ABSTAIN_DIRECTION_CONFLICT", "فرمانده: جهت ترکیبی با تکنیکال همسو نیست")
        val neuralImpact = if (neuralWeight > 0.0) (combined - technicalEvidence).roundToInt() else 0
        val neuralAdjustment = when {
            neuralWeight == 0.0 -> 0
            neuralAligned >= 8 && neuralStrength >= 25 -> 4
            neuralOpposed >= 7 -> -6
            neuralAligned >= 6 -> 2
            else -> -2
        }
        val voteAdjustment = when {
            technicalAgreement >= 0.85 -> 3
            technicalAgreement < 0.60 -> -5
            else -> 0
        }
        val finalConfidence = (signal.confidence + neuralAdjustment + voteAdjustment).coerceIn(0, 95)
        val finalScore = combined.roundToInt().coerceIn(-100, 100)
        val explanation = "فرمانده: امتیاز نهایی " + finalScore +
            "/100؛ تکنیکال ۷۵٪" + (if (neuralWeight > 0.0) " + شبکه عصبی ۲۵٪" else "؛ عصبی فقط نمایشی تا اعتبارسنجی") +
            "؛ متخصصان تکنیکال همسو " + (technicalAgreement * 100.0).roundToInt() +
            "٪؛ رأی عصبی صعود " + neuralLong + "، نزول " + neuralShort +
            "، خنثی " + neuralNeutral + "؛ اثر خالص عصبی " + neuralImpact
        val status = if (neuralWeight > 0.0) "COMMANDER_FULL_PANEL_REVIEW" else "COMMANDER_TECH_ONLY_AI_UNVALIDATED"
        return Decision(chosen, finalScore, finalConfidence, neuralImpact, status, explanation)
    }
}
