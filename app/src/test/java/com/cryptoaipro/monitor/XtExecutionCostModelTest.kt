package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XtExecutionCostModelTest {
    @Test fun slippageRisesWithVolatilityAndThinLiquidity() {
        val calm = XtExecutionCostModel.estimate(0.5, 2.0, 90)
        val volatile = XtExecutionCostModel.estimate(4.0, 2.0, 90)
        val illiquid = XtExecutionCostModel.estimate(0.5, 0.2, 10)
        assertTrue(volatile.slippagePerSide > calm.slippagePerSide)
        assertTrue(illiquid.slippagePerSide > calm.slippagePerSide)
    }

    @Test fun slippageIsBoundedAndCostsAreExplicit() {
        val estimate = XtExecutionCostModel.estimate(100.0, 0.0, 0)
        assertEquals(0.004, estimate.slippagePerSide, 1e-12)
        assertEquals(0.0006, estimate.feePerSide, 1e-12)
        assertTrue(estimate.roundTripCostPercent > 0.0)
    }

    @Test fun fundingIsNeverCountedAsGuaranteedIncome() {
        val estimate = XtExecutionCostModel.estimate(1.0, 1.0, 60, fundingReservePer8h = -0.001)
        assertEquals(0.001, estimate.fundingReservePer8h, 1e-12)
    }
}
