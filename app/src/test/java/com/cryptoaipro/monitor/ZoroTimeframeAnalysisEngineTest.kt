package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class ZoroTimeframeAnalysisEngineTest {
    private fun candles(count: Int, drift: Double): List<DoubleArray> =
        (0 until count).map { i ->
            val base = 100.0 + drift * i + sin(i / 5.0) * 0.4
            val close = base + drift * 0.7 + sin((i + 1) / 5.0) * 0.2
            doubleArrayOf(
                base,
                maxOf(base, close) + 0.35,
                minOf(base, close) - 0.35,
                close,
                800.0 + (i % 9) * 55.0,
                1_700_000_000_000.0 + i * 60_000.0
            )
        }

    @Test fun analyzesEverySupportedXtRestTimeframeInStableOrder() {
        val input = linkedMapOf(
            "1m" to candles(160, 0.08),
            "5m" to candles(160, 0.07),
            "15m" to candles(160, 0.06),
            "30m" to candles(160, 0.05),
            "1h" to candles(160, 0.04),
            "4h" to candles(160, 0.03),
            "1d" to candles(160, 0.02),
            "1w" to candles(160, 0.01)
        )
        val result = ZoroTimeframeAnalysisEngine.analyze(input)
        assertEquals(listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w"), result.map { it.timeframe })
        assertTrue(result.all { it.available && it.candleCount == 160 && it.lastClosedAt > 0L })
        assertTrue(ZoroTimeframeAnalysisEngine.consensusScore(result) in -100..100)
    }

    @Test fun missingAndInsufficientFramesDoNotInventDirection() {
        val result = ZoroTimeframeAnalysisEngine.analyze(mapOf("1w" to candles(40, 0.1)))
        assertEquals(1, result.size)
        assertFalse(result.single().available)
        assertEquals("WAIT", result.single().direction)
        assertEquals(0, ZoroTimeframeAnalysisEngine.consensusScore(result))
    }

    @Test fun opposingFrameCountRespectsTheRequestedSide() {
        val frames = ZoroTimeframeAnalysisEngine.analyze(
            mapOf("1h" to candles(160, -0.1), "4h" to candles(160, -0.08))
        )
        assertEquals(2, ZoroTimeframeAnalysisEngine.opposingCount(frames, "LONG_BIAS"))
        assertEquals(0, ZoroTimeframeAnalysisEngine.opposingCount(frames, "SHORT_BIAS"))
    }
}
