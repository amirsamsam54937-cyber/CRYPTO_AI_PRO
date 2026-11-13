package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperOutcomeEconomicsTest {
    @Test fun roundTripCostsAreChargedBeforeLeverage() {
        val net = PaperOutcomeEconomics.netMarginPercent(0.50, 2, 60L * 60L * 1000L)
        assertEquals(0.5575, net, 1e-6)
    }

    @Test fun grossSmallWinCanBecomeNetLossAfterCosts() {
        val net = PaperOutcomeEconomics.netMarginPercent(0.10, 3, 60L * 60L * 1000L)
        assertTrue(net < 0.0)
    }

    @Test fun longerHoldingPeriodAddsFundingCost() {
        val oneHour = PaperOutcomeEconomics.costPercentOnNotional(60L * 60L * 1000L)
        val eightHours = PaperOutcomeEconomics.costPercentOnNotional(8L * 60L * 60L * 1000L)
        assertTrue(eightHours > oneHour)
    }

    @Test fun negativeHoldingTimeIsClampedToZero() {
        assertEquals(
            PaperOutcomeEconomics.costPercentOnNotional(0L),
            PaperOutcomeEconomics.costPercentOnNotional(-1000L),
            1e-12
        )
    }
}
