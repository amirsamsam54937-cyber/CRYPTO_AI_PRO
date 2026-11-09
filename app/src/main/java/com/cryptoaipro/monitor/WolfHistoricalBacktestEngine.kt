package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Chronological 5m paper replay. No order execution; decisions only use candles available at that timestamp. */
internal object WolfHistoricalBacktestEngine {
    private const val PREFS = "hope_hunter_historical_backtest"
    private const val KEY = "latest_v1"
    private const val MAX_HOLD_BARS = 36
    private const val ROUND_TRIP_COST_PCT_NOTIONAL = 0.10

    data class Trade(
        val symbol: String, val side: String, val entryTime: Long, val exitTime: Long,
        val entry: Double, val exit: Double, val stop: Double, val tp1: Double,
        val leverage: Int, val netReturnPct: Double, val result: String,
        val technicalDirection: String, val neuralDirection: String, val neuralScore: Int
    )
    data class Snapshot(
        val completedAt: Long, val symbols: List<String>, val candlesPerSymbol: Int,
        val opportunities: Int, val accepted: Int, val rejectedDisagreement: Int,
        val wins: Int, val losses: Int, val breakeven: Int, val winRate: Double,
        val netReturnPct: Double, val profitFactor: Double, val maxDrawdownPct: Double,
        val trades: List<Trade>, val errors: List<String>
    ) {
        companion object {
            fun empty() = Snapshot(0L, emptyList(), 0, 0, 0, 0, 0, 0, 0,
                0.0, 0.0, 0.0, 0.0, emptyList(), emptyList())
        }
    }

    suspend fun run(context: Context, symbols: List<String>): Snapshot = withContext(Dispatchers.IO) {
        val unique = symbols.distinct().take(3)
        require(unique.isNotEmpty()) { "ابتدا اسکن بازار را اجرا کن تا نمادهای منتخب مشخص شوند." }
        val trades = mutableListOf<Trade>()
        val errors = mutableListOf<String>()
        var opportunities = 0
        var accepted = 0
        var rejectedDisagreement = 0
        val candleCounts = mutableListOf<Int>()

        for (symbol in unique) {
            try {
                val bars = BinanceMonitor.historicalFiveMinuteCandles(symbol, 1000)
                candleCounts.add(bars.size)
                var index = 210
                while (index < bars.size - 2) {
                    val history = bars.subList(0, index + 1)
                    val technical = BinanceMonitor.analyzeFiveMinuteWindow(symbol, history)
                    if (technical == null || technical.technicalDirection == "NEUTRAL") {
                        index++
                        continue
                    }
                    opportunities++
                    val reviewed = applyWolfNeuralScoring(context, listOf(technical)).first()
                    if (reviewed.direction != "LONG_BIAS" && reviewed.direction != "SHORT_BIAS") {
                        if (reviewed.agreementStatus.contains("اختلاف") ||
                            reviewed.neuralDirection != reviewed.technicalDirection) rejectedDisagreement++
                        index++
                        continue
                    }
                    accepted++
                    val isLong = reviewed.direction == "LONG_BIAS"
                    val entryBar = bars[index + 1]
                    val entry = entryBar[0]
                    if (!entry.isFinite() || entry <= 0.0) { index++; continue }
                    val stop = entry + (reviewed.stop - reviewed.price)
                    val target = entry + (reviewed.tp1 - reviewed.price)
                    if (if (isLong) !(stop < entry && target > entry) else !(stop > entry && target < entry)) {
                        index++
                        continue
                    }
                    var exit = entry
                    var exitIndex = index + 1
                    var result = "TIME"
                    val lastIndex = minOf(bars.lastIndex, index + MAX_HOLD_BARS)
                    for (j in (index + 1)..lastIndex) {
                        val bar = bars[j]
                        val stopHit = if (isLong) bar[2] <= stop else bar[1] >= stop
                        val targetHit = if (isLong) bar[1] >= target else bar[2] <= target
                        // Conservative tie-break: if TP and SL occur inside one 5m candle, count SL first.
                        if (stopHit) {
                            exit = if (isLong) minOf(bar[0], stop) else maxOf(bar[0], stop)
                            result = "SL"; exitIndex = j; break
                        }
                        if (targetHit) {
                            exit = target; result = "TP1"; exitIndex = j; break
                        }
                        exit = bar[3]
                        exitIndex = j
                    }
                    val rawPct = if (isLong) (exit / entry - 1.0) * 100.0
                        else (entry / exit - 1.0) * 100.0
                    val leverage = reviewed.leverage.coerceIn(1, 5)
                    // Conservative estimated taker fees + slippage, charged on leveraged notional.
                    val netPct = rawPct * leverage - ROUND_TRIP_COST_PCT_NOTIONAL * leverage
                    val outcome = when { netPct > 0.0 -> "WIN"; netPct < 0.0 -> "LOSS"; else -> "FLAT" }
                    trades.add(Trade(symbol, reviewed.direction, entryBar[5].toLong(),
                        bars[exitIndex][5].toLong(), entry, exit, stop, target, leverage,
                        netPct, outcome, reviewed.technicalDirection, reviewed.neuralDirection, reviewed.neuralScore))
                    index = max(index + 1, exitIndex + 1)
                }
            } catch (e: Exception) {
                errors.add(symbol + ": " + (e.message ?: e.javaClass.simpleName).take(180))
            }
        }
        val wins = trades.count { it.result == "WIN" }
        val losses = trades.count { it.result == "LOSS" }
        val flats = trades.count { it.result == "FLAT" }
        val positive = trades.filter { it.netReturnPct > 0.0 }.sumOf { it.netReturnPct }
        val negative = abs(trades.filter { it.netReturnPct < 0.0 }.sumOf { it.netReturnPct })
        var equity = 100.0
        var peak = 100.0
        var maxDd = 0.0
        trades.forEach { trade ->
            equity *= (1.0 + trade.netReturnPct / 100.0).coerceAtLeast(0.0)
            peak = max(peak, equity)
            if (peak > 0.0) maxDd = max(maxDd, (peak - equity) / peak * 100.0)
        }
        val result = Snapshot(
            System.currentTimeMillis(), unique, candleCounts.minOrNull() ?: 0,
            opportunities, accepted, rejectedDisagreement, wins, losses, flats,
            if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size * 100.0,
            trades.sumOf { it.netReturnPct },
            if (negative <= 1e-9) if (positive > 0.0) 99.0 else 0.0 else positive / negative,
            maxDd, trades.takeLast(12).asReversed(), errors
        )
        save(context, result)
        result
    }

