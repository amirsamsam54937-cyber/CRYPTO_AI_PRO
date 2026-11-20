package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class ZoroStrategyLabTest {
    private val costs = ZoroStrategyLab.CostModel(
        feePerSidePct = 0.05,
        slippagePerSidePct = 0.02,
        fundingPctPerRoundTrip = 0.01
    )

    @Test fun roundTripCostsIncludeBothSidesAndFunding() {
        assertEquals(0.15, costs.roundTripCostPct, 1e-9)
    }

    @Test fun costsAreSubtractedFromEveryTrade() {
        val trade = ZoroStrategyLab.ClosedTrade("A", 1, 2, 0.20)
        val result = ZoroStrategyLab.applyCosts(listOf(trade), costs).single()
        assertEquals(0.20, result.grossPct, 1e-9)
        assertEquals(0.15, result.costPct, 1e-9)
        assertEquals(0.05, result.netPct, 1e-9)
    }

    @Test fun resultsAreSortedChronologicallyBeforeCompounding() {
        val trades = listOf(
            ZoroStrategyLab.ClosedTrade("A", 2, 4, -10.0),
            ZoroStrategyLab.ClosedTrade("A", 1, 2, 10.0)
        )
        val result = ZoroStrategyLab.applyCosts(trades,
            ZoroStrategyLab.CostModel(0.0, 0.0))
        val metrics = ZoroStrategyLab.metrics(result)
        assertEquals(-1.0, metrics.totalNetReturnPct, 1e-9)
        assertEquals(10.0, metrics.maxDrawdownPct, 1e-9)
    }

    @Test fun walkForwardKeepsFinalThirtyPercentAsHoldout() {
        val trades = (0 until 100).map { i ->
            ZoroStrategyLab.ClosedTrade("A", i.toLong(), (i + 1).toLong(), 1.0)
        }
        val wf = ZoroStrategyLab.walkForward(
            trades, ZoroStrategyLab.CostModel(0.0, 0.0),
            minTrainTrades = 70, minTestTrades = 30
        )
        assertEquals(70, wf.trainTrades)
        assertEquals(30, wf.testTrades)
        assertTrue(wf.eligibleForPaperForwardTest)
    }

    @Test fun insufficientHoldoutCannotBePromoted() {
        val trades = (0 until 20).map { i ->
            ZoroStrategyLab.ClosedTrade("A", i.toLong(), (i + 1).toLong(), 2.0)
        }
        val wf = ZoroStrategyLab.walkForward(
            trades, ZoroStrategyLab.CostModel(0.0, 0.0)
        )
        assertFalse(wf.eligibleForPaperForwardTest)
        assertTrue(wf.reasons.any { it.contains("خارج از نمونه") })
    }

    @Test fun negativeNetExpectancyFailsEvenWithHighWinRate() {
        val results = (0 until 30).map { i ->
            val net = if (i < 27) 0.1 else -2.0
            ZoroStrategyLab.TradeResult("A", i.toLong(), (i + 1).toLong(),
                net, 0.0, net)
        }
        val metrics = ZoroStrategyLab.metrics(results)
        assertTrue(metrics.winRatePct > 80.0)
        assertTrue(metrics.expectancyPct < 0.0)
        assertFalse(metrics.samplePass)
    }

    @Test fun rankingPenalizesStrategiesThatFailValidation() {
        val passing = ZoroStrategyLab.WalkForward(
            train = ZoroStrategyLab.Metrics(70, 60.0, 1.8, 0.3, 20.0, 5.0, 1.0, -0.5, true, emptyList()),
            test = ZoroStrategyLab.Metrics(30, 60.0, 1.6, 0.2, 6.0, 4.0, 0.8, -0.5, true, emptyList()),
            trainTrades = 70, testTrades = 30, eligibleForPaperForwardTest = true, reasons = emptyList()
        )
        val failing = passing.copy(eligibleForPaperForwardTest = false)
        assertTrue(ZoroStrategyLab.rank(passing) > ZoroStrategyLab.rank(failing))
    }
}
