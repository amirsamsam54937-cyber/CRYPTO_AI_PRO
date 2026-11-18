package com.cryptoaipro.monitor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Historical OHLCV replay for explicit, deterministic hypotheses.
 * Entries are filled on the next candle open. If stop and target are both touched
 * in one candle, stop is assumed first (conservative). No exchange orders are sent.
 */
internal object ZoroStrategyReplayEngine {
    data class Snapshot(
        val completedAt: Long,
        val symbols: List<String>,
        val candleCount: Int,
        val tradeCount: Int,
        val results: Map<String, ZoroStrategyLab.WalkForward>,
        val errors: List<String>
    )

    private data class Signal(val side: Int, val stopDistance: Double)
    private data class ReplayTrade(val trade: ZoroStrategyLab.ClosedTrade, val exitIndex: Int)

    private val costs = ZoroStrategyLab.CostModel(
        feePerSidePct = 0.06,
        slippagePerSidePct = 0.02,
        fundingPctPerRoundTrip = 0.01
    )

    suspend fun run(context: Context, symbols: List<String>): Snapshot = withContext(Dispatchers.IO) {
        val selected = symbols.map { it.trim().uppercase() }.filter { it.matches(Regex("[A-Z0-9_]{3,30}")) }
            .distinct().take(8)
        require(selected.isNotEmpty()) { "ابتدا اسکن XT را اجرا کن تا نمادهای معتبر انتخاب شوند." }
        val start = System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1000L
        val loaded = coroutineScope {
            selected.map { symbol ->
                async {
                    symbol to runCatching { XtFuturesMonitor.historicalCandles(symbol, "15m", start, 9000) }
                }
            }.awaitAll()
        }
        val errors = mutableListOf<String>()
        val byStrategy = mutableMapOf<String, MutableList<ZoroStrategyLab.ClosedTrade>>()
        var candlesTotal = 0
        loaded.forEach { (symbol, result) ->
            val candles = result.getOrElse {
                errors += "$symbol: ${it.message ?: "دریافت تاریخچه ناموفق"}"
                emptyList()
            }
            candlesTotal += candles.size
            if (candles.size < 300) {
                errors += "$symbol: برای آزمون معتبر حداقل ۳۰۰ کندل بسته لازم است."
            } else {
                replaySymbol(symbol, candles).forEach { (strategy, trades) ->
                    byStrategy.getOrPut(strategy) { mutableListOf() }.addAll(trades)
                }
            }
        }
        val grouped = byStrategy.mapValues { (_, trades) ->
            ZoroStrategyLab.walkForward(trades, costs, minTrainTrades = 30, minTestTrades = 15)
        }
        Snapshot(System.currentTimeMillis(), selected, candlesTotal,
            byStrategy.values.sumOf { it.size }, grouped, errors)
    }

    private fun replaySymbol(symbol: String, candles: List<DoubleArray>): Map<String, List<ZoroStrategyLab.ClosedTrade>> {
        val ids = listOf("HTF_TREND_PULLBACK", "RETEST_CONTINUATION", "FAILED_BREAKOUT", "RANGE_MEAN_REVERSION")
        val out = ids.associateWith { mutableListOf<ZoroStrategyLab.ClosedTrade>() }
        val closes = candles.map { it[3] }
        val atrs = (0 until candles.size).map { i -> atr(candles, i, 14) }
        val ema20 = emaSeries(closes, 20)
        val ema50 = emaSeries(closes, 50)
        ids.forEach { id ->
            var i = 60
            while (i < candles.size - 30) {
                val signal = signal(id, candles, closes, atrs, ema20, ema50, i)
                if (signal == null) {
                    i++
                    continue
                }
                val entryIndex = i + 1
                val entry = candles[entryIndex][0]
                if (!entry.isFinite() || entry <= 0.0 || signal.stopDistance <= 0.0) { i++; continue }
                val stop = entry - signal.side * signal.stopDistance
                val target = entry + signal.side * signal.stopDistance * 1.8
                var exitPrice: Double? = null
                var exitIndex = minOf(entryIndex + 24, candles.lastIndex - 1)
                for (j in entryIndex..exitIndex) {
                    val bar = candles[j]
                    val stopHit = if (signal.side > 0) bar[2] <= stop else bar[1] >= stop
                    val targetHit = if (signal.side > 0) bar[1] >= target else bar[2] <= target
                    if (stopHit) { exitPrice = stop; exitIndex = j; break }
                    if (targetHit) { exitPrice = target; exitIndex = j; break }
                }
                val exit = exitPrice ?: candles[exitIndex][3]
                val gross = signal.side * (exit / entry - 1.0) * 100.0
                val openedAt = candles[entryIndex][5].toLong()
                val closedAt = candles[exitIndex][5].toLong() + 15L * 60L * 1000L
                if (closedAt >= openedAt && gross.isFinite()) {
                    out.getValue(id).add(ZoroStrategyLab.ClosedTrade(id, openedAt, closedAt, gross))
                }
                i = max(i + 1, exitIndex + 1)
            }
        }
        return out.mapValues { it.value.toList() }
    }

