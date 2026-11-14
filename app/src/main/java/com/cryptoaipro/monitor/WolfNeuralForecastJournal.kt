package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/**
 * Scores each of the ten 1h-trained neural specialists against its own 6h forward outcome.
 * This journal is intentionally separate from the 15m paper-signal outcome journal.
 * Neutral-band outcomes are recorded but excluded from directional skill metrics.
 */
internal object WolfNeuralForecastJournal {
    private const val PREFS = "wolf_neural_forecast_journal"
    private const val KEY = "forecasts_v1"
    private const val MAX_RECORDS = 2500
    private const val HORIZON_MS = 6L * WolfCandleHorizon.HOUR_MS
    private const val MAX_BASE_AGE_MS = 2L * WolfCandleHorizon.HOUR_MS
    private const val EXPERTS = 10

    data class MemberStats(
        val index: Int,
        val samples: Int,
        val correct: Int,
        val accuracy: Double,
        val brier: Double,
        val logLoss: Double,
        val coverage: Double
    )

    data class Snapshot(
        val evaluated: Int,
        val neutral: Int,
        val pending: Int,
        val stale: Int,
        val ensembleAccuracy: Double,
        val ensembleBrier: Double,
        val members: List<MemberStats>,
        val bestMember: MemberStats?
    )

    @Synchronized
    fun processScan(context: Context, signals: List<MarketSignal>, now: Long = System.currentTimeMillis()): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val source = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val rows = (0 until source.length()).mapNotNull { source.optJSONObject(it) }.toMutableList()
        val bySymbol = signals.associateBy { it.symbol }

        rows.forEach { record ->
            if (record.optBoolean("closed")) return@forEach
            val signal = bySymbol[record.optString("symbol")] ?: return@forEach
            val bars = WolfCandleHorizon.closedHourlyCandles(signal.neuralCandles, now)
            val baseTime = record.optLong("base_candle_open")
            val targetTime = baseTime + HORIZON_MS
            val target = bars.firstOrNull { it[5].toLong() == targetTime }
            if (target != null) {
                val baseClose = record.optDouble("base_close", Double.NaN)
                val futureClose = target[3]
                val label = runCatching {
                    WolfCandleHorizon.directionalOutcome(baseClose, futureClose)
                }.getOrNull()
                record.put("closed", true)
                record.put("future_close", futureClose)
                record.put("evaluated_at", now)
                if (label == null) {
                    record.put("neutral", true)
                } else {
                    record.put("neutral", false)
                    record.put("outcome", if (label) 1 else 0)
                    record.put("forward_return", futureClose / baseClose - 1.0)
                }
            } else if (now > targetTime + 2L * WolfCandleHorizon.HOUR_MS) {
                record.put("closed", true)
                record.put("stale", true)
                record.put("evaluated_at", now)
            }
        }

        val existing = rows.asSequence()
            .map { it.optString("symbol") + "|" + it.optLong("base_candle_open") }
            .toHashSet()
        signals.forEach { signal ->
            val probabilities = signal.neuralMemberProbabilities
            if (probabilities.size != EXPERTS || probabilities.any { !it.isFinite() || it !in 0.0..1.0 }) {
                return@forEach
            }
            val bars = WolfCandleHorizon.closedHourlyCandles(signal.neuralCandles, now)
            val base = bars.lastOrNull() ?: return@forEach
            val baseTime = base[5].toLong()
            if (now - (baseTime + WolfCandleHorizon.HOUR_MS) > MAX_BASE_AGE_MS) return@forEach
            val key = signal.symbol + "|" + baseTime
            if (!existing.add(key)) return@forEach
            rows.add(JSONObject().apply {
                put("symbol", signal.symbol)
                put("base_candle_open", baseTime)
                put("base_close", base[3])
                put("created_at", now)
                put("horizon_hours", 6)
                put("regime", signal.neuralTechnical?.regime ?: signal.technical.regime)
                put("ensemble_probability", probabilities.average())
                put("member_probabilities", JSONArray().apply { probabilities.forEach { put(it) } })
                put("closed", false)
                put("neutral", false)
                put("stale", false)
            })
        }

        val bounded = rows.takeLast(MAX_RECORDS)
        val saved = JSONArray().apply { bounded.forEach { put(it) } }
        prefs.edit().putString(KEY, saved.toString()).apply()
        return snapshot(bounded)
    }

    fun snapshot(context: Context): Snapshot {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val rows = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return snapshot((0 until rows.length()).mapNotNull { rows.optJSONObject(it) })
    }

    private fun snapshot(rows: List<JSONObject>): Snapshot {
        val evaluated = rows.filter {
            it.optBoolean("closed") && !it.optBoolean("neutral") && !it.optBoolean("stale") &&
                (it.optInt("outcome", -1) == 0 || it.optInt("outcome", -1) == 1)
        }
        val neutral = rows.count { it.optBoolean("closed") && it.optBoolean("neutral") }
        val stale = rows.count { it.optBoolean("stale") }
        val pending = rows.count { !it.optBoolean("closed") }

        internal fun metrics(probabilities: List<Double>, labels: List<Int>, index: Int): MemberStats {
            val count = minOf(probabilities.size, labels.size)
            if (count == 0) return MemberStats(index, 0, 0, 0.0, 0.0, 0.0, 0.0)
            var correct = 0
            var voted = 0
            var brier = 0.0
            var logLoss = 0.0
            for (i in 0 until count) {
                val p = probabilities[i].coerceIn(1e-6, 1.0 - 1e-6)
                val y = labels[i]
                brier += (p - y) * (p - y)
                logLoss += -(y * ln(p) + (1 - y) * ln(1.0 - p))
                val prediction = when {
                    p >= 0.55 -> 1
                    p <= 0.45 -> 0
                    else -> -1
                }
                if (prediction >= 0) {
                    voted++
                    if (prediction == y) correct++
                }
            }
            return MemberStats(index, count, correct,
                if (voted == 0) 0.0 else correct.toDouble() / voted,
                brier / count, logLoss / count, voted.toDouble() / count)
        }

        val labels = evaluated.map { it.optInt("outcome") }
        val memberStats = (0 until EXPERTS).map { index ->
            val probs = evaluated.mapNotNull { row ->
                row.optJSONArray("member_probabilities")?.takeIf { it.length() == EXPERTS }
                    ?.optDouble(index)?.takeIf { it.isFinite() && it in 0.0..1.0 }
            }
            val matchingLabels = if (probs.size == evaluated.size) labels else emptyList()
            metrics(probs, matchingLabels, index + 1)
        }
        val ensembleProbs = evaluated.map { it.optDouble("ensemble_probability", 0.5) }
        val ensemble = metrics(ensembleProbs, labels, 0)
        return Snapshot(
            evaluated.size, neutral, pending, stale,
            ensemble.accuracy, ensemble.brier, memberStats,
            memberStats.filter { it.samples >= 30 }.minByOrNull { it.brier }
        )
    }
}
