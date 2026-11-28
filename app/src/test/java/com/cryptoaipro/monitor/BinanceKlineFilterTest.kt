package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceKlineFilterTest {
    @Test
    fun excludesOnlyKlinesThatHaveNotClosedYet() {
        val nowMs = 2_000L
        val closeTimes = listOf(999L, 1_999L, 2_000L, 3_000L)

        assertEquals(listOf(0, 1), completedBinanceKlineIndices(closeTimes, nowMs))
    }

    @Test
    fun retainsAllRowsWhenTheExchangeReturnsOnlyClosedKlines() {
        val closeTimes = listOf(999L, 1_999L, 2_999L)

        assertEquals(listOf(0, 1, 2), completedBinanceKlineIndices(closeTimes, 3_000L))
    }

    @Test
    fun returnsNoRowsWhenEveryKlineIsStillOpen() {
        val closeTimes = listOf(2_000L, 3_000L)

        assertEquals(emptyList<Int>(), completedBinanceKlineIndices(closeTimes, 2_000L))
    }
}
