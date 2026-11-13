package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Local paper-signal journal. Tracks public prices only and never places orders. */
internal object WolfSignalOutcomeJournal {
    private const val PREFS = "wolf_signal_outcomes"
    private const val KEY = "records_v4"
    private val LEGACY_KEYS = arrayOf("records_v3", "records_v2", "records_v1")
    private const val MAX_RECORDS = 5000
    private const val SIGNAL_TIMEFRAME = "15m"
    private const val VALIDITY_MS = 15L * 60L * 1000L
    // Conservative paper assumptions: net outcomes, not gross price moves, drive learning.
    private const val TAKER_FEE_PER_SIDE = 0.0006
    private const val SLIPPAGE_PER_SIDE = 0.0005
    private const val FUNDING_PER_8H = 0.0001
    private const val EIGHT_HOURS_MS = 8L * 60L * 60L * 1000L

    data class Record(
        val symbol: String, val side: String, val created: Long, val expiresAt: Long,
        val timeframe: String, val entry: Double, val stop: Double,
        val tp1: Double,
        val status: String, val closed: Boolean, val confidence: Int, val neuralScore: Int, val score: Int,
        val reason: String, val leverage: Int, val change: Double,
        val exitPrice: Double, val pnlPct: Double, val closedAt: Long
    )
    data class Snapshot(
        val total: Int, val active: Int, val tp1: Int,
        val stopped: Int, val expired: Int, val recent: List<String>,
        val records: List<Record>, val winRate: Double, val totalPnlPct: Double,
        val bestRecord: Record?, val averageWinPct: Double, val averageLossPct: Double
    )

    @Synchronized
    fun processScan(context: Context, signals: List<MarketSignal>): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
            ?: LEGACY_KEYS.firstNotNullOfOrNull { prefs.getString(it, null) } ?: "[]"
        val old = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val rows = (0 until old.length()).mapNotNull { old.optJSONObject(it) }.toMutableList()
        val now = System.currentTimeMillis()
        val current = signals.associateBy { it.symbol }

        // Update every open paper signal. First TP, SL, or the 15m deadline closes it into history.
        rows.forEach { record ->
            if (record.optBoolean("closed")) return@forEach
            val created = record.optLong("created")
            val expiresAt = record.optLong("expiresAt", created + VALIDITY_MS)
            record.put("expiresAt", expiresAt)
            record.put("timeframe", record.optString("timeframe", SIGNAL_TIMEFRAME))
            val signal = current[record.optString("symbol")]
            if (signal != null && signal.price > 0.0 && now > created) {
                val entry = record.optDouble("entry")
                val long = record.optString("side") == "LONG_BIAS"
                val stop = record.optDouble("stop")
                val targets = listOf(record.optDouble("tp1"))
                // Evaluate every newly closed 15m candle since the previous scan. Using only
                // the sampled last price misses intrabar SL/TP touches and biases win-rate stats.
                val cursor = record.optLong("lastEvaluatedCandleOpenTime", created)
                val newBars = signal.candles.asSequence()
                    .filter { it.size >= 6 && it[5].isFinite() && it[5].toLong() > cursor }
                    .sortedBy { it[5] }
                    .toList()
                newBars.forEach { bar ->
                    if (record.optBoolean("closed")) return@forEach
                    val open = bar[0]
                    val high = bar[1]
                    val low = bar[2]
                    val close = bar[3]
                    if (!listOf(open, high, low, close).all { it.isFinite() && it > 0.0 }) {
                        record.put("lastEvaluatedCandleOpenTime", bar[5].toLong())
                        return@forEach
                    }
                    val stopHit = if (long) low <= stop else high >= stop
                    val targetHit = targets.indexOfLast { target ->
                        if (long) high >= target else low <= target
                    }
                    if (stopHit) {
                        // If a candle opens through the stop, model the worse opening price.
                        val stopFill = if (long) minOf(open, stop) else maxOf(open, stop)
                        closeRecord(record, "SL", stopFill, now)
                    } else if (targetHit >= 0) {
                        closeRecord(record, "TP" + (targetHit + 1), targets[targetHit], now)
                    } else {
                        record.put("lastPrice", close)
                    }
                    record.put("lastEvaluatedCandleOpenTime", bar[5].toLong())
                }
                if (record.optBoolean("closed")) {
                    val exit = record.optDouble("exitPrice")
                    if (entry > 0.0 && exit > 0.0) {
                        val rawPct = if (long) (exit / entry - 1.0) * 100.0
                            else ((entry - exit) / entry) * 100.0
                        applyNetPaperPnl(record, rawPct, now)
                    }
                } else if (now >= expiresAt) {
                    closeRecord(record, "EXPIRED", signal.price, now)
                    val exit = record.optDouble("exitPrice")
                    if (entry > 0.0 && exit > 0.0) {
                        val rawPct = if (long) (exit / entry - 1.0) * 100.0
                            else ((entry - exit) / entry) * 100.0
                        applyNetPaperPnl(record, rawPct, now)
                    }
                } else {
                    record.put("lastPrice", signal.price)
                    record.put("lastChecked", now)
                }
            } else if (now >= expiresAt) {
                closeRecord(record, "EXPIRED", record.optDouble("lastPrice", record.optDouble("entry")), now)
                val entry = record.optDouble("entry")
                val exit = record.optDouble("exitPrice")
                if (entry > 0.0 && exit > 0.0) {
                    val rawPct = if (record.optString("side") == "LONG_BIAS") (exit / entry - 1.0) * 100.0
                        else ((entry - exit) / entry) * 100.0
                    applyNetPaperPnl(record, rawPct, now)
                }
            }
        }

