package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class ZoroAdvancedTechnicalEngineTest {
    private fun candles(count: Int = 260): List<DoubleArray> =
        (0 until count).map { i ->
            val trend = 100.0 + i * 0.18
            val wave = kotlin.math.sin(i / 7.0) * 0.8
            val open = trend + wave
            val close = trend + kotlin.math.sin((i + 1) / 7.0) * 0.8 + 0.12
            doubleArrayOf(
                open,
                maxOf(open, close) + 0.45,
                minOf(open, close) - 0.40,
                close,
                1000.0 + (i % 13) * 90.0,
                1_700_000_000_000.0 + i * 900_000.0
            )
        }

    @Test fun calculatesBroadIndicatorPanelAndChartContext() {
        val report = ZoroAdvancedTechnicalEngine.analyze(candles(), 146.0, candles())
        assertTrue(report.available)
        assertTrue(report.indicators.size >= 16)
        assertTrue(report.indicators.any { it.name.startsWith("RSI") })
        assertTrue(report.indicators.any { it.name.startsWith("MACD") })
        assertTrue(report.indicators.any { it.name.startsWith("Ichimoku") })
        assertTrue(report.levels.isNotEmpty())
        assertTrue(report.higherTimeframe.contains("۱ساعته"))
        assertTrue(report.evidenceScore in -100..100)
    }

    @Test fun rejectsInsufficientOrMalformedChartData() {
        assertFalse(ZoroAdvancedTechnicalEngine.analyze(candles(40), 100.0).available)
        val broken = candles().toMutableList()
        broken[100] = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertTrue(ZoroAdvancedTechnicalEngine.analyze(broken, 100.0).available)
    }
}
