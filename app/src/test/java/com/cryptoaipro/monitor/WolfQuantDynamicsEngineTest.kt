package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class WolfQuantDynamicsEngineTest {
    @Test fun insufficientHistoryFailsClosed() {
        val report = WolfQuantDynamicsEngine.analyze(emptyList())
        assertFalse(report.available)
        assertEquals(WolfQuantDynamicsEngine.State.INSUFFICIENT, report.state)
        assertTrue(report.riskDirective.contains("ممنوع"))
    }

    @Test fun risingSeriesProducesFiniteStateAndScenarioBands() {
        val candles = (0 until 180).map { i ->
            val close = 100.0 + i * 0.12 + sin(i / 6.0) * 0.08
            val open = close - 0.035
            doubleArrayOf(open, close + 0.06, open - 0.06, close, 1000.0 + (i % 8) * 30.0, (i + 1) * 60_000.0)
        }
        val report = WolfQuantDynamicsEngine.analyze(candles)
        assertTrue(report.available)
        assertTrue(report.stateConfidence in 0.0..1.0)
        assertTrue(report.trendProbability.isFinite())
        assertTrue(report.rangeProbability.isFinite())
        assertEquals(listOf(3, 6, 12), report.scenarios.map { it.horizonBars })
        assertTrue(report.scenarios.all { it.lowerBandPct <= it.expectedMovePct && it.expectedMovePct <= it.upperBandPct })
    }

    @Test fun invalidRowsAreIgnoredAndProbabilitiesRemainBounded() {
        val candles = (0 until 120).map { i ->
            val close = 75.0 + sin(i / 4.0) * 0.2
            doubleArrayOf(close, close + 0.1, close - 0.1, close, 500.0, (i + 1) * 60_000.0)
        } + listOf(doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, 0.0, 99999999.0))
        val report = WolfQuantDynamicsEngine.analyze(candles)
        assertTrue(report.available)
        listOf(report.trendProbability, report.rangeProbability, report.chaosProbability, report.compressionProbability)
            .forEach { assertTrue(it in 0.0..1.0) }
    }
}
