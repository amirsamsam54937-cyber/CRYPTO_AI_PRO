package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class NeuralSignalAlignmentTest {
    @Test fun bullishModelVoteStrengthensLongSignal() {
        assertEquals(71, NeuralSignalAlignment.adjustedConfidence(70, "LONG_BIAS", 0.80))
    }

    @Test fun bearishModelVoteWeakensLongSignal() {
        assertEquals(65, NeuralSignalAlignment.adjustedConfidence(70, "LONG_BIAS", 0.20))
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

    @Test fun calibrationThresholdControlsIndependentDirectionAndCanAbstain() {
        assertEquals("LONG_BIAS", NeuralSignalAlignment.predictedDirection(0.82, 0.80))
        assertEquals("SHORT_BIAS", NeuralSignalAlignment.predictedDirection(0.18, 0.80))
        assertEquals("NEUTRAL", NeuralSignalAlignment.predictedDirection(0.75, 0.80))
        assertEquals("NEUTRAL", NeuralSignalAlignment.predictedDirection(0.99, 1.01))
    }

    @Test fun unvalidatedTenMemberPanelGetsOnlySmallResearchWeight() {
        assertEquals(0.08, NeuralSignalAlignment.influenceWeight(false, 10, false), 0.0001)
        assertEquals(0.0, NeuralSignalAlignment.influenceWeight(false, 10, true), 0.0001)
        assertEquals(0.0, NeuralSignalAlignment.influenceWeight(false, 9, false), 0.0001)
    }

    @Test fun validatedPanelGetsMoreWeightButDisagreementReducesIt() {
        assertEquals(0.25, NeuralSignalAlignment.influenceWeight(true, 10, false), 0.0001)
        assertEquals(0.10, NeuralSignalAlignment.influenceWeight(true, 10, true), 0.0001)
    }

    @Test fun unvalidatedNeuralConfidenceEffectIsCappedAtTwoPoints() {
        assertEquals(2, NeuralSignalAlignment.experimentalConfidenceAdjustment(false, 9, 1, 70))
        assertEquals(-2, NeuralSignalAlignment.experimentalConfidenceAdjustment(false, 1, 9, 70))
        assertEquals(0, NeuralSignalAlignment.experimentalConfidenceAdjustment(false, 9, 1, 54))
    }
}
