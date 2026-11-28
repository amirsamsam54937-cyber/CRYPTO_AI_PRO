package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class WolfTrapDefenseEngineTest {
    private fun base(): List<DoubleArray> = (0 until 22).map { i ->
        doubleArrayOf(104.0, 110.0, 100.0, 105.0, 100.0, (i + 1).toDouble())
    }.toMutableList().also {
        it[20] = doubleArrayOf(104.0, 108.0, 102.0, 106.0, 100.0, 21.0)
        it[21] = doubleArrayOf(106.0, 107.0, 103.0, 105.0, 100.0, 22.0)
    }

    @Test fun rejectsLongAfterHighSweepAndReclaim() {
        val bars = base().toMutableList()
        bars[21] = doubleArrayOf(109.0, 112.0, 108.0, 109.5, 260.0, 22.0)
        val result = WolfTrapDefenseEngine.analyze(bars)
        assertEquals(WolfTrapDefenseEngine.Trap.BUY_SIDE_TRAP, result.trap)
        assertTrue(result.blocksLong)
        assertFalse(result.blocksShort)
        assertTrue(result.severity >= 42)
    }

    @Test fun rejectsShortAfterLowSweepAndReclaim() {
        val bars = base().toMutableList()
        bars[21] = doubleArrayOf(101.0, 102.0, 98.0, 100.5, 250.0, 22.0)
        val result = WolfTrapDefenseEngine.analyze(bars)
        assertEquals(WolfTrapDefenseEngine.Trap.SELL_SIDE_TRAP, result.trap)
        assertTrue(result.blocksShort)
        assertFalse(result.blocksLong)
    }

    @Test fun acceptsBreakoutOnlyAfterTwoClosedBarsBeyondSharedPriorRange() {
        val bars = base().toMutableList()
        bars[20] = doubleArrayOf(109.0, 112.0, 108.0, 111.0, 180.0, 21.0)
        bars[21] = doubleArrayOf(111.0, 114.0, 110.5, 113.0, 190.0, 22.0)
        val result = WolfTrapDefenseEngine.analyze(bars)
        assertTrue(result.acceptanceConfirmed)
        assertFalse(result.blocksLong)
        assertEquals(WolfTrapDefenseEngine.Trap.NONE, result.trap)
    }

    @Test fun insufficientCandlesFailClosedWithoutInventingTrap() {
        val result = WolfTrapDefenseEngine.analyze(base().take(10))
        assertFalse(result.available)
        assertEquals(WolfTrapDefenseEngine.Trap.NONE, result.trap)
        assertFalse(result.blocksLong)
        assertFalse(result.blocksShort)
    }
}
