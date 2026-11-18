package com.cryptoaipro.monitor

/**
 * PROJECT MIRROR / ZORO Strategy Lab.
 *
 * Deterministic, fee-aware evaluation primitives. This module evaluates already-generated
 * historical trade candidates; it does not generate signals and has no exchange/order access.
 * All timestamps and candidate ordering must be chronological.
 */
internal object ZoroStrategyLab {
    data class CostModel(
        /** One-way taker/maker fee in percent of notional, e.g. 0.05 means 0.05%. */
        val feePerSidePct: Double,
        /** One-way expected slippage in percent of notional. */
        val slippagePerSidePct: Double,
        /** Total funding cost in percent of notional for the complete holding period. */
        val fundingPctPerRoundTrip: Double = 0.0
    ) {
        init {
            require(feePerSidePct >= 0.0 && slippagePerSidePct >= 0.0)
            require(fundingPctPerRoundTrip >= 0.0)
        }

        val roundTripCostPct: Double
            get() = feePerSidePct * 2.0 + slippagePerSidePct * 2.0 + fundingPctPerRoundTrip
    }

    data class ClosedTrade(
        val strategyId: String,
        val openedAt: Long,
        val closedAt: Long,
        /** Gross return on allocated position notional, in percent; direction-adjusted by caller. */
        val grossReturnPct: Double,
        /** Optional explicit cost override, otherwise CostModel is used. */
        val explicitCostPct: Double? = null
    )

    data class TradeResult(
        val strategyId: String,
        val openedAt: Long,
        val closedAt: Long,
        val grossPct: Double,
        val costPct: Double,
        val netPct: Double
    )

    data class Metrics(
        val tradeCount: Int,
        val winRatePct: Double,
        val profitFactor: Double,
        val expectancyPct: Double,
        val totalNetReturnPct: Double,
        val maxDrawdownPct: Double,
        val averageWinPct: Double,
        val averageLossPct: Double,
        val samplePass: Boolean,
        val reasons: List<String>
    )

    data class WalkForward(
        val train: Metrics,
        val test: Metrics,
        val trainTrades: Int,
        val testTrades: Int,
        val eligibleForPaperForwardTest: Boolean,
        val reasons: List<String>
    )

    fun applyCosts(trades: List<ClosedTrade>, costs: CostModel): List<TradeResult> {
        require(trades.all { it.grossReturnPct.isFinite() })
        require(trades.all { it.closedAt >= it.openedAt })
        return trades.sortedBy { it.closedAt }.map {
            val cost = (it.explicitCostPct ?: costs.roundTripCostPct).coerceAtLeast(0.0)
            TradeResult(it.strategyId, it.openedAt, it.closedAt, it.grossReturnPct, cost,
                it.grossReturnPct - cost)
        }
    }

    /** Chronological 70/30 split; the final 30% is never used for parameter selection. */
    fun walkForward(
        trades: List<ClosedTrade>,
        costs: CostModel,
        minTrainTrades: Int = 70,
        minTestTrades: Int = 30
    ): WalkForward {
        val ordered = trades.sortedBy { it.closedAt }
        val split = (ordered.size * 0.70).toInt()
        val train = applyCosts(ordered.take(split), costs)
        val test = applyCosts(ordered.drop(split), costs)
        val trainMetrics = metrics(train)
        val testMetrics = metrics(test)
        val reasons = mutableListOf<String>()
        if (train.size < minTrainTrades) reasons += "نمونه آموزش کمتر از $minTrainTrades معامله است"
        if (test.size < minTestTrades) reasons += "نمونه خارج از نمونه کمتر از $minTestTrades معامله است"
        if (!trainMetrics.samplePass) reasons += "عملکرد بخش آموزش از آستانه‌های کیفیت عبور نکرد"
        if (!testMetrics.samplePass) reasons += "عملکرد خارج از نمونه از آستانه‌های کیفیت عبور نکرد"
        if (testMetrics.expectancyPct <= 0.0) reasons += "امید ریاضی خالص خارج از نمونه مثبت نیست"
        return WalkForward(trainMetrics, testMetrics, train.size, test.size,
            reasons.isEmpty(), reasons)
    }

    fun metrics(results: List<TradeResult>): Metrics {
        val ordered = results.sortedBy { it.closedAt }
        val returns = ordered.map { it.netPct }
        val wins = returns.filter { it > 0.0 }
        val losses = returns.filter { it < 0.0 }
        val grossProfit = wins.sum()
        val grossLoss = -losses.sum()
        val pf = when {
            grossLoss > 0.0 -> grossProfit / grossLoss
            grossProfit > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }
        var equity = 1.0
        var peak = 1.0
        var maxDd = 0.0
        returns.forEach { pct ->
            equity *= (1.0 + pct / 100.0).coerceAtLeast(0.0)
            if (equity > peak) peak = equity
            if (peak > 0.0) maxDd = maxOf(maxDd, (peak - equity) / peak * 100.0)
        }
        val expectancy = returns.average().takeIf { it.isFinite() } ?: 0.0
        val reasons = mutableListOf<String>()
        if (ordered.size < MIN_TOTAL_TRADES) reasons += "حداقل $MIN_TOTAL_TRADES معامله بسته‌شده لازم است"
        if (pf < MIN_PROFIT_FACTOR) reasons += "Profit Factor خالص کمتر از $MIN_PROFIT_FACTOR است"
        if (expectancy <= 0.0) reasons += "امید ریاضی خالص مثبت نیست"
        if (maxDd > MAX_DRAWDOWN_PCT) reasons += "افت سرمایه از سقف $MAX_DRAWDOWN_PCT٪ بیشتر است"
        return Metrics(
            ordered.size,
            if (returns.isEmpty()) 0.0 else wins.size.toDouble() / returns.size * 100.0,
            pf, expectancy, (equity - 1.0) * 100.0, maxDd,
            wins.average().takeIf { it.isFinite() } ?: 0.0,
            losses.average().takeIf { it.isFinite() } ?: 0.0,
            reasons.isEmpty(), reasons
        )
    }

    /** Conservative ranking: out-of-sample expectancy and drawdown dominate win rate. */
    fun rank(walkForward: WalkForward): Double {
        val test = walkForward.test
        if (test.tradeCount == 0) return Double.NEGATIVE_INFINITY
        val pf = if (test.profitFactor.isFinite()) test.profitFactor.coerceAtMost(3.0) else 3.0
        val score = test.expectancyPct * 12.0 + (pf - 1.0) * 8.0 -
            test.maxDrawdownPct * 0.8 + (test.winRatePct - 50.0) * 0.05
        return if (walkForward.eligibleForPaperForwardTest) score else score - 1000.0
    }

    private const val MIN_TOTAL_TRADES = 30
    private const val MIN_PROFIT_FACTOR = 1.20
    private const val MAX_DRAWDOWN_PCT = 15.0
}
