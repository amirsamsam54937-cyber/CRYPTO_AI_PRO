package com.cryptoaipro.monitor

/**
 * Conservative execution-cost estimator for XT USDT-M.
 *
 * Fees come from XT contract metadata in LIVE preflight. Slippage is an estimate,
 * not a guaranteed fill price; it widens with ATR and thins with volume/liquidity.
 * Funding is deliberately treated as a cost reserve, never as certain income.
 */
internal object XtExecutionCostModel {
    const val DEFAULT_PAPER_TAKER_FEE = 0.0006
    const val DEFAULT_FUNDING_RESERVE_PER_8H = 0.0001

    data class Estimate(
        val slippagePerSide: Double,
        val fundingReservePer8h: Double,
        val feePerSide: Double
    ) {
        val roundTripCostFraction get() = feePerSide * 2.0 + slippagePerSide * 2.0
        val roundTripCostPercent get() = roundTripCostFraction * 100.0
    }

    fun estimate(
        atrPercent: Double,
        relativeVolume: Double,
        liquidityStrength: Int,
        feePerSide: Double = DEFAULT_PAPER_TAKER_FEE,
        fundingReservePer8h: Double = DEFAULT_FUNDING_RESERVE_PER_8H
    ): Estimate {
        require(atrPercent.isFinite() && relativeVolume.isFinite())
        require(feePerSide.isFinite() && feePerSide in 0.0..0.02)
        require(fundingReservePer8h.isFinite() && kotlin.math.abs(fundingReservePer8h) <= 0.02)

        val normalizedAtr = atrPercent.coerceIn(0.0, 12.0) / 100.0
        val volatilityCost = normalizedAtr * 0.035
        val thinVolumePenalty = (1.0 - relativeVolume.coerceIn(0.0, 1.0)) * 0.0005
        val thinLiquidityPenalty = (55 - liquidityStrength.coerceIn(0, 100))
            .coerceAtLeast(0) / 55.0 * 0.00045
        val slippage = (0.00015 + volatilityCost + thinVolumePenalty + thinLiquidityPenalty)
            .coerceIn(0.0002, 0.004)

        return Estimate(
            slippagePerSide = slippage,
            fundingReservePer8h = kotlin.math.abs(fundingReservePer8h),
            feePerSide = feePerSide
        )
    }

    /** Cost reserve after entry slippage has already been embedded in the simulated fill. */
    fun remainingCostPercentAfterEntry(estimate: Estimate): Double =
        (estimate.feePerSide * 2.0 + estimate.slippagePerSide) * 100.0
}
