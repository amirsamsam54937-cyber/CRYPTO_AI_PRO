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

    @Test fun advancedZoroIndicatorsAndLiquidityFeedTheNeuralFeatureVector() {
        val technical = TechnicalSnapshot(
            ema9 = 105.0, ema20 = 104.0, ema50 = 102.0, ema100 = 101.0, ema200 = 99.0,
            adx = 28.0, bollingerWidthPct = 3.0, bollingerPosition = 0.8,
            relativeVolume = 1.7, vwap = 103.0, stochasticRsi = 72.0, atrPercent = 1.8,
            regime = "TREND", candlePattern = "پوشای صعودی", marketStructure = "HH/HL",
            support = 98.0, resistance = 110.0, cci = 140.0, rocPercent = 2.2,
            mfi = 68.0, obvTrend = 0.6, supertrendDirection = "صعودی",
            liquiditySweep = "برگشت صعودی پس از sweep کف", fairValueGap = "FVG صعودی",
            rsiDivergence = "واگرایی مثبت", macdLine = 1.2, macdSignalLine = 0.7,
            buySideLiquidity = 112.0, sellSideLiquidity = 95.0, liquidityStrength = 78
        )
        val advanced = ZoroAdvancedTechnicalEngine.Report(
            available = true, bias = "صعودی", evidenceScore = 72, trend = "صعودی",
            regime = "TREND", structure = "HH/HL", momentum = "مثبت", volumeState = "قوی",
            smartMoney = "BOS/FVG", candlePatterns = listOf("پوشا"), chartPatterns = listOf("پرچم"),
            indicators = listOf(
                ZoroAdvancedTechnicalEngine.Metric("Ichimoku", "بالای ابر", "روند مثبت"),
                ZoroAdvancedTechnicalEngine.Metric("Chaikin Money Flow", "0.25", "انباشت"),
                ZoroAdvancedTechnicalEngine.Metric("Aroon Oscillator", "80", "روند صعودی"),
                ZoroAdvancedTechnicalEngine.Metric("Donchian 20", "75٪", "نیمه بالایی"),
                ZoroAdvancedTechnicalEngine.Metric("Keltner 20,2", "65٪", "مومنتوم"),
                ZoroAdvancedTechnicalEngine.Metric("Williams %R", "-15", "اشباع خرید"),
                ZoroAdvancedTechnicalEngine.Metric("Stoch RSI", "72", "مثبت"),
                ZoroAdvancedTechnicalEngine.Metric("Bollinger 20,2", "80٪", "نزدیک باند بالا"),
                ZoroAdvancedTechnicalEngine.Metric("VWAP", "1.2٪", "بالای VWAP"),
                ZoroAdvancedTechnicalEngine.Metric("Parabolic SAR", "101.0", "قیمت بالای SAR")
            ),
            levels = listOf(
                ZoroAdvancedTechnicalEngine.Level("حمایت ساختاری", 98.0),
                ZoroAdvancedTechnicalEngine.Level("مقاومت ساختاری", 112.0),
                ZoroAdvancedTechnicalEngine.Level("فیبوناچی ۶۱٫۸٪", 104.0)
            ),
            higherTimeframe = "تأیید صعودی", warnings = emptyList(), education = emptyList()
        )
        val features = TenaxisChartCognition.extractFeatures(
            candles(240), technical = technical, advanced = advanced
        )
        assertEquals(TenaxisChartCognition.FEATURE_COUNT, features.size)
        assertEquals(0.72, features[52], 0.0001)
        assertEquals(1.0, features[54], 0.0)
        assertEquals(0.25, features[55], 0.0001)
        assertEquals(0.8, features[56], 0.0001)
        assertTrue(features[40] > 0.0)
        assertEquals(1.0, features[44], 0.0)
        assertEquals(1.0, features[45], 0.0)
        assertEquals(1.0, features[46], 0.0)
    }

    @Test fun insufficientHistoryProducesFiniteNeutralFeatureVector() {
        val features = TenaxisChartCognition.extractFeatures(candles(12))
        assertEquals(TenaxisChartCognition.FEATURE_COUNT, features.size)
        assertTrue(features.all { it == 0.0 })
    }
}
