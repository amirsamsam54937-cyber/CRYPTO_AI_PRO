package com.cryptoaipro.monitor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

/** Evenly sampled chronological replay across a paginated ~90-day XT history window. */
internal object WolfHistoricalBacktestEngine {
    private const val PREFS = "hope_hunter_historical_backtest"
    private const val KEY = "latest_v2"
    private const val COST_PCT_NOTIONAL_ROUND_TRIP = 0.10
    private const val SYMBOL_LIMIT = 8
    private const val SAMPLES_PER_SYMBOL = 72
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

    private data class PaperTrade(val pnlPct: Double, val entryTime: Long)
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
            trades.sortedBy { it.entryTime }.forEach {
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
                val technicalTally = Tally()
        val neuralTally = Tally()
        val agreementTally = Tally()
        val errors = mutableListOf<String>()
        var sampleCount = 0
        var neuralEvaluated = 0
        var neuralUnavailable = 0
        var agreements = 0
        var disagreements = 0
        var neuralWins = 0
        var technicalWins = 0
        var earliest = Long.MAX_VALUE
        var latest = 0L

        for (symbol in symbols) {
            currentCoroutineContext().ensureActive()
            try {
                // Use a stable daily anchor so repeated runs on the same day are reproducible.
                // The replay window is historical and never includes candles after each decision point.
                val anchor = (System.currentTimeMillis() / DAY_MS) * DAY_MS - 90L * DAY_MS
                val frames = listOf(
                    Triple("5m", 5L * 60_000L, "micro"),
                    Triple("15m", 15L * 60_000L, "short"),
                    Triple("1h", 60L * 60_000L, "confirm"),
                    Triple("4h", 4L * 60L * 60_000L, "trend")
                )
                val loadedFrames = coroutineScope {
                    frames.map { (interval, intervalMs, _) ->
                        async {
                            val historyLimit = when (interval) {
                                "5m" -> 27_000   // ~90 days plus warm-up
                                "15m" -> 9_000
                                "1h" -> 2_500
                                "4h" -> 800
                                else -> 1000
                            }
                            interval to XtFuturesMonitor.historicalCandles(
                                symbol, interval, anchor - WARMUP_BARS * intervalMs, historyLimit
                            )
                        }
                    }.awaitAll()
                }
                val history = loadedFrames.toMap()
                val micro = history.getValue("5m")
                val eligibleIndexes = (250 until (micro.size - 150).coerceAtLeast(251)).toList()
                // Evenly spaced chronological samples reduce random variance and preserve
                // coverage across the fetched window instead of clustering trades together.
                val sampleIndexes = if (eligibleIndexes.size <= SAMPLES_PER_SYMBOL) eligibleIndexes
                    else (0 until SAMPLES_PER_SYMBOL).map { slot ->
                        eligibleIndexes[(slot.toLong() * (eligibleIndexes.size - 1) / (SAMPLES_PER_SYMBOL - 1)).toInt()]
                    }.distinct()
                for (idx in sampleIndexes) {
                    currentCoroutineContext().ensureActive()
                    val decisionTime = micro[idx][5].toLong() + 5L * 60_000L
                    val microClosed = micro.filter { it[5].toLong() + 5L * 60_000L <= decisionTime }.takeLast(500)
                    val short = history.getValue("15m").filter { it[5].toLong() + 15L * 60_000L <= decisionTime }.takeLast(500)
                    val confirm = history.getValue("1h").filter { it[5].toLong() + 60L * 60_000L <= decisionTime }.takeLast(500)
                    val trend = history.getValue("4h").filter { it[5].toLong() + 4L * 60L * 60_000L <= decisionTime }.takeLast(500)
                    if (microClosed.size < 210 || short.size < 210 || confirm.size < 210 || trend.size < 210) continue
                    // Count every valid decision point before entry/side filters; this keeps the
                    // neural prediction denominator consistent with the number of evaluated points.
                    sampleCount++
                    val price = microClosed.last()[3]
                    val technical = MarketAnalysisEngine.analyzeHistoricalWindow(
                        symbol, short, confirm, trend, microClosed, price
                    )
                    // Keep the production gate intact, but score the neural model independently
                    // in research mode so an unvalidated model can still be measured offline.
                    WolfPretrainedEnsemble.initialize(context)
                    val researchPrediction = WolfPretrainedEnsemble.predictResearch(technical)
                                        val technicalSide = technical.technicalDirection
                    val neuralSide = researchPrediction?.predictedDirection ?: "NEUTRAL"
                    if (researchPrediction != null) neuralEvaluated++ else neuralUnavailable++
                    val isTech = technicalSide == "LONG_BIAS" || technicalSide == "SHORT_BIAS"
                    val isNeural = neuralSide == "LONG_BIAS" || neuralSide == "SHORT_BIAS"
                    if (isTech && isNeural && technicalSide == neuralSide && (researchPrediction?.strength ?: 0) >= 55) agreements++
                    else if (isTech && isNeural && technicalSide != neuralSide) disagreements++

                    val entryIndex = idx + 1
                    if (entryIndex >= micro.lastIndex) continue
                    val entryBar = micro[entryIndex]
                    val entry = entryBar[0]
                    if (!entry.isFinite() || entry <= 0.0) continue
                    // Each strategy must use its own risk geometry. The neural engine is trained
                    // on 1h candles, so its replay uses the 1h ATR rather than borrowing the
                    // technical engine's stop, target, timeframe and leverage.
                    val technicalRisk = abs(technical.price - technical.stop)
                    val technicalReward = abs(technical.tp1 - technical.price)
                    val technicalLeverage = technical.leverage.coerceIn(1, 5)

                    val neuralFrame = technical.neuralTechnical
                    val neuralAtrPct = neuralFrame?.atrPercent?.takeIf { it.isFinite() && it > 0.0 }
                    val neuralRisk = neuralAtrPct?.let { entry * it / 100.0 * 1.5 }
                    val neuralReward = neuralRisk?.times(2.0)
                    val neuralLeverage = when {
                        neuralAtrPct == null -> 1
                        neuralAtrPct >= 5.0 -> 1
                        neuralAtrPct >= 3.0 -> 2
                        neuralAtrPct >= 1.5 -> 3
                        else -> 4
                    }

                    val slippagePctPerSide = 0.02
                    fun replay(
                        side: String,
                        riskDistance: Double,
                        rewardDistance: Double,
                        leverage: Int,
                        holdBars: Int
                    ): Double? {
                        if (side != "LONG_BIAS" && side != "SHORT_BIAS") return null
                        if (!riskDistance.isFinite() || !rewardDistance.isFinite() ||
                            riskDistance <= 0.0 || rewardDistance <= 0.0 ||
                            rewardDistance / riskDistance < 1.0) return null
                        val long = side == "LONG_BIAS"
                        val rawEntry = entryBar[0]
                        if (!rawEntry.isFinite() || rawEntry <= 0.0) return null
                        val stop = if (long) rawEntry - riskDistance else rawEntry + riskDistance
                        val target = if (long) rawEntry + rewardDistance else rawEntry - rewardDistance
                        if (stop <= 0.0 || target <= 0.0) return null
                        // Adverse market-entry slippage; stop/target distances remain fixed for all portfolios.
                        val entryFill = rawEntry * (1.0 + (if (long) 1.0 else -1.0) * slippagePctPerSide / 100.0)
                        val end = minOf(micro.lastIndex, entryIndex + holdBars)
                        var rawExit = micro[end][3]
                        for (j in entryIndex..end) {
                            val bar = micro[j]
                            val stopHit = if (long) bar[2] <= stop else bar[1] >= stop
                            val targetHit = if (long) bar[1] >= target else bar[2] <= target
                            // Conservative OHLC ambiguity: if both levels hit, stop is assumed first.
                            if (stopHit) {
                                rawExit = if ((long && bar[0] < stop) || (!long && bar[0] > stop)) bar[0] else stop
                                break
                            }
                            if (targetHit) { rawExit = target; break }
                        }
                        val exitFill = rawExit * (1.0 + (if (long) -1.0 else 1.0) * slippagePctPerSide / 100.0)
                        if (entryFill <= 0.0 || exitFill <= 0.0) return null
                        val gross = if (long) (exitFill / entryFill - 1.0) * 100.0
                            else (entryFill / exitFill - 1.0) * 100.0
                        // Fee assumption is round-trip 0.10% notional; funding remains unmodelled.
                        return gross * leverage.coerceIn(1, 5) - COST_PCT_NOTIONAL_ROUND_TRIP * leverage.coerceIn(1, 5)
                    }

                    if (isTech) replay(technicalSide, technicalRisk, technicalReward, technicalLeverage, holdBarsFor(technical.tradeTimeframe))
                        ?.let { technicalTally.trades.add(PaperTrade(it, entryBar[5].toLong())) }
                    if (isNeural && neuralRisk != null && neuralReward != null) {
                        replay(neuralSide, neuralRisk, neuralReward, neuralLeverage, holdBarsFor("1h"))
                            ?.let { neuralTally.trades.add(PaperTrade(it, entryBar[5].toLong())) }
                    }
                    // Agreement is evaluated on the same entry timestamp with conservative shared
                    // risk: wider of the two stops, nearer of the two targets, lower leverage.
                    if (isTech && isNeural && technicalSide == neuralSide &&
                        (researchPrediction?.strength ?: 0) >= 55 &&
                        neuralRisk != null && neuralReward != null) {
                        val sharedRisk = max(technicalRisk, neuralRisk)
                        val sharedReward = minOf(technicalReward, neuralReward)
                        replay(technicalSide, sharedRisk, sharedReward, minOf(technicalLeverage, neuralLeverage), maxOf(holdBarsFor(technical.tradeTimeframe), holdBarsFor("1h")))
                            ?.let { agreementTally.trades.add(PaperTrade(it, entryBar[5].toLong())) }
                    }
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
        if (neuralEvaluated == 0) {
            errors.add("شبکه عصبی در هیچ نمونه‌ای پیش‌بینی مستقل نداد؛ وزن‌ها یا ورودی ۱ساعته در دسترس نیست.")
        } else {
            errors.add("پیش‌بینی‌های مستقل شبکه عصبی: $neuralEvaluated از $sampleCount نقطه ارزیابی شدند؛ حداقل قدرت رأی برای ورود به آزمایش ۵۵ از ۱۰۰ است.")
        }
        if (neuralEvaluated > 0 && !WolfPretrainedEnsemble.status().validatedForScoring) {
            errors.add("نتایج شبکه عصبی صرفاً پژوهشی و آزمایشی هستند؛ مدل هنوز مجوز اثرگذاری بر سیگنال زنده را ندارد.")
        }
        if (neuralUnavailable > 0) errors.add("$neuralUnavailable نمونه به‌دلیل نبود داده/وزن معتبر شبکه عصبی ارزیابی نشد.")
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

    private fun holdBarsFor(timeframe: String): Int = when (timeframe) {
        "4h" -> 12 * 48
        "1h" -> 12 * 12
        "15m" -> 12 * 3
        else -> 12
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
