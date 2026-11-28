package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfBlackUnifiedBrainTest {
    private val market = ZoroApexQueenCore.MarketState(
        symbol = "BTC_USDT",
        price = 60_000.0,
        atrPercent = 1.2,
        spreadPercent = 0.02,
        dataQuality = 98.0,
        regime = ZoroApexQueenCore.Regime.TREND,
        openPositionExists = false,
        emergencyStop = false,
        accountReady = true
    )
    private val risk = ZoroApexQueenCore.RiskSettings(equity = 10.0)
    private val alignedExperts = listOf(
        "trend", "price_action", "liquidity", "momentum",
        "volume", "derivatives", "volatility", "scenario", "chart"
    ).map { id ->
        ZoroApexQueenCore.ExpertEvidence(
            id = id, score = 0.8, reliability = 0.9, freshness = 1.0,
            explanation = "unit-test fixture"
        )
    }
    private fun model(validated: Boolean) = WolfPretrainedEnsemble.Status(
        loaded = true, members = 10, samples = 100_000, trainedAt = "fixture",
        testAccuracy = 0.56, baselineAccuracy = 0.51, balancedAccuracy = 0.55,
        validatedForScoring = validated
    )

    @Test fun validatedInDistributionCouncilCanParticipate() {
        val result = WolfBlackUnifiedBrain.evaluate(
            WolfBlackUnifiedBrain.Inputs(
                market, alignedExperts,
                ZoroApexQueenCore.NeuralCouncil(List(10) { 0.8 }, true, 0.05),
                model(true), false, risk
            )
        )
        assertEquals(ZoroApexQueenCore.Direction.LONG, result.decision.direction)
        assertEquals(
            WolfBlackUnifiedBrain.NeuralMode.VALIDATED_AND_IN_DISTRIBUTION,
            result.neuralMode
        )
        assertTrue(result.liveExecutionEligible)
    }

    @Test fun unvalidatedNeuralVotesAreExcluded() {
        val result = WolfBlackUnifiedBrain.evaluate(
            WolfBlackUnifiedBrain.Inputs(
                market, alignedExperts,
                ZoroApexQueenCore.NeuralCouncil(List(10) { -0.9 }, true, 0.01),
                model(false), false, risk
            )
        )
        assertEquals(ZoroApexQueenCore.Direction.LONG, result.decision.direction)
        assertFalse(result.decision.neuralCouncilUsed)
        assertEquals(
            WolfBlackUnifiedBrain.NeuralMode.RESEARCH_ONLY_UNVALIDATED,
            result.neuralMode
        )
    }

    @Test fun outOfDistributionModelMustAbstain() {
        val result = WolfBlackUnifiedBrain.evaluate(
            WolfBlackUnifiedBrain.Inputs(
                market, alignedExperts,
                ZoroApexQueenCore.NeuralCouncil(List(10) { -0.9 }, true, 0.01),
                model(true), true, risk
            )
        )
        assertEquals(ZoroApexQueenCore.Direction.LONG, result.decision.direction)
        assertFalse(result.decision.neuralCouncilUsed)
        assertEquals(
            WolfBlackUnifiedBrain.NeuralMode.ABSTAIN_OUT_OF_DISTRIBUTION,
            result.neuralMode
        )
    }

    @Test fun staleOrInvalidSpreadFailsClosed() {
        val badMarket = market.copy(spreadPercent = Double.NaN)
        val result = WolfBlackUnifiedBrain.evaluate(
            WolfBlackUnifiedBrain.Inputs(
                badMarket, alignedExperts, null, model(false), true, risk
            )
        )
        assertEquals(ZoroApexQueenCore.Direction.WAIT, result.decision.direction)
        assertFalse(result.liveExecutionEligible)
        assertTrue(result.decision.blockers.any { it.contains("اسپرد") })
    }

    @Test fun missingIndependentSpecialistsAreReportedAndBlockDecision() {
        val result = WolfBlackUnifiedBrain.evaluate(
            WolfBlackUnifiedBrain.Inputs(
                market, alignedExperts.take(3), null, model(false), true, risk
            )
        )
        assertEquals(ZoroApexQueenCore.Direction.WAIT, result.decision.direction)
        assertEquals(6, result.missingSpecialists.size)
        assertFalse(result.liveExecutionEligible)
    }

    @Test fun emergencyStopAlwaysBlocksEligibility() {
        val result = WolfBlackUnifiedBrain.evaluate(
            WolfBlackUnifiedBrain.Inputs(
                market.copy(emergencyStop = true), alignedExperts,
                null, model(false), true, risk
            )
        )
        assertEquals(ZoroApexQueenCore.Direction.WAIT, result.decision.direction)
        assertFalse(result.liveExecutionEligible)
    }
}
