package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max

/**
 * Confirmed, candle-close-only order-block detector.
 *
 * A block is considered only after a displacement candle closes beyond a prior
 * 12-candle swing range, with an ATR-sized body and volume expansion. The zone
 * is the last opposing candle before displacement. Invalidated zones are rejected.
 * This is a heuristic context feature, never a standalone trade signal.
 */
internal data class WolfOrderBlock(
    val direction: String = "NONE",
    val low: Double = 0.0,
    val high: Double = 0.0,
    val distanceAtr: Double = Double.POSITIVE_INFINITY,
    val sourceIndex: Int = -1
) {
    fun label(): String = when (direction) {
        "BULLISH" -> "بلاک سفارش صعودی"
        "BEARISH" -> "بلاک سفارش نزولی"
        else -> "بلاک سفارش معتبر شناسایی نشد"
    }
}

internal object WolfOrderBlockEngine {
    fun detect(candles: List<DoubleArray>, atr: Double): WolfOrderBlock {
        if (candles.size < 40 || !atr.isFinite() || atr <= 0.0) return WolfOrderBlock()
        if (candles.any { it.size < 5 }) return WolfOrderBlock()

        val last = candles.last()
        val price = last[3]
        if (!price.isFinite() || price <= 0.0) return WolfOrderBlock()
        val firstCandidate = max(20, candles.size - 60)
        var best: WolfOrderBlock? = null

        for (i in firstCandidate until candles.size) {
            val candle = candles[i]
            val open = candle[0]
            val high = candle[1]
            val low = candle[2]
            val close = candle[3]
            val volume = candle[4]
            val body = abs(close - open)
            val range = (high - low).coerceAtLeast(1e-12)
            if (body < atr * 0.90 || body / range < 0.55) continue

            val prior = candles.subList(i - 12, i)
            val priorHigh = prior.maxOf { it[1] }
            val priorLow = prior.minOf { it[2] }
            val averageVolume = prior.takeLast(20).map { it[4] }.average().coerceAtLeast(1e-12)
            if (volume < averageVolume * 1.10) continue

            val bullish = close > open && close > priorHigh
            val bearish = close < open && close < priorLow
            if (!bullish && !bearish) continue

            val opposingIndex = (i - 1 downTo max(0, i - 5)).firstOrNull { j ->
                if (bullish) candles[j][3] < candles[j][0] else candles[j][3] > candles[j][0]
            } ?: continue

            val opposing = candles[opposingIndex]
            val zoneLow: Double
            val zoneHigh: Double
            val direction: String
            if (bullish) {
                zoneLow = opposing[2]
                zoneHigh = max(opposing[0], opposing[3])
                direction = "BULLISH"
            } else {
                zoneLow = minOf(opposing[0], opposing[3])
                zoneHigh = opposing[1]
                direction = "BEARISH"
            }
            if (!zoneLow.isFinite() || !zoneHigh.isFinite() || zoneLow <= 0.0 || zoneHigh <= zoneLow) continue

            // A later candle close through the far edge invalidates the block.
            val invalidated = candles.drop(i + 1).any { later ->
                if (direction == "BULLISH") later[3] < zoneLow else later[3] > zoneHigh
            }
            if (invalidated) continue

            val distance = when {
                price in zoneLow..zoneHigh -> 0.0
                price > zoneHigh -> (price - zoneHigh) / atr
                else -> (zoneLow - price) / atr
            }
            val candidate = WolfOrderBlock(direction, zoneLow, zoneHigh, distance, i)
            if (best == null || candidate.sourceIndex > best.sourceIndex) best = candidate
        }
        return best ?: WolfOrderBlock()
    }
}
