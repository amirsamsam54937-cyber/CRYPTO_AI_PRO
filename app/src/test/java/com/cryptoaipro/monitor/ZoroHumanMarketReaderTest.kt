package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class ZoroHumanMarketReaderTest {
    private fun candles(count: Int = 180, direction: Double = 1.0): List<DoubleArray> =
        (0 until count).map { i ->
            val base = 100.0 + direction * i * 0.12
            val wave = sin(i / 5.0) * 0.9
            val open = base + wave
            val close = base + sin((i + 1) / 5.0) * 0.9 + direction * 0.08
            doubleArrayOf(
                open,
                maxOf(open, close) + 0.45,
                minOf(open, close) - 0.40,
                close,
                1000.0 + (i % 11) * 70.0,
                1_700_000_000_000.0 + i * 900_000.0
            )
        }

    @Test fun readsStructureAuctionAndVolumeProfileFromOhlcv() {
        val result = ZoroHumanMarketReader.analyze(candles())
        assertTrue(result.available)
        assertTrue(result.structure.isNotBlank())
        assertTrue(result.auction.isNotBlank())
        assertTrue(result.flow.isNotBlank())
        assertNotNull(result.poc)
        assertNotNull(result.valueLow)
        assertNotNull(result.valueHigh)
        assertTrue(result.evidence.isNotEmpty())
    }

    @Test fun detectsInsufficientAndMalformedChartDataSafely() {
        assertFalse(ZoroHumanMarketReader.analyze(candles(40)).available)
        val malformed = candles().toMutableList()
        malformed[50] = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertTrue(ZoroHumanMarketReader.analyze(malformed).available)
    }

    @Test fun exposesConditionalBullishAndBearishScenarios() {
        val result = ZoroHumanMarketReader.analyze(candles())
        assertTrue(result.bullishScenario.isNotBlank())
        assertTrue(result.bearishScenario.isNotBlank())
        assertTrue(result.patterns.isNotEmpty() || result.warnings.isNotEmpty())
    }
}
