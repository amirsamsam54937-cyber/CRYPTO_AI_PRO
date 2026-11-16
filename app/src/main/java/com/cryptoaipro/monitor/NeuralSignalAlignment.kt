package com.cryptoaipro.monitor

/**
 * Converts an absolute UP/DOWN model probability into agreement with a technical signal.
 * The model's label is future price direction, not probability that the technical signal wins.
 */
internal object NeuralSignalAlignment {
    /**
     * Unvalidated outputs may only provide a small research assist when all ten
     * experts are present and the panel is internally coherent. A validated model
     * may contribute more, but disagreement always reduces its weight.
     */
    fun influenceWeight(validated: Boolean, memberCount: Int, highDisagreement: Boolean): Double {
        if (memberCount != 10) return 0.0
        return when {
            validated && highDisagreement -> 0.10
            validated -> 0.25
            highDisagreement -> 0.0
            else -> 0.08
        }
    }

    fun experimentalConfidenceAdjustment(
        validated: Boolean,
        alignedVotes: Int,
        opposedVotes: Int,
        neuralStrength: Int
    ): Int {
        if (validated) return when {
            alignedVotes >= 8 && neuralStrength >= 25 -> 4
            opposedVotes >= 7 -> -6
            alignedVotes >= 6 -> 2
            else -> -2
        }
        // Before holdout validation, influence is deliberately capped to +/-2 points.
        if (neuralStrength < 55) return 0
        return when {
            alignedVotes >= 8 -> 2
            opposedVotes >= 8 -> -2
            else -> 0
        }
    }

    fun predictedDirection(probabilityUp: Double): String {
        require(probabilityUp.isFinite() && probabilityUp in 0.0..1.0)
        return when {
            probabilityUp >= 0.55 -> "LONG_BIAS"
            probabilityUp <= 0.45 -> "SHORT_BIAS"
            else -> "NEUTRAL"
        }
    }

    fun adjustedConfidence(
        technicalConfidence: Int,
        technicalDirection: String,
        probabilityUp: Double,
        modelWeight: Double = 0.10
    ): Int {
        require(technicalConfidence in 0..100)
        require(modelWeight in 0.0..0.25)
        val predicted = predictedDirection(probabilityUp)
        // A neutral model abstains; it must not dilute a valid technical signal.
        if (predicted == "NEUTRAL") return technicalConfidence
        val strength = (maxOf(probabilityUp, 1.0 - probabilityUp) * 100.0)
            .toInt().coerceIn(50, 99)
        val aligned = if (predicted == technicalDirection) strength else 100 - strength
        return (technicalConfidence * (1.0 - modelWeight) + aligned * modelWeight)
            .toInt().coerceIn(0, 100)
    }
}
