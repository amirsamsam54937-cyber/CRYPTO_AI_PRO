package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoroSignalIntelligenceTest {
    private fun signal(
        direction: String = "LONG_BIAS",
        neuralDirection: String = "LONG_BIAS",
        technicalDirection: String = "LONG_BIAS",
        confidence: Int = 82,
        rsi: Double = 56.0,
        rrTarget: Double = 120.0
    ) = MarketSignal(
        symbol = "BTC/USDT:USDT",
        price = 100.0,
        change = 1.0,
        volume = 1000000.0,
        direction = direction,
        score = 80,
        rsi = rsi,
        confidence = confidence,
        reason = "test",
        entry = 100.0,
        stop = 90.0,
        tp1 = rrTarget,
        tp2 = rrTarget,
        tp3 = rrTarget,
        leverage = 2,
        atr = 1.0,
        technical = TechnicalSnapshot(
            ema9 = 101.0, ema20 = 100.0, ema50 = 99.0, ema100 = 98.0, ema200 = 97.0,
            adx = 30.0, bollingerWidthPct = 2.0, bollingerPosition = 0.7,
            relativeVolume = 1.8, vwap = 100.0, stochasticRsi = 0.6,
            atrPercent = 1.5, regime = "TREND",
            candlePattern = "bullish", marketStructure = "higher highs",
            support = 95.0, resistance = 110.0, liquidityStrength = 75
        ),
        neuralDirection = neuralDirection,
        technicalDirection = technicalDirection,
        neuralUncertainty = WolfEnsembleUncertainty.analyze(
            List(10) { if (neuralDirection == "LONG_BIAS") 0.72 else 0.28 }
        )
    )

    @Test fun strongAlignedEvidenceDoesNotReduceConfidence() {
        val input = signal()
        val result = ZoroSignalIntelligence.enrich(listOf(input)).single()
        assertEquals(input.confidence, result.confidence)
        assertTrue(result.reason.contains("ارزیابی کیفیت ZORO"))
    }

    @Test fun neuralDisagreementReducesConfidenceButNeverChangesDirection() {
        val input = signal(neuralDirection = "SHORT_BIAS")
        val result = ZoroSignalIntelligence.enrich(listOf(input)).single()
        assertEquals(input.direction, result.direction)
        assertTrue(result.confidence < input.confidence)
        assertTrue(result.reason.contains("تعارض نظر شبکه عصبی"))
    }

    @Test fun invalidRiskRewardCannotIncreaseConfidence() {
        val input = signal(rrTarget = 101.0)
        val result = ZoroSignalIntelligence.enrich(listOf(input)).single()
        assertTrue(result.confidence <= input.confidence)
    }

    @Test fun neutralSignalsAreNotPromotedByQualityLayer() {
        val input = signal(direction = "NEUTRAL")
        val result = ZoroSignalIntelligence.enrich(listOf(input)).single()
        assertEquals("NEUTRAL", result.direction)
    }
}
