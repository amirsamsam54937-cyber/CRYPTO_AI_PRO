package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class HopeCommanderTest {
    private fun signal(
        outOfDistribution: Boolean,
        distance: Double,
        threshold: Double
    ) = MarketSignal(
        symbol = "BTCUSDT",
        price = 100.0,
        change = 0.0,
        volume = 1_000_000.0,
        direction = "LONG_BIAS",
        score = 70,
        rsi = 58.0,
        confidence = 75,
        reason = "test",
        entry = 100.0,
        stop = 98.0,
        tp1 = 103.6,
        tp2 = 104.8,
        tp3 = 106.0,
        leverage = 2,
        atr = 1.0,
        technical = TechnicalSnapshot(
            ema9 = 101.0, ema20 = 100.5, ema50 = 99.0, ema100 = 98.0, ema200 = 97.0,
            adx = 25.0, bollingerWidthPct = 2.0, bollingerPosition = 0.6,
            relativeVolume = 1.2, vwap = 100.0, stochasticRsi = 0.6,
            atrPercent = 1.0, regime = "trend", candlePattern = "bullish",
            marketStructure = "higher_high", support = 98.0, resistance = 104.0
        ),
        neuralDirection = "LONG_BIAS",
        neuralMemberProbabilities = List(10) { 0.9 },
        neuralUncertainty = WolfEnsembleUncertainty.analyze(List(10) { 0.9 }, threshold),
        neuralOutOfDistribution = outOfDistribution,
        neuralInputDistance = distance,
        neuralDecisionThreshold = threshold
    )

    @Test fun outOfDistributionInputForcesAbstentionEvenWithUnanimousVotes() {
        val result = HopeCommander.decide(signal(true, 7.0, 0.8), neuralValidated = true)
        assertEquals("ABSTAIN_OUT_OF_DISTRIBUTION", result.status)
        assertEquals("NEUTRAL", result.direction)
    }

    @Test fun disabledCalibrationThresholdForcesAbstention() {
        val result = HopeCommander.decide(signal(false, 0.5, 1.01), neuralValidated = true)
        assertEquals("ABSTAIN_CALIBRATION_GATE_DISABLED", result.status)
        assertEquals("NEUTRAL", result.direction)
    }
}
