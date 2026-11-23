package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Candle-derived liquidity map. It estimates likely stop clusters from repeated swing
 * highs/lows; it never claims access to hidden stop orders.
 */
internal object ZoroLiquidityMapEngine {
    data class Candle(
        val time: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double = 0.0
    ) {
        fun valid(): Boolean = time > 0 && listOf(open, high, low, close, volume).all { it.isFinite() } &&
            low > 0.0 && high >= max(open, close) && low <= min(open, close) &&
            open > 0.0 && close > 0.0 && volume >= 0.0
    }

    enum class Side { BUY_SIDE, SELL_SIDE }
    enum class Event { NONE, SWEEP_AND_RECLAIM, BREAK_AND_HOLD }

    data class Pool(
        val side: Side,
        val price: Double,
        val lowerBound: Double,
        val upperBound: Double,
        val touches: Int,
        val strength: Int,
        val distancePercent: Double,
        val event: Event,
        val detectedAt: Long
    )

    data class Snapshot(
        val pools: List<Pool>,
        val nearestBuySide: Pool?,
        val nearestSellSide: Pool?,
        val sweptBuySide: Boolean,
        val sweptSellSide: Boolean,
        val summary: String
    )

    fun analyze(input: List<Candle>, lookback: Int = 160): Snapshot {
        val candles = input.filter { it.valid() }.takeLast(lookback.coerceIn(30, 500))
        if (candles.size < 25) return Snapshot(emptyList(), null, null, false, false,
            "برای نقشه نقدینگی حداقل ۲۵ کندل معتبر لازم است")
        val current = candles.last()
        val atr = averageTrueRange(candles, 14).takeIf { it > 0.0 } ?: (current.close * 0.002)
        val tolerance = max(atr * 0.18, current.close * 0.00035)
        val pivotsHigh = mutableListOf<Pair<Int, Double>>()
        val pivotsLow = mutableListOf<Pair<Int, Double>>()
        for (i in 2 until candles.lastIndex - 1) {
            val c = candles[i]
            if (c.high >= candles[i-1].high && c.high >= candles[i-2].high &&
                c.high > candles[i+1].high) pivotsHigh += i to c.high
            if (c.low <= candles[i-1].low && c.low <= candles[i-2].low &&
                c.low < candles[i+1].low) pivotsLow += i to c.low
        }
        val pools = mutableListOf<Pool>()
        fun build(side: Side, pivots: List<Pair<Int, Double>>) {
            val clusters = mutableListOf<MutableList<Pair<Int, Double>>>()
            pivots.sortedBy { it.second }.forEach { pivot ->
                val cluster = clusters.firstOrNull { group ->
                    abs(group.map { it.second }.average() - pivot.second) <= tolerance
                }
                if (cluster == null) clusters += mutableListOf(pivot) else cluster += pivot
            }
            clusters.filter { it.size >= 2 }.forEach { group ->
                val level = group.map { it.second }.average()
                val firstIndex = group.minOf { it.first }
                val lastIndex = group.maxOf { it.first }
                if (candles.lastIndex - lastIndex < 2) return@forEach
                val recent = candles.takeLast(4)
                val event = when (side) {
                    Side.BUY_SIDE -> when {
                        recent.last().high > level + tolerance && recent.last().close < level ->
                            Event.SWEEP_AND_RECLAIM
                        recent.last().close > level + tolerance -> Event.BREAK_AND_HOLD
                        else -> Event.NONE
                    }
                    Side.SELL_SIDE -> when {
                        recent.last().low < level - tolerance && recent.last().close > level ->
                            Event.SWEEP_AND_RECLAIM
                        recent.last().close < level - tolerance -> Event.BREAK_AND_HOLD
                        else -> Event.NONE
                    }
                }
                val distance = abs(level - current.close) / current.close * 100.0
                val recency = (100.0 - (candles.lastIndex - lastIndex).coerceAtMost(100)).toInt()
                val strength = (group.size * 18 + recency / 5 +
                    if (event == Event.SWEEP_AND_RECLAIM) 20 else 0).coerceIn(1, 100)
                pools += Pool(side, level, group.minOf { it.second } - tolerance / 2,
                    group.maxOf { it.second } + tolerance / 2, group.size, strength,
                    distance, event, candles[lastIndex].time)
            }
        }
        build(Side.BUY_SIDE, pivotsHigh)
        build(Side.SELL_SIDE, pivotsLow)
        val buy = pools.filter { it.side == Side.BUY_SIDE && it.event != Event.BREAK_AND_HOLD }
            .minByOrNull { abs(it.price - current.close) }
        val sell = pools.filter { it.side == Side.SELL_SIDE && it.event != Event.BREAK_AND_HOLD }
            .minByOrNull { abs(it.price - current.close) }
        val sweptBuy = pools.any { it.side == Side.BUY_SIDE && it.event == Event.SWEEP_AND_RECLAIM }
        val sweptSell = pools.any { it.side == Side.SELL_SIDE && it.event == Event.SWEEP_AND_RECLAIM }
        val summary = when {
            sweptBuy && sweptSell -> "هر دو سمت نقدینگی اخیراً جاروب شده؛ احتمال نوسان و تله دوطرفه"
            sweptBuy -> "نقدینگی بالای سقف‌ها جمع‌آوری و قیمت به زیر سطح برگشته است"
            sweptSell -> "نقدینگی زیر کف‌ها جمع‌آوری و قیمت به بالای سطح برگشته است"
            buy != null || sell != null -> "استخرهای احتمالی نقدینگی شناسایی شدند؛ منتظر واکنش قیمت"
            else -> "خوشه معتبر سقف/کف هم‌سطح شناسایی نشد"
        }
        return Snapshot(pools.sortedByDescending { it.strength }, buy, sell, sweptBuy, sweptSell, summary)
    }

    private fun averageTrueRange(candles: List<Candle>, period: Int): Double {
        val start = max(1, candles.size - period)
        val ranges = (start until candles.size).map { i ->
            val c = candles[i]
            max(c.high - c.low, max(abs(c.high - candles[i-1].close),
                abs(c.low - candles[i-1].close)))
        }
        return ranges.average().takeIf { it.isFinite() } ?: 0.0
    }
}
