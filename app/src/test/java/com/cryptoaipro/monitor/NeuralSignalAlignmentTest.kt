package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class NeuralSignalAlignmentTest {
    @Test fun bullishModelVoteStrengthensLongSignal() {
        assertEquals(71, NeuralSignalAlignment.adjustedConfidence(70, "LONG_BIAS", 0.80))
    }

    @Test fun bearishModelVoteWeakensLongSignal() {
        assertEquals(65, NeuralSignalAlignment.adjustedConfidence(70, "LONG_BIAS", 0.80.let { 1.0 - it }))
    }

    @Test fun bearishModelVoteStrengthensShortSignal() {
        assertEquals(71, NeuralSignalAlignment.adjustedConfidence(70, "SHORT_BIAS", 0.20))
    }

    @Test fun bullishModelVoteWeakensShortSignal() {
        assertEquals(65, NeuralSignalAlignment.adjustedConfidence(70, "SHORT_BIAS", 0.80))
    }

    @Test fun neutralModelAbstainsWithoutChangingConfidence() {
        assertEquals(70, NeuralSignalAlignment.adjustedConfidence(70, "LONG_BIAS", 0.52))
    }

    @Test fun weakModelVoteIsNeutralAtThresholdBoundary() {
        assertEquals("NEUTRAL", NeuralSignalAlignment.predictedDirection(0.54))
        assertEquals("NEUTRAL", NeuralSignalAlignment.predictedDirection(0.46))
    }
}
