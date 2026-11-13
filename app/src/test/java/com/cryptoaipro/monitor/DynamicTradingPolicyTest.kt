package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class DynamicTradingPolicyTest {
    private fun strongLong() = DynamicTradingPolicy.Input(
        direction = "LONG_BIAS",
        confidence = 88,
        neuralUpProbability = 0.78,
        neuralValidated = true,
        consensusDirection = "LONG_BIAS",
        consensusAgreement = 84,
        riskReward = 2.2,
        atrPercent = 1.1,
        volatilityRatio = 0.8,
        structuralBreak = false,
        dailyLossPercent = 0.0,
        portfolioOpenRiskPercent = 0.5,
        openPositions = 0
    )

    @Test fun strongValidatedSignalGetsBoundedDynamicPlan() {
        val plan = DynamicTradingPolicy.evaluate(strongLong(), 1_000.0, 900.0, 100.0, 98.0)
        assertNotNull(plan)
        plan!!
        assertEquals("LONG_BIAS", plan.direction)
        assertTrue(plan.riskFraction in 0.0015..0.0050)
        assertTrue(plan.leverage in 1..3)
        assertTrue(plan.estimatedLossAtStopUsdt <= plan.riskBudgetUsdt * 1.000001)
        assertTrue(plan.marginRequiredUsdt <= 900.0 * 0.15 * 1.000001)
    }

    @Test fun adverseEntrySlippageReducesPositionSizeForSameStop() {
        val ideal = DynamicTradingPolicy.evaluate(strongLong(), 1_000.0, 900.0, 100.0, 98.0)!!
        val slipped = DynamicTradingPolicy.evaluate(strongLong(), 1_000.0, 900.0, 100.05, 98.0)!!
        assertTrue(slipped.quantity < ideal.quantity)
        assertTrue(slipped.estimatedLossAtStopUsdt <= slipped.riskBudgetUsdt * 1.000001)
    }

    @Test fun unvalidatedNeuralModelCannotAuthorizeAutopilot() {
        assertNull(DynamicTradingPolicy.evaluate(strongLong().copy(neuralValidated = false), 1_000.0, 900.0, 100.0, 98.0))
    }

    @Test fun neuralContradictionBlocksLongAndShort() {
        assertNull(DynamicTradingPolicy.evaluate(strongLong().copy(neuralUpProbability = 0.43), 1_000.0, 900.0, 100.0, 98.0))
        val short = strongLong().copy(direction = "SHORT_BIAS", consensusDirection = "SHORT_BIAS",
            neuralUpProbability = 0.65)
        assertNull(DynamicTradingPolicy.evaluate(short, 1_000.0, 900.0, 100.0, 102.0))
    }

    @Test fun regimeBreakAndDailyLossStopBlockNewEntries() {
        assertNull(DynamicTradingPolicy.evaluate(strongLong().copy(structuralBreak = true), 1_000.0, 900.0, 100.0, 98.0))
        assertNull(DynamicTradingPolicy.evaluate(strongLong().copy(dailyLossPercent = 2.0), 1_000.0, 900.0, 100.0, 98.0))
    }

    @Test fun volatilityReducesLeverageWithoutIncreasingRisk() {
        val calm = DynamicTradingPolicy.evaluate(strongLong(), 1_000.0, 900.0, 100.0, 98.0)!!
        val volatile = DynamicTradingPolicy.evaluate(strongLong().copy(volatilityRatio = 1.6), 1_000.0, 900.0, 100.0, 98.0)!!
        assertTrue(volatile.leverage < calm.leverage)
        assertEquals(calm.riskFraction, volatile.riskFraction, 1e-12)
    }

    @Test fun dailyEntryAndConsecutiveLossCircuitBreakersBlockNewPlans() {
        assertNull(DynamicTradingPolicy.evaluate(strongLong().copy(dailyEntries = 8), 1_000.0, 900.0, 100.0, 98.0))
        assertNull(DynamicTradingPolicy.evaluate(strongLong().copy(consecutiveLosses = 3), 1_000.0, 900.0, 100.0, 98.0))
        assertNotNull(DynamicTradingPolicy.evaluate(strongLong().copy(dailyEntries = 7, consecutiveLosses = 2), 1_000.0, 900.0, 100.0, 98.0))
    }

    @Test fun trailingStopOnlyTightensAndWaitsForOneR() {
        val unchanged = DynamicTradingPolicy.trailStop("LONG_BIAS", 100.0, 100.5, 98.0, 2.0, 1.0)
        assertEquals(98.0, unchanged, 1e-12)
        val moved = DynamicTradingPolicy.trailStop("LONG_BIAS", 100.0, 103.2, 98.0, 2.0, 1.0)
        assertTrue(moved > 100.0)
        assertTrue(moved <= 103.2)
        val shortMoved = DynamicTradingPolicy.trailStop("SHORT_BIAS", 100.0, 96.8, 102.0, 2.0, 1.0)
        assertTrue(shortMoved < 100.0)
        assertTrue(shortMoved >= 96.8)
        assertEquals(102.0, DynamicTradingPolicy.trailStop("SHORT_BIAS", 100.0, 99.5, 102.0, 2.0, 1.0), 1e-12)
    }
}