    fun load(context: Context): Snapshot {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return Snapshot.empty()
        return runCatching {
            val o = JSONObject(raw)
            val tradesJson = o.optJSONArray("trades") ?: JSONArray()
            val trades = (0 until tradesJson.length()).mapNotNull { i ->
                val t = tradesJson.optJSONObject(i) ?: return@mapNotNull null
                Trade(t.optString("symbol"), t.optString("side"), t.optLong("entryTime"), t.optLong("exitTime"),
                    t.optDouble("entry"), t.optDouble("exit"), t.optDouble("stop"), t.optDouble("tp1"),
                    t.optInt("leverage", 1), t.optDouble("netReturnPct"), t.optString("result"),
                    t.optString("technicalDirection"), t.optString("neuralDirection"), t.optInt("neuralScore"))
            }
            val sym = o.optJSONArray("symbols") ?: JSONArray()
            val err = o.optJSONArray("errors") ?: JSONArray()
            Snapshot(o.optLong("completedAt"), (0 until sym.length()).map { sym.optString(it) },
                o.optInt("candlesPerSymbol"), o.optInt("opportunities"), o.optInt("accepted"),
                o.optInt("rejectedDisagreement"), o.optInt("wins"), o.optInt("losses"),
                o.optInt("breakeven"), o.optDouble("winRate"), o.optDouble("netReturnPct"),
                o.optDouble("profitFactor"), o.optDouble("maxDrawdownPct"), trades,
                (0 until err.length()).map { err.optString(it) })
        }.getOrDefault(Snapshot.empty())
    }

    private fun save(context: Context, s: Snapshot) {
        val o = JSONObject().apply {
            put("completedAt", s.completedAt); put("symbols", JSONArray(s.symbols))
            put("candlesPerSymbol", s.candlesPerSymbol); put("opportunities", s.opportunities)
            put("accepted", s.accepted); put("rejectedDisagreement", s.rejectedDisagreement)
            put("wins", s.wins); put("losses", s.losses); put("breakeven", s.breakeven)
            put("winRate", s.winRate); put("netReturnPct", s.netReturnPct)
            put("profitFactor", s.profitFactor); put("maxDrawdownPct", s.maxDrawdownPct)
            put("trades", JSONArray().apply { s.trades.forEach { t ->
                put(JSONObject().apply {
                    put("symbol", t.symbol); put("side", t.side); put("entryTime", t.entryTime)
                    put("exitTime", t.exitTime); put("entry", t.entry); put("exit", t.exit)
                    put("stop", t.stop); put("tp1", t.tp1); put("leverage", t.leverage)
                    put("netReturnPct", t.netReturnPct); put("result", t.result)
                    put("technicalDirection", t.technicalDirection); put("neuralDirection", t.neuralDirection)
                    put("neuralScore", t.neuralScore)
                })
            }})
            put("errors", JSONArray(s.errors))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa", "IR")).format(Date(timestamp))
}
