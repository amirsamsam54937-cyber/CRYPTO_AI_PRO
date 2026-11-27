package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class TenaxisChartCognitionTest {
    private fun candles(count: Int, drift: Double = 0.12): List<DoubleArray> =
        (0 until count).map { i ->
            val close = 100.0 + i * drift + kotlin.math.sin(i * 0.23) * 0.35
            val open = close - drift * 0.35
            doubleArrayOf(
                open, maxOf(open, close) + 0.28, minOf(open, close) - 0.24,
                close, 900.0 + (i % 9) * 35.0, ((i + 1L) * 900_000L).toDouble()
            )
        }

    @Test fun extractsFortyBoundedFeaturesFromFullOhlcvChart() {
        val features = TenaxisChartCognition.extractFeatures(candles(240))
        assertEquals(TenaxisChartCognition.FEATURE_COUNT, features.size)
        assertTrue(features.all { it.isFinite() && it in -1.0..1.0 })
        assertTrue(features[0] > 0.0)
        assertTrue(features[6] > 0.0)
    }

    @Test fun incorporatesHigherTimeframeContextWithoutInventingMissingValues() {
        val primary = candles(180)
        val hourly = candles(100, 0.45)
        val fourHour = candles(100, -0.15)
        val features = TenaxisChartCognition.extractFeatures(primary, hourly, fourHour)
        assertTrue(features[33] > 0.0)
        assertTrue(features[34] < 0.0)
        val missing = TenaxisChartCognition.extractFeatures(primary)
        assertEquals(0.0, missing[33], 0.0)
        assertEquals(0.0, missing[34], 0.0)
    }

    @Test fun insufficientHistoryProducesFiniteNeutralFeatureVector() {
        val features = TenaxisChartCognition.extractFeatures(candles(12))
        assertEquals(TenaxisChartCognition.FEATURE_COUNT, features.size)
        assertTrue(features.all { it == 0.0 })
    }
}
