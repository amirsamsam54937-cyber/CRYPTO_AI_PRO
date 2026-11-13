package com.cryptoaipro.monitor

/**
 * Conservative estimated net return for paper-signal evaluation.
 * Fees, slippage and funding are charged on notional before leverage is applied.
 */
internal object PaperOutcomeEconomics {
    const val TAKER_FEE_PER_SIDE = 0.0006
    const val SLIPPAGE_PER_SIDE = 0.0005
    const val FUNDING_PER_8H = 0.0001
    const val EIGHT_HOURS_MS = 8L * 60L * 60L * 1000L

    fun netMarginPercent(grossPriceChangePct: Double, leverage: Int, heldMs: Long): Double {
        require(grossPriceChangePct.isFinite())
        require(leverage in 1..20)
        val duration = heldMs.coerceAtLeast(0L)
        val roundTripCostFraction = 2.0 * (TAKER_FEE_PER_SIDE + SLIPPAGE_PER_SIDE) +
            FUNDING_PER_8H * (duration.toDouble() / EIGHT_HOURS_MS.toDouble())
        return (grossPriceChangePct - roundTripCostFraction * 100.0) * leverage
    }

    fun costPercentOnNotional(heldMs: Long): Double {
        val duration = heldMs.coerceAtLeast(0L)
        return (2.0 * (TAKER_FEE_PER_SIDE + SLIPPAGE_PER_SIDE) +
            FUNDING_PER_8H * (duration.toDouble() / EIGHT_HOURS_MS.toDouble())) * 100.0
    }
}
