package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic TRH-inspired structure model for research/backtesting only.
 *
 * Public descriptions attribute the zone to the last opposing origin pivot and the
 * structure-breaking candle. Exact proprietary pivot/zone thresholds are not public;
 * this implementation declares its own reproducible proxy and must not be represented
 * as Khosro's private indicator code.
 *
 * No future candles are used at decision time: swing pivots are only accepted after
 * RIGHT_BARS confirmation. A BOS is confirmed on candle close; retest entry is filled
 * on the next candle open. Stop/target collisions resolve to stop first.
 */
internal object ZoroTrhEngine {
    const val STRATEGY_ID = "TRH_ORIGIN_PIVOT_RETEST"

    data class Setup(
        val side: Int,
        val originIndex: Int,
        val breakIndex: Int,
        val zoneLow: Double,
        val zoneHigh: Double,
        val stopPrice: Double,
        val targetR: Double
    )

    data class Trade(
        val openedAt: Long,
        val closedAt: Long,
        val grossReturnPct: Double,
        val side: Int,
        val originIndex: Int,
        val breakIndex: Int
    )

    private const val LEFT_BARS = 3
    private const val RIGHT_BARS = 3
    private const val ATR_PERIOD = 14
    private const val IMPULSE_ATR = 1.15
    private const val MAX_ORIGIN_LOOKBACK = 12
    private const val RETEST_TIMEOUT = 32
    private const val TARGET_R = 2.0

    fun replay(bars: List<DoubleArray>): List<Trade> {
        if (bars.size < 100) return emptyList()
        val atrs = DoubleArray(bars.size) { atr(bars, it) }
        val pivotsHigh = mutableListOf<Pair<Int, Double>>()
        val pivotsLow = mutableListOf<Pair<Int, Double>>()
        val trades = mutableListOf<Trade>()
        var active: Setup? = null
        var lastExitIndex = 0
        var i = LEFT_BARS + RIGHT_BARS + 2

        while (i < bars.lastIndex - 2) {
            // A pivot at p is only added at p + RIGHT_BARS, so no look-ahead.
            val confirmed = i - RIGHT_BARS
            if (confirmed >= LEFT_BARS && isPivotHigh(bars, confirmed)) {
                pivotsHigh += confirmed to bars[confirmed][1]
            }
            if (confirmed >= LEFT_BARS && isPivotLow(bars, confirmed)) {
                pivotsLow += confirmed to bars[confirmed][2]
            }
            while (pivotsHigh.size > 30) pivotsHigh.removeAt(0)
            while (pivotsLow.size > 30) pivotsLow.removeAt(0)

            val current = active
            if (current != null && i > current.breakIndex) {
                if (i - current.breakIndex > RETEST_TIMEOUT) {
                    active = null
                } else if (i > lastExitIndex) {
                    val bar = bars[i]
                    val overlaps = bar[2] <= current.zoneHigh && bar[1] >= current.zoneLow
                    val rejected = if (current.side > 0) {
                        overlaps && bar[3] > bar[0] && bar[3] > current.zoneHigh
                    } else {
                        overlaps && bar[3] < bar[0] && bar[3] < current.zoneLow
                    }
                    if (rejected) {
                        val entryIndex = i + 1
                        val entry = bars[entryIndex][0]
                        val risk = abs(entry - current.stopPrice)
                        if (entry > 0.0 && risk > 0.0 && risk / entry < 0.04) {
                            val target = entry + current.side * risk * TARGET_R
                            var exitIndex = minOf(entryIndex + 48, bars.lastIndex - 1)
                            var exit = bars[exitIndex][3]
                            for (j in entryIndex..exitIndex) {
                                val b = bars[j]
                                val stopHit = if (current.side > 0) b[2] <= current.stopPrice else b[1] >= current.stopPrice
                                val targetHit = if (current.side > 0) b[1] >= target else b[2] <= target
                                if (stopHit) {
                                    exit = current.stopPrice
                                    exitIndex = j
                                    break
                                }
                                if (targetHit) {
                                    exit = target
                                    exitIndex = j
                                    break
                                }
                            }
                            val gross = current.side * (exit / entry - 1.0) * 100.0
                            val opened = bars[entryIndex][5].toLong()
                            val closed = bars[exitIndex][5].toLong() + 15L * 60L * 1000L
                            if (gross.isFinite() && closed >= opened) {
                                trades += Trade(opened, closed, gross, current.side,
                                    current.originIndex, current.breakIndex)
                            }
                            lastExitIndex = exitIndex
                            active = null
                        }
                    }
                }
            }

            if (active == null && i > lastExitIndex && atrs[i] > 0.0) {
                val bar = bars[i]
                val body = abs(bar[3] - bar[0])
                val latestHigh = pivotsHigh.lastOrNull { it.first < i }
                val latestLow = pivotsLow.lastOrNull { it.first < i }
                val bullishBos = latestHigh != null && bar[3] > latestHigh.second &&
                    bar[3] > bar[0] && body >= atrs[i] * IMPULSE_ATR
                val bearishBos = latestLow != null && bar[3] < latestLow.second &&
                    bar[3] < bar[0] && body >= atrs[i] * IMPULSE_ATR

                if (bullishBos) {
                    val origin = (max(0, i - MAX_ORIGIN_LOOKBACK) until i)
                        .lastOrNull { bars[it][3] < bars[it][0] }
                    if (origin != null) {
                        val distal = bars[origin][2]
                        val proximal = bar[2]
                        if (proximal > distal) {
                            active = Setup(1, origin, i, distal, proximal,
                                distal - atrs[i] * 0.15, TARGET_R)
                        }
                    }
                } else if (bearishBos) {
                    val origin = (max(0, i - MAX_ORIGIN_LOOKBACK) until i)
                        .lastOrNull { bars[it][3] > bars[it][0] }
                    if (origin != null) {
                        val distal = bars[origin][1]
                        val proximal = bar[1]
                        if (distal > proximal) {
                            active = Setup(-1, origin, i, proximal, distal,
                                distal + atrs[i] * 0.15, TARGET_R)
                        }
                    }
                }
            }
            i++
        }
        return trades
    }

    private fun isPivotHigh(bars: List<DoubleArray>, p: Int): Boolean {
        val value = bars[p][1]
        for (j in p - LEFT_BARS..p + RIGHT_BARS) {
            if (j != p && bars[j][1] >= value) return false
        }
        return true
    }

    private fun isPivotLow(bars: List<DoubleArray>, p: Int): Boolean {
        val value = bars[p][2]
        for (j in p - LEFT_BARS..p + RIGHT_BARS) {
            if (j != p && bars[j][2] <= value) return false
        }
        return true
    }

    private fun atr(bars: List<DoubleArray>, end: Int): Double {
        if (end < ATR_PERIOD + 1) return 0.0
        return (end - ATR_PERIOD + 1..end).map { i ->
            max(bars[i][1] - bars[i][2],
                max(abs(bars[i][1] - bars[i - 1][3]), abs(bars[i][2] - bars[i - 1][3])))
        }.average()
    }
}
