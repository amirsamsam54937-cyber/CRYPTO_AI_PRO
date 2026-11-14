package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfNeuralForecastJournalTest {
    @Test fun sixHourForecastUsesSixHourlyBarsAndTheTrainingNeutralBand() {
        val hour = WolfCandleHorizon.HOUR_MS
        val baseTime = 10L * hour
        assertEquals(true, WolfCandleHorizon.directionalOutcome(100.0, 100.30))
        assertEquals(false, WolfCandleHorizon.directionalOutcome(100.0, 99.70))
        assertEquals(null, WolfCandleHorizon.directionalOutcome(100.0, 100.10))
        assertEquals(null, WolfCandleHorizon.directionalOutcome(100.0, 100.25))
        assertEquals(null, WolfCandleHorizon.directionalOutcome(100.0, 99.75))
        assertEquals(baseTime + 6L * hour, baseTime + 6L * WolfCandleHorizon.HOUR_MS)
    }

    @Test fun perMemberMetricsSeparateDirectionalAccuracyFromCoverage() {
        val stats = WolfNeuralForecastJournal.metrics(
            listOf(0.90, 0.20, 0.50),
            listOf(1, 0, 1),
            4
        )
        assertEquals(4, stats.index)
        assertEquals(3, stats.samples)
        assertEquals(2, stats.correct)
        assertEquals(1.0, stats.accuracy, 1e-9)
        assertEquals(0.10, stats.brier, 1e-9)
        assertEquals(2.0 / 3.0, stats.coverage, 1e-9)
        assertTrue(stats.logLoss > 0.0)
    }
}