        // A signal is created once per symbol/direction per 15m window, even if it closes early.
        val lastBySignal = rows.associate {
            (it.optString("symbol") + "|" + it.optString("side")) to it.optLong("created")
        }
        signals.filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }.forEach { s ->
            val key = s.symbol + "|" + s.direction
            val last = lastBySignal[key] ?: 0L
            if (now - last >= VALIDITY_MS) {
                rows.add(JSONObject().apply {
                    put("symbol", s.symbol); put("side", s.direction)
                    put("created", now); put("expiresAt", now + VALIDITY_MS)
                    put("timeframe", s.timeframeCoverage)
                    put("entry", s.entry); put("stop", s.stop)
                    put("tp1", s.tp1)
                    put("status", "ACTIVE"); put("closed", false)
                    put("confidence", s.confidence); put("neuralScore", s.neuralScore); put("score", s.score)
                    put("reason", s.reason); put("leverage", s.leverage); put("change", s.change)
                    put("lastPrice", s.price); put("lastChecked", now)
                    put("neuralFeatures", JSONArray().apply { WolfDeepNeuralNetwork.featureVector(s).forEach { put(it) } })
                    put("learningApplied", false)
                    put("lastEvaluatedCandleOpenTime", s.candles.lastOrNull()?.getOrNull(5)?.toLong() ?: now)
                })
            }
        }

        rows.filter { it.optBoolean("closed") && !it.optBoolean("learningApplied") }.forEach { record ->
            val savedFeatures = record.optJSONArray("neuralFeatures")
            val pnl = record.optDouble("pnlPct", Double.NaN)
            if (savedFeatures != null && savedFeatures.length() == 7 && pnl.isFinite()) {
                val vector = DoubleArray(7) { savedFeatures.optDouble(it, Double.NaN) }
                if (vector.all { it.isFinite() }) {
                    WolfDeepNeuralNetwork.learn(prefs, vector, if (pnl > 0.0) 1.0 else 0.0)
                    record.put("learningApplied", true)
                }
            }
        }
        val bounded = rows.takeLast(MAX_RECORDS)
        val saved = JSONArray().apply { bounded.forEach { put(it) } }
        val editor = prefs.edit().putString(KEY, saved.toString())
        LEGACY_KEYS.forEach { editor.remove(it) }
        editor.apply()
        return snapshot(bounded)
    }

    private fun applyNetPaperPnl(record: JSONObject, grossPriceChangePct: Double, now: Long) {
        val leverage = record.optInt("leverage", 1).coerceIn(1, 20)
        val heldMs = (now - record.optLong("created", now)).coerceAtLeast(0L)
        val roundTripCostFraction = 2.0 * (TAKER_FEE_PER_SIDE + SLIPPAGE_PER_SIDE) +
            FUNDING_PER_8H * (heldMs.toDouble() / EIGHT_HOURS_MS.toDouble())
        val costPctOnNotional = roundTripCostFraction * 100.0
        val netMarginPct = (grossPriceChangePct - costPctOnNotional) * leverage
        record.put("grossPriceChangePct", grossPriceChangePct)
        record.put("estimatedCostPctOnNotional", costPctOnNotional)
        record.put("priceChangePct", grossPriceChangePct)
        record.put("pnlPct", netMarginPct)
        record.put("pnlBasis", "estimated_net_after_round_trip_fee_slippage_funding")
    }

    private fun closeRecord(record: JSONObject, status: String, exitPrice: Double, now: Long) {
        record.put("status", status)
        record.put("closed", true)
        record.put("closedAt", now)
        record.put("exitPrice", exitPrice)
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
            ?: LEGACY_KEYS.firstNotNullOfOrNull { prefs.getString(it, null) } ?: "[]"
        val rows = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val objects = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        return snapshot(objects)
    }

    private fun snapshot(rows: List<JSONObject>): Snapshot {
        val records = rows.asReversed().mapNotNull { r ->
            runCatching {
                val created = r.optLong("created")
                Record(
                    r.getString("symbol"), r.getString("side"), created,
                    r.optLong("expiresAt", created + VALIDITY_MS),
                    r.optString("timeframe", SIGNAL_TIMEFRAME),
                    r.optDouble("entry"), r.optDouble("stop"), r.optDouble("tp1"),
                    r.optString("status", "ACTIVE"),
                    r.optBoolean("closed"), r.optInt("confidence"), r.optInt("neuralScore", 50), r.optInt("score"),
                    r.optString("reason"), r.optInt("leverage", 1), r.optDouble("change"),
                    r.optDouble("exitPrice"), r.optDouble("pnlPct"), r.optLong("closedAt")
                )
            }.getOrNull()
        }
        val closed = records.filter { it.closed && it.status != "EXPIRED" || it.status == "EXPIRED" }
        val wins = closed.filter { it.pnlPct > 0.0 }
        val losses = closed.filter { it.pnlPct < 0.0 }
        val best = closed.filter { it.pnlPct > 0.0 }.maxByOrNull { it.pnlPct }
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa", "IR")).apply {
            timeZone = TimeZone.getTimeZone("Asia/Tehran")
        }
        val recent = records.take(8).map { r ->
            dateFormat.format(Date(r.created)) + " • " + r.symbol + " • " +
                (if (r.side == "LONG_BIAS") "LONG" else "SHORT") + " • " + statusLabel(r.status) +
                if (r.closed) " • " + String.format(Locale.US, "%+.2f%%", r.pnlPct) else ""
        }
        return Snapshot(
            rows.size, records.count { !it.closed },
            records.count { it.status == "TP1" }, records.count { it.status == "SL" },
            records.count { it.status == "EXPIRED" }, recent, records,
            if (closed.isEmpty()) 0.0 else wins.size.toDouble() / closed.size * 100.0,
            closed.sumOf { it.pnlPct }, best,
            if (wins.isEmpty()) 0.0 else wins.map { it.pnlPct }.average(),
            if (losses.isEmpty()) 0.0 else losses.map { it.pnlPct }.average()
        )
    }

    fun statusLabel(status: String): String = when (status) {
        "TP1" -> "حد سود اول • بسته شد"
        "TP2", "TP3" -> "نتیجه نسخه قدیمی • پیش از تک‌هدفه‌شدن"
        "SL" -> "حد ضرر • بسته شد"
        "EXPIRED" -> "منقضی‌شده • پایان تایم‌فریم"
        else -> "فعال • در انتظار نتیجه"
    }
}
