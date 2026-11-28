package com.cryptoaipro.monitor

import kotlin.math.abs

/**
 * Fee-aware edge gate for XT USDT-M perpetual candidates.
 *
 * Converts price-level TP1/SL geometry into a conservative net reward/risk estimate.
 * It is a filter, not a win-probability model. Leverage is intentionally excluded:
 * leverage scales both P/L and risk and must be handled by the separate capital manager.
 */
internal object WolfNetEdgeGate {
    data class Assessment(
        val valid: Boolean,
        val rawRewardRisk: Double,
        val estimatedRoundTripCostPct: Double,
        val netRewardPct: Double,
        val netRiskPct: Double,
        val netRewardRisk: Double,
        val minimumNetRewardRisk: Double,
        val accepted: Boolean,
        val explanation: String
    )

    fun assess(
        entry: Double,
        stop: Double,
        target: Double,
        isLong: Boolean,
        atrPercent: Double,
        relativeVolume: Double,
        liquidityStrength: Int,
        expectedHoldingHours: Double = 2.0,
        feePerSide: Double = XtExecutionCostModel.DEFAULT_PAPER_TAKER_FEE,
        fundingReservePer8h: Double = XtExecutionCostModel.DEFAULT_FUNDING_RESERVE_PER_8H,
        minimumNetRewardRisk: Double = 1.50
    ): Assessment {
        val valid = listOf(entry, stop, target, atrPercent, relativeVolume,
            expectedHoldingHours, feePerSide, fundingReservePer8h, minimumNetRewardRisk)
            .all { it.isFinite() } &&
            entry > 0.0 && stop > 0.0 && target > 0.0 &&
            atrPercent >= 0.0 && relativeVolume >= 0.0 &&
            expectedHoldingHours >= 0.0 && minimumNetRewardRisk > 0.0 &&
            if (isLong) stop < entry && target > entry else stop > entry && target < entry

        if (!valid) return Assessment(
            false, 0.0, 0.0, 0.0, 0.0, 0.0,
            minimumNetRewardRisk, false, "هندسه یا ورودی‌های محاسبه هزینه نامعتبر است"
        )

        val riskPct = abs(entry - stop) / entry * 100.0
        val rewardPct = abs(target - entry) / entry * 100.0
        val rawRr = rewardPct / riskPct
        val estimate = runCatching {
            XtExecutionCostModel.estimate(
                atrPercent = atrPercent,
                relativeVolume = relativeVolume,
                liquidityStrength = liquidityStrength,
                feePerSide = feePerSide,
                fundingReservePer8h = fundingReservePer8h
            )
        }.getOrNull() ?: return Assessment(
            false, rawRr, 0.0, 0.0, 0.0, 0.0,
            minimumNetRewardRisk, false, "مدل هزینه معامله نتوانست برآورد معتبر تولید کند"
        )

        val holdingFundingPct = estimate.fundingReservePer8h *
            (expectedHoldingHours / 8.0) * 100.0
        val totalCostPct = estimate.roundTripCostPercent + holdingFundingPct
        val netReward = rewardPct - totalCostPct
        val netRisk = riskPct + totalCostPct
        val netRr = if (netRisk > 0.0) netReward / netRisk else 0.0
        val accepted = netReward > 0.0 && netRr >= minimumNetRewardRisk
        val explanation = "RR خام %.2f؛ هزینه رفت‌وبرگشت تخمینی %.3f%%؛ RR خالص %.2f؛ حداقل لازم %.2f"
            .format(java.util.Locale.US, rawRr, totalCostPct, netRr, minimumNetRewardRisk)
        return Assessment(
            true, rawRr, totalCostPct, netReward, netRisk, netRr,
            minimumNetRewardRisk, accepted, explanation
        )
    }
}
