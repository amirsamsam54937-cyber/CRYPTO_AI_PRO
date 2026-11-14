package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfCandleHorizonTest {
    private fun candle(openTime: Long, close: Double = 100.0) =
        doubleArrayOf(close, close + 1.0, close - 1.0, close, 1000.0, openTime.toDouble())

    @Test fun excludesTheStillFormingHourlyCandle() {
        val now = 10L * WolfCandleHorizon.HOUR_MS + 30L * 60L * 1000L
        val rows = listOf(candle(8L * WolfCandleHorizon.HOUR_MS), candle(9L * WolfCandleHorizon.HOUR_MS))
        val closed = WolfCandleHorizon.closedHourlyCandles(rows, now)
        assertEquals(1, closed.size)
        assertEquals(8L * WolfCandleHorizon.HOUR_MS.toLong(), closed.single()[5].toLong())
    }

    @Test fun sortsAndDeduplicatesClosedCandles() {
        val now = 20L * WolfCandleHorizon.HOUR_MS
        val rows = listOf(candle(18L * WolfCandleHorizon.HOUR_MS), candle(17L * WolfCandleHorizon.HOUR_MS),
            candle(17L * WolfCandleHorizon.HOUR_MS))
        val closed = WolfCandleHorizon.closedHourlyCandles(rows, now)
        assertEquals(2, closed.size)
        assertTrue(closed[0][5] < closed[1][5])
    }

    @Test fun rejectsMalformedOhlcRows() {
        val now = 20L * WolfCandleHorizon.HOUR_MS
        val malformed = doubleArrayOf(100.0, 99.0, 98.0, 100.0, 10.0, 18L * WolfCandleHorizon.HOUR_MS.toDouble())
        assertTrue(WolfCandleHorizon.closedHourlyCandles(listOf(malformed), now).isEmpty())
    }

    @Test fun directionalOutcomeMatchesTrainingNeutralBand() {
        assertEquals(true, WolfCandleHorizon.directionalOutcome(100.0, 100.30))
        assertEquals(false, WolfCandleHorizon.directionalOutcome(100.0, 99.70))
        assertNull(WolfCandleHorizon.directionalOutcome(100.0, 100.10))
    }

    @Test fun rejectsInvalidOutcomePrices() {
        var rejected = false
        try { WolfCandleHorizon.directionalOutcome(0.0, 100.0) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
