package com.cryptoaipro.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfConceptDriftDetectorTest {
    @Test fun worseningRecentForecastLossTriggersDriftWarning() {
        val labels = List(60) { it % 2 }
        val probabilities = labels.mapIndexed { index, label ->
            val correct = index < 40
            when {
                correct && label == 1 -> 0.90
                correct && label == 0 -> 0.10
                !correct && label == 1 -> 0.10
                else -> 0.90
            }
        }
        val report = WolfConceptDriftDetector.analyze(probabilities, labels)
        assertTrue(report.available)
        assertTrue(report.driftDetected)
        assertTrue(report.recentBrier > report.referenceBrier)
    }

    @Test fun stableForecastQualityDoesNotTriggerDrift() {
        val labels = List(60) { it % 2 }
        val probabilities = labels.map { if (it == 1) 0.85 else 0.15 }
        val report = WolfConceptDriftDetector.analyze(probabilities, labels)
        assertTrue(report.available)
        assertFalse(report.driftDetected)
    }

    @Test fun insufficientHistoryFailsClosedWithoutClaimingDrift() {
        val labels = List(59) { it % 2 }
        val probabilities = labels.map { if (it == 1) 0.9 else 0.1 }
        val report = WolfConceptDriftDetector.analyze(probabilities, labels)
        assertFalse(report.available)
        assertFalse(report.driftDetected)
    }

    @Test fun malformedProbabilitiesDoNotProduceDriftVerdict() {
        val labels = List(60) { it % 2 }
        val probabilities = List(59) { 0.8 } + Double.NaN
        val report = WolfConceptDriftDetector.analyze(probabilities, labels)
        assertFalse(report.available)
        assertFalse(report.driftDetected)
    }
}
