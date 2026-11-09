package com.cryptoaipro.monitor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** Randomized chronological replay of the app's multi-timeframe technical engine and neural vote. */
internal object WolfHistoricalBacktestEngine {
    private const val PREFS = "hope_hunter_historical_backtest"
    private const val KEY = "latest_v2"
    private const val COST_PCT_NOTIONAL_ROUND_TRIP = 0.10
    private const val SYMBOL_LIMIT = 2
    private const val SAMPLES_PER_SYMBOL = 6
    private const val WARMUP_BARS = 230
    private const val DAY_MS = 86_400_000L

    data class StrategyStats(
        val trades: Int = 0, val wins: Int = 0, val losses: Int = 0, val flats: Int = 0,
        val winRate: Double = 0.0, val netReturnPct: Double = 0.0,
        val profitFactor: Double = 0.0, val maxDrawdownPct: Double = 0.0
    )
    data class Snapshot(
        val completedAt: Long, val durationMs: Long, val symbols: List<String>,
        val samples: Int, val technical: StrategyStats, val neural: StrategyStats,
        val agreement: StrategyStats, val agreements: Int, val disagreements: Int,
        val neuralWins: Int, val technicalWins: Int, val sampleWindowStart: Long,
        val sampleWindowEnd: Long, val errors: List<String>
    ) {
        companion object {
            fun empty() = Snapshot(0L, 0L, emptyList(), 0, StrategyStats(), StrategyStats(),
                StrategyStats(), 0, 0, 0, 0, 0L, 0L, emptyList())
        }
    }

    private data class PaperTrade(val pnlPct: Double)
    private class Tally {
        val trades = mutableListOf<PaperTrade>()
        fun stats(): StrategyStats {
            val wins = trades.count { it.pnlPct > 0.0 }
            val losses = trades.count { it.pnlPct < 0.0 }
            val flats = trades.size - wins - losses
            val grossWin = trades.filter { it.pnlPct > 0.0 }.sumOf { it.pnlPct }
            val grossLoss = abs(trades.filter { it.pnlPct < 0.0 }.sumOf { it.pnlPct })
            var equity = 100.0
            var peak = 100.0
            var drawdown = 0.0
            trades.forEach {
                equity *= (1.0 + it.pnlPct / 100.0).coerceAtLeast(0.0)
                peak = max(peak, equity)
                if (peak > 0.0) drawdown = max(drawdown, (peak - equity) / peak * 100.0)
            }
            return StrategyStats(
                trades.size, wins, losses, flats,
                if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size * 100.0,
                trades.fold(1.0) { acc, t -> acc * (1.0 + t.pnlPct / 100.0) }.let { (it - 1.0) * 100.0 },
                if (grossLoss <= 1e-9) if (grossWin > 0.0) 99.0 else 0.0 else grossWin / grossLoss,
                drawdown
            )
        }
    }

