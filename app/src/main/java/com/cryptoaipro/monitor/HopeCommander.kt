package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.roundToInt

/** Neural-only directional commander: technical data can veto risk geometry, never choose a side. */
internal object HopeCommander {
    data class Decision(
        val direction: String, val score: Int, val confidence: Int,
        val neuralInfluence: Int, val status: String, val explanation: String
    )

    fun decide(signal: MarketSignal, neuralValidated: Boolean): Decision {
        if (!neuralValidated) return Decision(
            "NEUTRAL", 0, minOf(signal.confidence, 35), 0,
            "ABSTAIN_MODEL_NOT_VALIDATED",
            "فرمانده عصبی: مدل از اعتبارسنجی زمانی مستقل عبور نکرده؛ صدور سیگنال و معامله متوقف است"
        )
        val probabilities = signal.neuralMemberProbabilities.filter { it.isFinite() && it in 0.0..1.0 }
        if (probabilities.size < 7) return Decision(
            "NEUTRAL", 0, minOf(signal.confidence, 35), 0,
            "ABSTAIN_INSUFFICIENT_NEURAL_VOTES",
            "فرمانده عصبی: کمتر از ۷ رأی معتبر از متخصصان شبکه عصبی دریافت شد"
        )
        val meanUp = probabilities.average()
        val longVotes = probabilities.count { it >= 0.55 }
        val shortVotes = probabilities.count { it <= 0.45 }
        val side = when {
            longVotes >= 7 && meanUp >= 0.60 -> "LONG_BIAS"
            shortVotes >= 7 && meanUp <= 0.40 -> "SHORT_BIAS"
            else -> "NEUTRAL"
        }
        if (side == "NEUTRAL") return Decision(
            "NEUTRAL", 0, minOf(signal.confidence, 45), maxOf(longVotes, shortVotes),
            "ABSTAIN_NEURAL_DISAGREEMENT",
            "فرمانده عصبی: اجماع کافی نیست؛ رأی صعود $longVotes از ${probabilities.size} و رأی نزول $shortVotes از ${probabilities.size}"
        )
        if (signal.neuralUncertainty.highDisagreement) return Decision(
            "NEUTRAL", 0, minOf(signal.confidence, 40), maxOf(longVotes, shortVotes),
            "ABSTAIN_NEURAL_UNCERTAINTY", "فرمانده عصبی: اختلاف متخصصان بیش از حد مجاز است"
        )
        val longGeometry = signal.stop < signal.entry && signal.tp1 > signal.entry
        val shortGeometry = signal.stop > signal.entry && signal.tp1 < signal.entry
        val geometryValid = signal.entry.isFinite() && signal.entry > 0.0 &&
            signal.stop.isFinite() && signal.tp1.isFinite() &&
            (if (side == "LONG_BIAS") longGeometry else shortGeometry)
        if (!geometryValid) return Decision(
            "NEUTRAL", 0, minOf(signal.confidence, 35), maxOf(longVotes, shortVotes),
            "ABSTAIN_RISK_GEOMETRY",
            "فرمانده عصبی: جهت شبکه با هندسه حدضرر/هدف فعلی سازگار نیست؛ سیگنال متوقف شد"
        )
        val risk = abs(signal.entry - signal.stop)
        val reward = abs(signal.tp1 - signal.entry)
        val scalp = signal.tradeTimeframe.startsWith("5m")
        if (risk <= 0.0 || reward <= 0.0 || (!scalp && reward / risk < 1.8)) return Decision(
            "NEUTRAL", 0, minOf(signal.confidence, 39), maxOf(longVotes, shortVotes),
            "ABSTAIN_REWARD_RISK", "فرمانده عصبی: نسبت سود به زیان یا هندسه هدف برای اجرا کافی نیست"
        )
        val sideProbability = if (side == "LONG_BIAS") meanUp else 1.0 - meanUp
        val strength = ((sideProbability - 0.5) * 200.0).roundToInt().coerceIn(0, 100)
        val score = if (side == "LONG_BIAS") (meanUp * 100.0).roundToInt()
            else -((1.0 - meanUp) * 100.0).roundToInt()
        val confidence = minOf(signal.confidence, (50 + strength).coerceIn(0, 95))
        val explanation = "جهت فقط از اجماع شبکه عصبی: $side • میانگین احتمال صعود " +
            "${(meanUp * 100.0).roundToInt()}٪ • رأی صعود $longVotes/${probabilities.size} • " +
            "رأی نزول $shortVotes/${probabilities.size} • قدرت عصبی $strength/100"
        return Decision(side, score.coerceIn(-100, 100), confidence, strength,
            "NEURAL_ONLY_VALIDATED", explanation)
    }
}
