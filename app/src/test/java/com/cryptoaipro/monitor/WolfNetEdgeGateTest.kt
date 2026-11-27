package com.cryptoaipro.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfNetEdgeGateTest {
    @Test fun strongLongSetupPassesAfterCosts() {
        val result = WolfNetEdgeGate.assess(
            entry = 100.0, stop = 99.0, target = 102.2, isLong = true,
            atrPercent = 1.0, relativeVolume = 2.0, liquidityStrength = 90
        )
        assertTrue(result.valid)
        assertTrue(result.estimatedRoundTripCostPct > 0.0)
        assertTrue(result.netRewardRisk >= 1.50)
        assertTrue(result.accepted)
    }

    @Test fun weakShortSetupIsRejectedAfterCosts() {
        val result = WolfNetEdgeGate.assess(
            entry = 100.0, stop = 101.0, target = 99.2, isLong = false,
            atrPercent = 1.0, relativeVolume = 2.0, liquidityStrength = 90
        )
        assertTrue(result.valid)
        assertTrue(result.netRewardRisk < 1.50)
        assertFalse(result.accepted)
    }

    @Test fun invalidDirectionGeometryFailsClosed() {
        val result = WolfNetEdgeGate.assess(
            entry = 100.0, stop = 99.0, target = 98.0, isLong = true,
            atrPercent = 1.0, relativeVolume = 1.0, liquidityStrength = 50
        )
        assertFalse(result.valid)
        assertFalse(result.accepted)
    }
}