    suspend fun run(context: Context, candidates: List<String>): Snapshot = withContext(Dispatchers.IO) {
        val symbols = candidates.distinct().take(SYMBOL_LIMIT)
        require(symbols.isNotEmpty()) { "ابتدا اسکن بازار را اجرا کن تا نمادهای منتخب مشخص شوند." }
        val started = System.currentTimeMillis()
        val random = Random(System.nanoTime())
        val technicalTally = Tally()
        val neuralTally = Tally()
        val agreementTally = Tally()
        val errors = mutableListOf<String>()
        var sampleCount = 0
        var agreements = 0
        var disagreements = 0
        var neuralWins = 0
        var technicalWins = 0
        var earliest = Long.MAX_VALUE
        var latest = 0L

        for (symbol in symbols) {
            currentCoroutineContext().ensureActive()
            try {
                // Every run chooses a new historical anchor, not the latest/fixed chart segment.
                val anchor = System.currentTimeMillis() - random.nextLong(45L * DAY_MS, 180L * DAY_MS)
                val frames = listOf(
                    Triple("5m", 5L * 60_000L, "micro"),
                    Triple("15m", 15L * 60_000L, "short"),
                    Triple("1h", 60L * 60_000L, "confirm"),
                    Triple("4h", 4L * 60L * 60_000L, "trend")
                )
                val history = LinkedHashMap<String, List<DoubleArray>>()
                for ((interval, intervalMs, _) in frames) {
                    currentCoroutineContext().ensureActive()
                    history[interval] = BinanceMonitor.historicalCandles(
                        symbol, interval, anchor - WARMUP_BARS * intervalMs, 1000
                    )
                }
                val micro = history.getValue("5m")
                val sampleIndexes = (250 until (micro.size - 100).coerceAtLeast(251))
                    .toList().shuffled(random).take(SAMPLES_PER_SYMBOL).sorted()
                for (idx in sampleIndexes) {
                    currentCoroutineContext().ensureActive()
                    val decisionTime = micro[idx][5].toLong() + 5L * 60_000L
                    val microClosed = micro.filter { it[5].toLong() + 5L * 60_000L <= decisionTime }.takeLast(500)
                    val short = history.getValue("15m").filter { it[5].toLong() + 15L * 60_000L <= decisionTime }.takeLast(500)
                    val confirm = history.getValue("1h").filter { it[5].toLong() + 60L * 60_000L <= decisionTime }.takeLast(500)
                    val trend = history.getValue("4h").filter { it[5].toLong() + 4L * 60L * 60_000L <= decisionTime }.takeLast(500)
                    if (microClosed.size < 210 || short.size < 210 || confirm.size < 210 || trend.size < 210) continue
                    val price = microClosed.last()[3]
                    val technical = BinanceMonitor.analyzeHistoricalWindow(
                        symbol, short, confirm, trend, microClosed, price
                    )
                    val neuralReviewed = applyWolfNeuralScoring(context, listOf(technical)).first()
                    val technicalSide = technical.technicalDirection
                    val neuralSide = neuralReviewed.neuralDirection
                    val isTech = technicalSide == "LONG_BIAS" || technicalSide == "SHORT_BIAS"
                    val isNeural = neuralSide == "LONG_BIAS" || neuralSide == "SHORT_BIAS"
                    if (isTech && isNeural && technicalSide == neuralSide && neuralReviewed.neuralScore >= 55) agreements++
                    else if (isTech && isNeural && technicalSide != neuralSide) disagreements++

                    val entryIndex = idx + 1
                    if (entryIndex >= micro.lastIndex) continue
                    val entryBar = micro[entryIndex]
                    val entry = entryBar[0]
                    if (!entry.isFinite() || entry <= 0.0) continue
                    val timeframe = technical.tradeTimeframe
                    val holdBars = when (timeframe) { "4h" -> 144; "1h" -> 72; else -> 36 }
                    val leverage = neuralReviewed.leverage.coerceIn(1, 5)
                    val risk = maxOf(abs(technical.price - technical.stop), technical.atr * 1.5, entry * 0.002)
                    val reward = maxOf(abs(technical.tp1 - technical.price), risk)
                    fun replay(side: String): Double? {
                        if (side != "LONG_BIAS" && side != "SHORT_BIAS") return null
                        val long = side == "LONG_BIAS"
                        val stop = if (long) entry - risk else entry + risk
                        val target = if (long) entry + reward else entry - reward
                        val end = minOf(micro.lastIndex, entryIndex + holdBars)
                        var exit = micro[end][3]
                        for (j in entryIndex..end) {
                            val bar = micro[j]
                            val stopHit = if (long) bar[2] <= stop else bar[1] >= stop
                            val targetHit = if (long) bar[1] >= target else bar[2] <= target
                            // If both levels occur within one OHLC candle, assume the adverse stop filled first.
                            if (stopHit) { exit = stop; break }
                            if (targetHit) { exit = target; break }
                        }
                        val gross = if (long) (exit / entry - 1.0) * 100.0
                            else (entry / exit - 1.0) * 100.0
                        return gross * leverage - COST_PCT_NOTIONAL_ROUND_TRIP * leverage
                    }
                    replay(technicalSide)?.let { technicalTally.trades.add(PaperTrade(it)) }
                    replay(neuralSide)?.let { neuralTally.trades.add(PaperTrade(it)) }
                    // Combined/production strategy only takes a side when the live quality gate agrees.
                    if (neuralReviewed.direction == "LONG_BIAS" || neuralReviewed.direction == "SHORT_BIAS") {
                        replay(neuralReviewed.direction)?.let { agreementTally.trades.add(PaperTrade(it)) }
                    }
                    sampleCount++
                }
                earliest = minOf(earliest, micro.firstOrNull()?.get(5)?.toLong() ?: anchor)
                latest = maxOf(latest, micro.lastOrNull()?.get(5)?.toLong() ?: anchor)
            } catch (e: Exception) {
                errors.add("$symbol: " + (e.message ?: e.javaClass.simpleName).take(180))
            }
        }
        val techStats = technicalTally.stats()
        val neuralStats = neuralTally.stats()
        val agreementStats = agreementTally.stats()
        val snapshot = Snapshot(
            System.currentTimeMillis(), System.currentTimeMillis() - started, symbols, sampleCount,
            techStats, neuralStats, agreementStats, agreements, disagreements,
            when {
                neuralStats.netReturnPct > techStats.netReturnPct -> 1
                neuralStats.netReturnPct < techStats.netReturnPct -> 0
                else -> -1
            },
            when {
                techStats.netReturnPct > neuralStats.netReturnPct -> 1
                techStats.netReturnPct < neuralStats.netReturnPct -> 0
                else -> -1
            },
            if (earliest == Long.MAX_VALUE) 0L else earliest, latest, errors
        )
        save(context, snapshot)
        snapshot
    }

