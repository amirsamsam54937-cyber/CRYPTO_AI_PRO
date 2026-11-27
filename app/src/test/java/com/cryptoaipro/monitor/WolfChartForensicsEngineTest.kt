package com.cryptoaipro.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfChartForensicsEngineTest {
    @Test fun insufficientCandlesFailClosed() {
        val report = WolfChartForensicsEngine.analyze(emptyList())
        assertFalse(report.available)
        assertTrue(report.cautions.isNotEmpty())
    }

    @Test fun validOhlcvProducesProfileAndChartDiagnostics() {
        val candles = (0 until 140).map { i ->
            val base = 100.0 + i * 0.035 + kotlin.math.sin(i / 5.0) * 0.18
            val open = base - 0.04
            val close = base + 0.04
            doubleArrayOf(open, close + 0.09, open - 0.09, close, 800.0 + (i % 9) * 60.0, (i + 1) * 60_000.0)
        }
        val report = WolfChartForensicsEngine.analyze(candles)
        assertTrue(report.available)
        assertNotNull(report.poc)
        assertNotNull(report.valueLow)
        assertNotNull(report.valueHigh)
        assertNotNull(report.vwap)
        assertNotNull(report.rsi)
        assertTrue(report.profile.contains("POC"))
        assertTrue(report.cautions.isNotEmpty())
    }

    @Test fun malformedCandlesAreIgnored() {
        val valid = (0 until 100).map { i ->
            val p = 50.0 + i * 0.02
            doubleArrayOf(p, p + 0.1, p - 0.1, p + 0.02, 1000.0, (i + 1) * 60_000.0)
        }
        val mixed = valid + listOf(doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, 0.0, 99999999.0))
        assertTrue(WolfChartForensicsEngine.analyze(mixed).available)
    }
}