    private fun signal(
        id: String, bars: List<DoubleArray>, closes: List<Double>,
        atrs: List<Double>, e20: List<Double>, e50: List<Double>, i: Int
    ): Signal? {
        val bar = bars[i]
        val prev = bars[i - 1]
        val atr = atrs[i]
        if (!atr.isFinite() || atr <= 0.0) return null
        val prior = bars.subList(i - 20, i)
        val priorHigh = prior.maxOf { it[1] }
        val priorLow = prior.minOf { it[2] }
        val tolerance = atr * 0.18
        val bullish = bar[3] > bar[0]
        val bearish = bar[3] < bar[0]
        return when (id) {
            "HTF_TREND_PULLBACK" -> when {
                closes[i] > e20[i] && e20[i] > e50[i] && bar[2] <= e20[i] + tolerance && bullish ->
                    Signal(1, max(atr * 1.2, bar[3] * 0.002))
                closes[i] < e20[i] && e20[i] < e50[i] && bar[1] >= e20[i] - tolerance && bearish ->
                    Signal(-1, max(atr * 1.2, bar[3] * 0.002))
                else -> null
            }
            "RETEST_CONTINUATION" -> {
                val breakoutHigh = bars.subList(i - 21, i - 1).maxOf { it[1] }
                val breakoutLow = bars.subList(i - 21, i - 1).minOf { it[2] }
                when {
                    prev[3] > breakoutHigh && bar[2] <= breakoutHigh + tolerance &&
                        bar[3] > breakoutHigh && bullish -> Signal(1, max(atr * 1.2, bar[3] * 0.002))
                    prev[3] < breakoutLow && bar[1] >= breakoutLow - tolerance &&
                        bar[3] < breakoutLow && bearish -> Signal(-1, max(atr * 1.2, bar[3] * 0.002))
                    else -> null
                }
            }
            "FAILED_BREAKOUT" -> when {
                bar[1] > priorHigh && bar[3] < priorHigh && bearish ->
                    Signal(-1, max(atr * 1.2, bar[3] * 0.002))
                bar[2] < priorLow && bar[3] > priorLow && bullish ->
                    Signal(1, max(atr * 1.2, bar[3] * 0.002))
                else -> null
            }
            "RANGE_MEAN_REVERSION" -> {
                val width = priorHigh - priorLow
                if (width <= atr * 1.5) null
                else when {
                    bar[3] <= priorLow + width * 0.18 && bullish ->
                        Signal(1, max(atr * 1.2, bar[3] * 0.002))
                    bar[3] >= priorHigh - width * 0.18 && bearish ->
                        Signal(-1, max(atr * 1.2, bar[3] * 0.002))
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun atr(bars: List<DoubleArray>, end: Int, period: Int): Double {
        if (end < period + 1) return 0.0
        return (end - period + 1..end).map { i ->
            max(bars[i][1] - bars[i][2],
                max(abs(bars[i][1] - bars[i - 1][3]), abs(bars[i][2] - bars[i - 1][3])))
        }.average()
    }

    private fun emaSeries(values: List<Double>, period: Int): List<Double> {
        val alpha = 2.0 / (period + 1.0)
        val result = MutableList(values.size) { 0.0 }
        if (values.isEmpty()) return result
        result[0] = values[0]
        for (i in 1 until values.size) result[i] = alpha * values[i] + (1.0 - alpha) * result[i - 1]
        return result
    }
}
