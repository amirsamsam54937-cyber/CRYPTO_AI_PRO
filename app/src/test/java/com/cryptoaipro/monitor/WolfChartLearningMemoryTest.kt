package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class WolfChartLearningMemoryTest {
    private fun candles(count: Int): List<DoubleArray> = (0 until count).map { i ->
        val close = 100.0 + i * 0.35
        doubleArrayOf(
            close - 0.1, close + 0.6, close - 0.5, close,
            1000.0 + if (i == count - 1) 1800.0 else 0.0,
            ((i + 1L) * 900_000L).toDouble()
        )
    }

    @Test fun studiesClosedChartWindowAndProducesExplainableLesson() {
        val lesson = WolfChartLearningMemory.study("BTC_USDT", "15m", candles(120))
        assertNotNull(lesson)
        assertEquals("BTC_USDT", lesson!!.symbol)
        assertEquals("15m", lesson.timeframe)
        assertEquals(120, lesson.candleCount)
        assertTrue(lesson.trend.isNotBlank())
        assertTrue(lesson.structure.isNotBlank())
        assertTrue(lesson.volume.isNotBlank())
        assertEquals(120L * 900_000L, lesson.candleCloseTime)
    }

    @Test fun refusesShortOrInvalidChartWindows() {
        assertNull(WolfChartLearningMemory.study("BTC_USDT", "15m", candles(40)))
        val invalid = candles(100).toMutableList()
        invalid[99] = doubleArrayOf(100.0, 90.0, 95.0, 92.0, 10.0, 99_000.0)
        assertNull(WolfChartLearningMemory.study("BTC_USDT", "15m", invalid))
    }
}
