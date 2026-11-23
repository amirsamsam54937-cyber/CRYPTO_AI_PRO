package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoroApexQueenCoreTest {
    private fun experts(score: Double) = listOf(
        "trend", "price_action", "liquidity", "momentum", "volume", "derivatives"
    ).map { ZoroApexQueenCore.ExpertEvidence(it, score, 0.9, 1.0, explanation = "synthetic test") }

    private fun state(
        open: Boolean = false,
        emergency: Boolean = false,
        ready: Boolean = true
    ) = ZoroApexQueenCore.MarketState(
        symbol = "BTC_USDT", price = 60000.0, atrPercent = 1.2,
        spreadPercent = 0.02, dataQuality = 98.0,
        regime = ZoroApexQueenCore.Regime.TREND,
        openPositionExists = open, emergencyStop = emergency, accountReady = ready
    )

    private val risk = ZoroApexQueenCore.RiskSettings(1000.0)

    @Test fun alignedExpertsProduceLongScenarioAndRiskPlan() {
        val result = ZoroApexQueenCore.decide(state(), experts(0.8), null, risk)
        assertEquals(ZoroApexQueenCore.Direction.LONG, result.direction)
        assertTrue(result.stopReference!! < result.entryReference!!)
        assertTrue(result.target1!! > result.entryReference!!)
        assertTrue(result.suggestedQuantity > 0.0)
        assertTrue(result.suggestedMargin <= 50.0 + 1e-8)
    }

    @Test fun disagreementProducesWait() {
        val mixed = experts(0.8).mapIndexed { i, e ->
            if (i % 2 == 0) e else e.copy(score = -0.8)
        }
        val result = ZoroApexQueenCore.decide(state(), mixed, null, risk)
        assertEquals(ZoroApexQueenCore.Direction.WAIT, result.direction)
    }

    @Test fun openPositionBlocksAnotherEntry() {
        val result = ZoroApexQueenCore.decide(state(open = true), experts(0.9), null, risk)
        assertEquals(ZoroApexQueenCore.Direction.WAIT, result.direction)
        assertTrue(result.blockers.any { it.contains("پوزیشن باز") })
    }

    @Test fun unvalidatedNeuralCouncilDoesNotAffectDecisionAuthority() {
        val result = ZoroApexQueenCore.decide(
            state(), experts(0.8),
            ZoroApexQueenCore.NeuralCouncil(List(10) { -1.0 }, false, 0.0),
            risk
        )
        assertEquals(ZoroApexQueenCore.Direction.LONG, result.direction)
        assertTrue(!result.neuralCouncilUsed)
    }

    @Test fun emergencyStopBlocksTrading() {
        val result = ZoroApexQueenCore.decide(state(emergency = true), experts(0.9), null, risk)
        assertEquals(ZoroApexQueenCore.Direction.WAIT, result.direction)
    }
}
