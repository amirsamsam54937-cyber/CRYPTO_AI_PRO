package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class WolfLiquidityMapTest {
    private fun marketBars(): List<ZoroLiquidityMapEngine.Candle> {
        val bars = (0 until 70).map { i ->
            val close = 100.0 + i * 0.02
            ZoroLiquidityMapEngine.Candle(
                time = (i + 1L) * 900_000L,
                open = close,
                high = close + 0.7,
                low = close - 0.7,
                close = close,
                volume = 1000.0
            )
        }.toMutableList()
        listOf(12, 28, 44).forEach { i ->
            val p = bars[i]
            bars[i] = p.copy(high = 110.0, open = 108.0, close = 109.0)
        }
        listOf(18, 34, 50).forEach { i ->
            val p = bars[i]
            bars[i] = p.copy(low = 90.0, open = 92.0, close = 91.0)
        }
        return bars
    }

    @Test fun repeatedSwingLevelsCreateBuyAndSellSideLiquidityPools() {
        val result = ZoroLiquidityMapEngine.analyze(marketBars())
        assertNotNull(result.nearestBuySide)
        assertNotNull(result.nearestSellSide)
        assertTrue(result.pools.any { it.side == ZoroLiquidityMapEngine.Side.BUY_SIDE })
        assertTrue(result.pools.any { it.side == ZoroLiquidityMapEngine.Side.SELL_SIDE })
        assertTrue(result.pools.all { it.touches >= 2 && it.strength in 1..100 })
    }

    @Test fun insufficientHistoryDoesNotInventLiquidity() {
        val result = ZoroLiquidityMapEngine.analyze(marketBars().take(20))
        assertTrue(result.pools.isEmpty())
        assertNull(result.nearestBuySide)
        assertNull(result.nearestSellSide)
    }
}
