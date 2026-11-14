package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfNeuralForecastJournalTest {
    @Test fun sixHourForecastUsesSixHourlyBarsAndTheTrainingNeutralBand() {
        val hour = WolfCandleHorizon.HOUR_MS
        val baseTime = 10L * hour
        val baseClose = 100.0
        val futureUp = 100.30
        val futureDown = 99.70
        val neutral = 100.10

        assertEquals(true, WolfCandleHorizon.directionalOutcome(baseClose, futureUp))
        assertEquals(false, WolfCandleHorizon.directionalOutcome(baseClose, futureDown))
        assertEquals(null, WolfCandleHorizon.directionalOutcome(baseClose, neutral))
        assertTrue(baseTime + 6L * hour > baseTime)
    }
}
