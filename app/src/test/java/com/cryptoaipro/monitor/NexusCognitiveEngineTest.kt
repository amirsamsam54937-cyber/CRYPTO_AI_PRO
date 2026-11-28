package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusCognitiveEngineTest {
    private fun sampleSignal(): MarketSignal {
        val technical = TechnicalSnapshot(
            ema9 = 101.0, ema20 = 100.0, ema50 = 99.0, ema100 = 98.0, ema200 = 97.0,
            adx = 28.0, bollingerWidthPct = 2.0, bollingerPosition = 0.6,
            relativeVolume = 1.2, vwap = 100.0, stochasticRsi = 55.0,
            atrPercent = 1.0, regime = "TRENDING", candlePattern = "bullish",
            marketStructure = "higher highs", support = 98.0, resistance = 104.0,
            liquidityStrength = 80
        )
        val now = System.currentTimeMillis()
        val outlook = ZoroMultiHorizonEngine.Outlook(
            available = true, dataQuality = 90, trendState = "up", regime = "trending",
            flowState = "balanced", truthStatus = "test", warnings = emptyList(),
            horizons = listOf("1h", "4h", "6h", "24h").map {
                ZoroMultiHorizonEngine.Horizon(it, 4, "LONG_BIAS", 75, 0.5, 1.0, "test")
            }, neuralSixHourUsed = true
        )
        val probs = List(10) { 0.70 }
        return MarketSignal(
            symbol = "BTC_USDT", price = 100.0, change = 0.2, volume = 1000.0,
            direction = "LONG_BIAS", score = 80, rsi = 60.0, confidence = 80,
            reason = "test", entry = 100.0, stop = 99.0, tp1 = 102.0,
            tp2 = 102.5, tp3 = 103.0, leverage = 2, atr = 1.0, technical = technical,
            candles = listOf(doubleArrayOf(100.0, 101.0, 99.0, 100.5, 1000.0, now.toDouble())),
            neuralDirection = "LONG_BIAS", neuralScore = 70, tradeTimeframe = "15m",
            neuralMemberProbabilities = probs,
            neuralUncertainty = WolfEnsembleUncertainty.analyze(probs),
            multiHorizonOutlook = outlook
        )
    }

    @Test fun unvalidatedModelCannotPassTheCognitiveLoop() {
        val thought = NexusCognitiveEngine.think(sampleSignal(), modelValidated = false)
        assertFalse(thought.allowed)
        assertEquals("NEUTRAL", thought.side)
        assertEquals("ABSTAIN", thought.phase)
    }

    private fun risingCandles(count: Int, intervalMs: Long): List<DoubleArray> {
        val now = System.currentTimeMillis()
        return (0 until count).map { i ->
            val base = 100.0 + i * 0.25 + kotlin.math.sin(i / 5.0) * 1.5
            val close = base + 0.12
            doubleArrayOf(
                base, maxOf(base, close) + 0.20, minOf(base, close) - 0.10,
                close, 1000.0 + (i % 7) * 45.0,
                (now - (count - i) * intervalMs).toDouble()
            )
        }
    }

    @Test fun independentChartCanPassWithoutOptionalMultiHorizonForecast() {
        val frames = linkedMapOf(
            "5m" to risingCandles(120, 5L * 60_000L),
            "15m" to risingCandles(120, 15L * 60_000L),
            "1h" to risingCandles(120, 60L * 60_000L),
            "4h" to risingCandles(120, 4L * 60L * 60_000L)
        )
        val referencePrice = frames.getValue("15m").last()[3]
        val input = sampleSignal().copy(
            price = referencePrice,
            entry = referencePrice,
            stop = referencePrice * 0.995,
            tp1 = referencePrice * 1.025,
            tp2 = referencePrice * 1.03,
            tp3 = referencePrice * 1.035,
            candles = frames.getValue("15m"),
            timeframeCandles = frames,
            timeframeAnalysis = ZoroTimeframeAnalysisEngine.analyze(frames),
            multiHorizonOutlook = null
        )
        val thought = NexusCognitiveEngine.think(input, modelValidated = false)
        assertTrue("chart and four timeframe frames should independently qualify: ${thought.reasons.joinToString(" | ")}", thought.allowed)
        assertEquals("LONG_BIAS", thought.side)
        assertTrue(thought.netRewardRisk >= 1.35)
    }

    @Test fun validatedTenMemberConsensusCanPassWhenScenariosAndNetEconomicsAgree() {
        val thought = NexusCognitiveEngine.think(sampleSignal(), modelValidated = true)
        assertTrue(thought.allowed)
        assertEquals("LONG_BIAS", thought.side)
        assertTrue(thought.neuralAgreement >= 7)
        assertTrue(thought.netRewardRisk >= 1.35)
        assertTrue(thought.estimatedRoundTripCostPct > 0.0)
    }
}
