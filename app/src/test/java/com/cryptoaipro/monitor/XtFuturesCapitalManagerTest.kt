package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class XtFuturesCapitalManagerTest {
    private val wallet = XtFuturesCapitalManager.Wallet(
        asset = "USDT", walletBalance = 1000.0, equity = 1000.0,
        availableMargin = 900.0, usedMargin = 100.0, openOrderMargin = 0.0
    )
    private val fees = XtFuturesCapitalManager.Fees()
    private val rules = XtFuturesCapitalManager.ContractRules(
        contractSize = 1.0, quantityStep = 1.0, minQuantity = 1.0, minNotionalUsdt = 5.0
    )
    private val request = XtFuturesCapitalManager.Request(
        entry = 100.0, stop = 98.0, takeProfit = 104.0, leverage = 3,
        expectedFundingRatePer8h = 0.0001, expectedHoldingHours = 8.0
    )

    @Test fun sizesAgainstEquityFreeMarginAndStopRisk() {
        val decision = XtFuturesCapitalManager.plan(wallet, fees, rules, request)
        assertTrue(decision.reason, decision.allowed)
        val plan = decision.plan!!
        assertTrue(plan.contracts >= 1.0)
        assertTrue(plan.marginRequiredUsdt <= 100.0 + 1e-6)
        assertTrue(plan.worstCaseStopCostUsdt <= plan.riskBudgetUsdt * 1.1 + 1e-6)
        assertTrue(plan.netTargetProfitUsdt > 0.0)
    }

    @Test fun rejectsWalletTooSmallForExchangeMinimum() {
        val tiny = wallet.copy(walletBalance = 0.5, equity = 0.5, availableMargin = 0.5, usedMargin = 0.0)
        val decision = XtFuturesCapitalManager.plan(tiny, fees, rules, request)
        assertFalse(decision.allowed)
        assertNull(decision.plan)
    }

    @Test fun rejectsTargetThatCannotCoverRoundTripCosts() {
        val tinyTarget = request.copy(takeProfit = 100.1)
        val decision = XtFuturesCapitalManager.plan(wallet, fees, rules, tinyTarget)
        assertFalse(decision.allowed)
    }

    @Test fun refusesExcessiveLeverageAndInvalidAvailableMargin() {
        assertFalse(XtFuturesCapitalManager.plan(wallet, fees, rules, request.copy(leverage = 10)).allowed)
        val corrupt = wallet.copy(availableMargin = 5000.0)
        assertFalse(XtFuturesCapitalManager.plan(corrupt, fees, rules, request).allowed)
    }

    @Test fun fundingIsAlwaysCountedAsCostNotGuaranteedIncome() {
        val noFunding = XtFuturesCapitalManager.plan(wallet, fees, rules, request.copy(expectedFundingRatePer8h = 0.0)).plan!!
        val adverseFunding = XtFuturesCapitalManager.plan(wallet, fees, rules, request.copy(expectedFundingRatePer8h = -0.001)).plan!!
        assertTrue(adverseFunding.estimatedFundingCostUsdt > noFunding.estimatedFundingCostUsdt)
        assertTrue(adverseFunding.estimatedNetTargetProfitUsdt < noFunding.estimatedNetTargetProfitUsdt)
    }
    @Test fun dailyLossAndPeakDrawdownBreakersStopNewRisk() {
        val dailyStop = XtFuturesCapitalManager.riskPolicy(
            XtFuturesCapitalManager.PortfolioState(980.0, 1000.0, 1000.0, 0)
        )
        assertFalse(dailyStop.allowNewTrades)
        val drawdownStop = XtFuturesCapitalManager.riskPolicy(
            XtFuturesCapitalManager.PortfolioState(920.0, 1000.0, 1000.0, 0)
        )
        assertFalse(drawdownStop.allowNewTrades)
    }

    @Test fun riskScalesDownDuringDrawdownAndLossStreak() {
        val normal = XtFuturesCapitalManager.riskPolicy(
            XtFuturesCapitalManager.PortfolioState(1000.0, 1000.0, 1000.0, 0)
        )
        val reduced = XtFuturesCapitalManager.riskPolicy(
            XtFuturesCapitalManager.PortfolioState(970.0, 1000.0, 1000.0, 3)
        )
        assertTrue(normal.allowNewTrades)
        assertTrue(reduced.allowNewTrades)
        assertTrue(reduced.riskFraction < normal.riskFraction)
        val streakStop = XtFuturesCapitalManager.riskPolicy(
            XtFuturesCapitalManager.PortfolioState(1000.0, 1000.0, 1000.0, 5)
        )
        assertFalse(streakStop.allowNewTrades)
    }

}
