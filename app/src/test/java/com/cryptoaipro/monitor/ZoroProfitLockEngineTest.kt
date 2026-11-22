package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class ZoroProfitLockEngineTest {
    @Test fun longPositionLocksFeesAndFiveProfitMilestones() {
        val cases = listOf(
            Triple(110.0, 1, 101.01),
            Triple(115.0, 2, 106.01),
            Triple(120.0, 3, 111.01),
            Triple(125.0, 4, 116.01),
            Triple(130.0, 5, 121.01)
        )
        cases.forEach { (extreme, expectedStage, expectedStop) ->
            val plan = ZoroProfitLockEngine.plan(
                "LONG", 100.0, 10.0, extreme, extreme - 1.0,
                90.0, 1.0, 2
            )!!
            assertEquals(expectedStage, plan.stage)
            assertEquals(expectedStop, plan.stopPrice, 1e-8)
            assertTrue(plan.shouldUpdate)
        }
    }

    @Test fun shortPositionLocksProfitInTheCorrectDirection() {
        val plan = ZoroProfitLockEngine.plan(
            "SHORT", 100.0, 10.0, 70.0, 71.0,
            110.0, 1.0, 2
        )!!
        assertEquals(5, plan.stage)
        assertEquals(78.99, plan.stopPrice, 1e-8)
        assertTrue(plan.shouldUpdate)
    }

    @Test fun stopNeverMovesAwayFromProfitAndDoesNotCrossMark() {
        val long = ZoroProfitLockEngine.plan(
            "LONG", 100.0, 10.0, 120.0, 119.0,
            112.0, 1.0, 2
        )!!
        assertFalse(long.shouldUpdate)
        assertTrue(long.stopPrice < 119.0)

        val short = ZoroProfitLockEngine.plan(
            "SHORT", 100.0, 10.0, 80.0, 81.0,
            88.0, 1.0, 2
        )!!
        assertFalse(short.shouldUpdate)
        assertTrue(short.stopPrice > 81.0)
    }

    @Test fun finalStageUsesTwoAtrTrailButNeverGivesBackTheLockedTwoR() {
        val plan = ZoroProfitLockEngine.plan(
            side = "LONG",
            entry = 100.0,
            initialRisk = 10.0,
            favorableExtreme = 140.0,
            mark = 139.0,
            currentStop = 120.0,
            feeBufferPrice = 0.5,
            pricePrecision = 2,
            atrTrailDistance = 5.0
        )!!
        assertEquals(5, plan.stage)
        // 2 ATR = 10, so trail from 140 to 130; it is tighter than the +2R floor.
        assertEquals(130.51, plan.stopPrice, 1e-8)
        assertTrue(plan.shouldUpdate)
    }

    @Test fun atrTrailCannotMoveStopBackwardsOnShortPositions() {
        val plan = ZoroProfitLockEngine.plan(
            side = "SHORT",
            entry = 100.0,
            initialRisk = 10.0,
            favorableExtreme = 60.0,
            mark = 61.0,
            currentStop = 78.0,
            feeBufferPrice = 0.5,
            pricePrecision = 2,
            atrTrailDistance = 5.0
        )!!
        assertEquals(5, plan.stage)
        assertEquals(69.49, plan.stopPrice, 1e-8)
        assertTrue(plan.shouldUpdate)
    }

    @Test fun noLockBeforeOneRAndInvalidInputsAreRejected() {
        assertNull(ZoroProfitLockEngine.plan(
            "LONG", 100.0, 10.0, 109.99, 109.0, 90.0, 1.0, 2
        ))
        assertNull(ZoroProfitLockEngine.plan(
            "SIDEWAYS", 100.0, 10.0, 120.0, 119.0, 90.0, 1.0, 2
        ))
        assertNull(ZoroProfitLockEngine.plan(
            "LONG", 100.0, 0.0, 120.0, 119.0, 90.0, 1.0, 2
        ))
    }

    @Test fun roundingIsConservativeForBothSides() {
        val long = ZoroProfitLockEngine.plan(
            "LONG", 1.0, 0.1, 1.15, 1.14, 0.9, 0.00001, 2
        )!!
        assertEquals(1.05, long.stopPrice, 1e-8)

        val short = ZoroProfitLockEngine.plan(
            "SHORT", 1.0, 0.1, 0.85, 0.86, 1.1, 0.00001, 2
        )!!
        assertEquals(0.95, short.stopPrice, 1e-8)
    }
}
