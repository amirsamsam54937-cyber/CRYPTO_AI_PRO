package com.cryptoaipro.monitor

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Five-stage profit-lock governor shared by paper and XT live position management.
 * It only tightens an existing stop; it never increases size or moves a stop away
 * from profit. The fee buffer is expressed in quote-price units.
 */
internal object ZoroProfitLockEngine {
    data class Plan(
        val stage: Int,
        val triggerR: Double,
        val lockedR: Double,
        val favorableR: Double,
        val stopPrice: Double,
        val shouldUpdate: Boolean
    )

    private val stages = listOf(
        1.0 to 0.0,
        1.5 to 0.5,
        2.0 to 1.0,
        2.5 to 1.5,
        3.0 to 2.0
    )

    fun plan(
        side: String,
        entry: Double,
        initialRisk: Double,
        favorableExtreme: Double,
        mark: Double,
        currentStop: Double,
        feeBufferPrice: Double,
        pricePrecision: Int
    ): Plan? {
        val isLong = side.equals("LONG", true) || side == "LONG_BIAS"
        val isShort = side.equals("SHORT", true) || side == "SHORT_BIAS"
        if (!isLong && !isShort) return null
        if (listOf(entry, initialRisk, favorableExtreme, mark, currentStop, feeBufferPrice)
                .any { !it.isFinite() } ||
            entry <= 0.0 || initialRisk <= 0.0 || favorableExtreme <= 0.0 ||
            mark <= 0.0 || currentStop <= 0.0 || feeBufferPrice < 0.0) return null

        val favorableR = if (isLong) (favorableExtreme - entry) / initialRisk
            else (entry - favorableExtreme) / initialRisk
        val active = stages.lastOrNull { favorableR >= it.first } ?: return null
        val stage = stages.indexOf(active) + 1
        val lockedR = active.second

        // Five ratchets: fee-adjusted break-even, then lock +0.5R, +1R, +1.5R, +2R.
        val lockedStop = if (isLong) entry + initialRisk * lockedR + feeBufferPrice
            else entry - initialRisk * lockedR - feeBufferPrice

        // Once stage 5 is reached, preserve a one-R trailing gap behind the best excursion.
        val rawCandidate = if (stage >= 5) {
            val trailing = if (isLong) favorableExtreme - initialRisk
                else favorableExtreme + initialRisk
            if (isLong) max(lockedStop, trailing) else min(lockedStop, trailing)
        } else lockedStop

        val precision = pricePrecision.coerceIn(0, 12)
        val rounding = if (isLong) RoundingMode.DOWN else RoundingMode.UP
        val candidate = BigDecimal.valueOf(rawCandidate).setScale(precision, rounding).toDouble()
        val tick = Math.pow(10.0, -precision.toDouble())
        val improves = if (isLong) candidate > currentStop + tick * 0.5
            else candidate < currentStop - tick * 0.5
        val remainsOnSafeSide = if (isLong) candidate < mark - tick
            else candidate > mark + tick
        return Plan(
            stage = stage,
            triggerR = active.first,
            lockedR = lockedR,
            favorableR = favorableR,
            stopPrice = candidate,
            shouldUpdate = improves && remainsOnSafeSide
        )
    }
}
