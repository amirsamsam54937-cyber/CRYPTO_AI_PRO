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

    @Test fun validatedTenMemberConsensusCanPassWhenScenariosAndNetEconomicsAgree() {
        val thought = NexusCognitiveEngine.think(sampleSignal(), modelValidated = true)
        assertTrue(thought.allowed)
        assertEquals("LONG_BIAS", thought.side)
        assertTrue(thought.neuralAgreement >= 7)
        assertTrue(thought.netRewardRisk >= 1.35)
        assertTrue(thought.estimatedRoundTripCostPct > 0.0)
    }
}
