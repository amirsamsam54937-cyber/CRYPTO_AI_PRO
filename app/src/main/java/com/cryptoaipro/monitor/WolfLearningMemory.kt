package com.cryptoaipro.monitor

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Durable outcome statistics and fallback journal. Neural updates are owned exclusively
 * by WolfSignalOutcomeJournal so one outcome cannot train the online network twice or
 * mix the 15-minute journal target with this fallback horizon.
 * This is online fine-tuning of the app's small DNN, not a pretrained foundation model.
 */
internal object WolfLearningMemory {
    private const val PREFS = "wolf_learning_memory"
    private const val PENDING = "pending_outcomes_v2"
    private const val LAST_DAY = "last_learning_day"
    private const val DAY_COUNT = "daily_learned"
    private const val HORIZON_MS = 4L * 60L * 60L * 1000L
    private const val MAX_PENDING = 400

    data class Stats(val samples: Int, val wins: Int, val losses: Int, val pending: Int, val learnedToday: Int)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun processScan(context: Context, signals: List<MarketSignal>, outcomes: List<WolfSignalOutcomeJournal.Record> = emptyList(), now: Long = System.currentTimeMillis()): Stats {
        val p = prefs(context)
        val queue = runCatching { p.getString(PENDING, "[]")?.let(::JSONArray) ?: JSONArray() }.getOrElse { JSONArray() }
        val retained = JSONArray()
        var learned = 0
        var wins = p.getInt("wins", 0)
        var losses = p.getInt("losses", 0)
        var samples = p.getInt("samples", 0)
        val bySymbol = signals.associateBy { it.symbol }
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol")
            val age = now - item.optLong("time")
            val current = bySymbol[symbol]
            val createdAt = item.optLong("time")
            val closedOutcome = outcomes.firstOrNull {
                it.symbol == symbol && it.closed && abs(it.created - createdAt) <= 10_000L
            }
            if (closedOutcome != null) {
                if (abs(closedOutcome.pnlPct) >= 0.05) {
                    val isWin = closedOutcome.pnlPct > 0.0
                    samples++
                    if (isWin) wins++ else losses++
                    learned++
                }
                continue
            }
            if (age >= HORIZON_MS && current != null && current.price > 0.0) {
                val entry = item.optDouble("entry", 0.0)
                val side = item.optString("side")
                val move = if (entry > 0.0) (current.price / entry - 1.0) * if (side == "SHORT_BIAS") -100.0 else 100.0 else 0.0
                if (abs(move) >= 0.15) {
                    val isWin = move > 0.0
                    samples++
                    if (isWin) wins++ else losses++
                    learned++
                    continue
                }
                // Near-flat outcomes are inconclusive; retain briefly for the next scan.
                if (age < HORIZON_MS * 2) retained.put(item)
            } else if (age < HORIZON_MS * 2) retained.put(item)
        }

        // Record only actionable directional signals. De-duplicate a symbol within 15 minutes.
        val lastRecorded = mutableMapOf<String, Long>()
        outcomes.forEach { outcome ->
            lastRecorded[outcome.symbol] = maxOf(lastRecorded[outcome.symbol] ?: 0L, outcome.created)
        }
        for (i in 0 until retained.length()) {
            val item = retained.optJSONObject(i) ?: continue
            lastRecorded[item.optString("symbol")] = maxOf(lastRecorded[item.optString("symbol")] ?: 0L, item.optLong("time"))
        }
        signals.filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
            .sortedByDescending { it.volume }.forEach { s ->
                if (now - (lastRecorded[s.symbol] ?: 0L) >= TimeUnit.MINUTES.toMillis(15)) {
                    retained.put(encodeSignal(s, now))
                    lastRecorded[s.symbol] = now
                }
            }
        val bounded = JSONArray()
        val start = (retained.length() - MAX_PENDING).coerceAtLeast(0)
        for (i in start until retained.length()) bounded.put(retained.getJSONObject(i))
        val day = now / TimeUnit.DAYS.toMillis(1)
        val today = if (p.getLong(LAST_DAY, -1L) == day) p.getInt(DAY_COUNT, 0) + learned else learned
        p.edit().putString(PENDING, bounded.toString())
            .putInt("samples", samples).putInt("wins", wins).putInt("losses", losses)
            .putLong(LAST_DAY, day).putInt(DAY_COUNT, today).putLong("last_auto_learning_at", now).apply()
        return Stats(samples, wins, losses, bounded.length(), today)
    }

    fun stats(context: Context): Stats {
        val p = prefs(context)
        val q = runCatching { JSONArray(p.getString(PENDING, "[]")) }.getOrElse { JSONArray() }
        val day = System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1)
        return Stats(p.getInt("samples", 0), p.getInt("wins", 0), p.getInt("losses", 0),
            q.length(), if (p.getLong(LAST_DAY, -1L) == day) p.getInt(DAY_COUNT, 0) else 0)
    }

    private fun encodeSignal(s: MarketSignal, time: Long) = JSONObject().apply {
        put("symbol", s.symbol); put("time", time); put("entry", s.price); put("side", s.direction)
        put("change", s.change); put("volume", s.volume); put("score", s.score); put("rsi", s.rsi)
        put("confidence", s.confidence); put("reason", s.reason); put("stop", s.stop)
        put("tp1", s.tp1); put("tp2", s.tp2); put("tp3", s.tp3); put("leverage", s.leverage); put("atr", s.atr)
        val t = s.technical
        put("tech", JSONObject().apply {
            put("e9", t.ema9); put("e20", t.ema20); put("e50", t.ema50); put("e100", t.ema100); put("e200", t.ema200)
            put("adx", t.adx); put("bbw", t.bollingerWidthPct); put("bbp", t.bollingerPosition)
            put("rv", t.relativeVolume); put("vwap", t.vwap); put("stoch", t.stochasticRsi)
            put("atrp", t.atrPercent); put("regime", t.regime); put("pattern", t.candlePattern)
            put("structure", t.marketStructure); put("support", t.support); put("resistance", t.resistance)
        })
    }

    private fun decodeSignal(j: JSONObject): MarketSignal? = runCatching {
        val t = j.getJSONObject("tech")
        val tech = TechnicalSnapshot(t.getDouble("e9"), t.getDouble("e20"), t.getDouble("e50"),
            t.getDouble("e100"), t.getDouble("e200"), t.getDouble("adx"), t.getDouble("bbw"),
            t.getDouble("bbp"), t.getDouble("rv"), t.getDouble("vwap"), t.getDouble("stoch"),
            t.getDouble("atrp"), t.getString("regime"), t.getString("pattern"), t.getString("structure"),
            t.getDouble("support"), t.getDouble("resistance"))
        MarketSignal(j.getString("symbol"), j.getDouble("entry"), j.optDouble("change"), j.optDouble("volume"),
            j.getString("side"), j.optInt("score"), j.optDouble("rsi"), j.optInt("confidence"),
            j.optString("reason"), j.getDouble("entry"), j.optDouble("stop"), j.optDouble("tp1"),
            j.optDouble("tp2"), j.optDouble("tp3"), j.optInt("leverage", 1), j.optDouble("atr"), tech)
    }.getOrNull()
}
