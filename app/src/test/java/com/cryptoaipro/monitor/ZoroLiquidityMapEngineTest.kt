package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoroLiquidityMapEngineTest {
    private fun candles(highs: List<Double>, lows: List<Double>, closes: List<Double>): List<ZoroLiquidityMapEngine.Candle> =
        highs.indices.map { i ->
            val close = closes[i]
            ZoroLiquidityMapEngine.Candle(i + 1L, close, highs[i], lows[i], close, 100.0)
        }

    @Test fun repeatedSwingHighsCreateBuySidePool() {
        val highs = MutableList(40) { 101.0 + (it % 3) * 0.2 }
        val lows = MutableList(40) { 98.0 }
        val closes = MutableList(40) { 100.0 }
        highs[5] = 105.0; highs[12] = 105.1; highs[20] = 105.05
        val result = ZoroLiquidityMapEngine.analyze(candles(highs, lows, closes))
        assertTrue(result.pools.any { it.side == ZoroLiquidityMapEngine.Side.BUY_SIDE && it.touches >= 2 })
        assertTrue(result.nearestBuySide != null)
    }

    @Test fun sweepAndReclaimIsDetectedOnLatestCandle() {
        val highs = MutableList(40) { 101.0 }
        val lows = MutableList(40) { 98.0 }
        val closes = MutableList(40) { 100.0 }
        highs[6] = 105.0; highs[15] = 105.05
        highs[39] = 105.8
        closes[39] = 104.7
        val result = ZoroLiquidityMapEngine.analyze(candles(highs, lows, closes))
        assertTrue(result.sweptBuySide)
        assertTrue(result.pools.any { it.event == ZoroLiquidityMapEngine.Event.SWEEP_AND_RECLAIM })
    }

    @Test fun insufficientHistoryReturnsSafeEmptySnapshot() {
        val rows = (1..10).map {
            ZoroLiquidityMapEngine.Candle(it.toLong(), 100.0, 101.0, 99.0, 100.0, 1.0)
        }
        val result = ZoroLiquidityMapEngine.analyze(rows)
        assertTrue(result.pools.isEmpty())
        assertFalse(result.sweptBuySide)
        assertEquals(null, result.nearestBuySide)
    }
}
