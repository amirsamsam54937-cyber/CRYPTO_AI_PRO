package com.cryptoaipro.monitor

/**
 * Converts an absolute UP/DOWN model probability into agreement with a technical signal.
 * The model's label is future price direction, not probability that the technical signal wins.
 */
internal object NeuralSignalAlignment {
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
