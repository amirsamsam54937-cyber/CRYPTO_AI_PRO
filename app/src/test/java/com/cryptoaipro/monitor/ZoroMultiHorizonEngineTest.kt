package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class ZoroMultiHorizonEngineTest {
    private val now = 2_000_000_000_000L

    private fun signal(upward: Boolean = true, gapAt: Int? = null): MarketSignal {
        val bars = 80
        val candles = (0 until bars).map { i ->
            val base = if (upward) 100.0 + i * 0.12 else 100.0
            val close = if (upward) base + 0.04 else base
            val timestamp = now - (bars - i) * 900_000L
            doubleArrayOf(base, maxOf(base, close) + 0.08, minOf(base, close) - 0.08,
                close, if (upward) 120.0 else 100.0,
                (timestamp + if (gapAt != null && i >= gapAt) 900_000L else 0L).toDouble())
        }
        val technical = TechnicalSnapshot(
            ema9 = if (upward) 109.0 else 100.0,
            ema20 = if (upward) 108.0 else 100.0,
            ema50 = if (upward) 106.0 else 100.0,
            ema100 = if (upward) 104.0 else 100.0,
            ema200 = if (upward) 102.0 else 100.0,
            adx = if (upward) 30.0 else 12.0,
            bollingerWidthPct = 1.0,
            bollingerPosition = 0.8,
            relativeVolume = if (upward) 1.5 else 1.0,
            vwap = 109.0,
            stochasticRsi = 60.0,
            atrPercent = 0.5,
            regime = "trend",
            candlePattern = "none",
            marketStructure = "higher highs",
            support = 98.0,
            resistance = 112.0,
            macdLine = if (upward) 1.0 else 0.0,
            macdSignalLine = 0.0
        )
        return MarketSignal(
            symbol = "BTC_USDT",
            price = candles.last()[3],
            change = 0.5,
            volume = 1000.0,
            direction = "NEUTRAL",
            score = 0,
            rsi = if (upward) 60.0 else 50.0,
            confidence = 50,
            reason = "test",
            entry = 100.0,
            stop = 99.0,
            tp1 = 102.0,
            tp2 = 103.0,
            tp3 = 104.0,
            leverage = 2,
            atr = 0.5,
            technical = technical,
            candles = candles
        )
    }

    @Test
    fun producesFourScenarioHorizonsWithoutCallingThemProbabilities() {
        val report = ZoroMultiHorizonEngine.analyze(signal(), now)
        assertTrue(report.available)
        assertEquals(4, report.horizons.size)
        assertEquals(listOf(4, 16, 24, 96), report.horizons.map { it.bars })
        assertTrue(report.horizons.all { it.evidenceScore in 0..100 })
        assertTrue(report.horizons.all { it.volatilityBandPct > 0.0 })
        assertFalse(report.neuralSixHourUsed)
    }

    @Test
    fun rejectsInsufficientCandleHistory() {
        val base = signal()
        val short = base.copy(candles = base.candles.take(20))
        val report = ZoroMultiHorizonEngine.analyze(short, now)
        assertFalse(report.available)
        assertTrue(report.truthStatus.contains("۳۲"))
    }

    @Test
    fun reportsTimestampGapsAsTruthWarnings() {
        val clean = ZoroMultiHorizonEngine.analyze(signal(), now)
        val broken = ZoroMultiHorizonEngine.analyze(signal(gapAt = 40), now)
        assertTrue(clean.available)
        assertTrue(broken.available)
        assertTrue(broken.warnings.any { it.contains("فاصله زمانی") })
        assertTrue(broken.dataQuality <= clean.dataQuality)
    }
}