    fun load(context: Context): Snapshot {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return Snapshot.empty()
        return runCatching {
            val o = JSONObject(raw)
            fun readStats(key: String): StrategyStats {
                val v = o.optJSONObject(key) ?: JSONObject()
                return StrategyStats(v.optInt("trades"), v.optInt("wins"), v.optInt("losses"),
                    v.optInt("flats"), v.optDouble("winRate"), v.optDouble("netReturnPct"),
                    v.optDouble("profitFactor"), v.optDouble("maxDrawdownPct"))
            }
            val syms = o.optJSONArray("symbols") ?: JSONArray()
            val errs = o.optJSONArray("errors") ?: JSONArray()
            Snapshot(o.optLong("completedAt"), o.optLong("durationMs"),
                (0 until syms.length()).map { syms.optString(it) }, o.optInt("samples"),
                readStats("technical"), readStats("neural"), readStats("agreement"),
                o.optInt("agreements"), o.optInt("disagreements"), o.optInt("neuralWins"),
                o.optInt("technicalWins"), o.optLong("sampleWindowStart"), o.optLong("sampleWindowEnd"),
                (0 until errs.length()).map { errs.optString(it) })
        }.getOrDefault(Snapshot.empty())
    }

    private fun save(context: Context, s: Snapshot) {
        fun stats(v: StrategyStats) = JSONObject().apply {
            put("trades", v.trades); put("wins", v.wins); put("losses", v.losses); put("flats", v.flats)
            put("winRate", v.winRate); put("netReturnPct", v.netReturnPct)
            put("profitFactor", v.profitFactor); put("maxDrawdownPct", v.maxDrawdownPct)
        }
        val o = JSONObject().apply {
            put("completedAt", s.completedAt); put("durationMs", s.durationMs)
            put("symbols", JSONArray(s.symbols)); put("samples", s.samples)
            put("technical", stats(s.technical)); put("neural", stats(s.neural)); put("agreement", stats(s.agreement))
            put("agreements", s.agreements); put("disagreements", s.disagreements)
            put("neuralWins", s.neuralWins); put("technicalWins", s.technicalWins)
            put("sampleWindowStart", s.sampleWindowStart); put("sampleWindowEnd", s.sampleWindowEnd)
            put("errors", JSONArray(s.errors))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa", "IR")).format(Date(timestamp))
}
