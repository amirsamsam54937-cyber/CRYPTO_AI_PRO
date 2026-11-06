package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Paper signal outcome tracker. It observes public prices only and never places orders. */
internal object WolfSignalOutcomeJournal {
    private const val PREFS = "wolf_signal_outcomes"
    private const val KEY = "records_v1"
    private const val MAX_RECORDS = 500
    data class Snapshot(val total: Int, val active: Int, val tp1: Int, val tp2: Int, val tp3: Int, val stopped: Int, val recent: List<String>)

    @Synchronized
    fun processScan(context: Context, signals: List<MarketSignal>): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val rows = (0 until old.length()).mapNotNull { old.optJSONObject(it) }.toMutableList()
        val now = System.currentTimeMillis()
        val current = signals.associateBy { it.symbol }
        rows.forEach { record ->
            if (record.optBoolean("closed")) return@forEach
            val signal = current[record.optString("symbol")] ?: return@forEach
            if (now - record.optLong("created") < 15_000L) return@forEach
            val candle = signal.candles.lastOrNull()
            val high = candle?.getOrNull(1) ?: signal.price
            val low = candle?.getOrNull(2) ?: signal.price
            val long = record.optString("side") == "LONG_BIAS"
            val stop = record.optDouble("stop")
            val t1 = record.optDouble("tp1")
            val t2 = record.optDouble("tp2")
            val t3 = record.optDouble("tp3")
            val stopHit = if (long) low <= stop else high >= stop
            val tp1Hit = if (long) high >= t1 else low <= t1
            val tp2Hit = if (long) high >= t2 else low <= t2
            val tp3Hit = if (long) high >= t3 else low <= t3
            // Conservative candle ambiguity rule: if stop and any target are both touched in one candle, count stop first.
            if (stopHit && (tp1Hit || tp2Hit || tp3Hit)) {
                record.put("status", "SL"); record.put("closed", true); record.put("closedAt", now)
            } else if (stopHit) {
                record.put("status", "SL"); record.put("closed", true); record.put("closedAt", now)
            } else if (tp3Hit) { record.put("status", "TP3"); record.put("closed", true); record.put("closedAt", now) }
            else if (tp2Hit) record.put("status", "TP2")
            else if (tp1Hit) record.put("status", "TP1")
        }
        val lastBySymbol = rows.filter { !it.optBoolean("closed") }.associate { it.optString("symbol") to it.optLong("created") }.toMutableMap()
        signals.filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }.forEach { s ->
            val last = lastBySymbol[s.symbol] ?: 0L
            if (now - last >= 60L * 60L * 1000L) {
                rows.add(JSONObject().apply {
                    put("symbol", s.symbol); put("side", s.direction); put("created", now)
                    put("entry", s.entry); put("stop", s.stop); put("tp1", s.tp1); put("tp2", s.tp2); put("tp3", s.tp3)
                    put("status", "ACTIVE"); put("closed", false)
                })
                lastBySymbol[s.symbol] = now
            }
        }
        val bounded = rows.takeLast(MAX_RECORDS)
        val saved = JSONArray().apply { bounded.forEach { put(it) } }
        prefs.edit().putString(KEY, saved.toString()).apply()
        return snapshot(bounded)
    }

    fun snapshot(context: Context): Snapshot {
        val rows = runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return snapshot((0 until rows.length()).mapNotNull { rows.optJSONObject(it) })
    }

    private fun snapshot(rows: List<JSONObject>): Snapshot {
        val recent = rows.takeLast(8).asReversed().map { r ->
            val date = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(r.optLong("created")))
            val status = when (r.optString("status")) { "TP1" -> "حد سود اول"; "TP2" -> "حد سود دوم"; "TP3" -> "حد سود سوم"; "SL" -> "حد ضرر"; else -> "باز" }
            date + " • " + r.optString("symbol") + " • " + (if (r.optString("side") == "LONG_BIAS") "LONG" else "SHORT") + " • " + status
        }
        return Snapshot(rows.size, rows.count { !it.optBoolean("closed") }, rows.count { it.optString("status") == "TP1" },
            rows.count { it.optString("status") == "TP2" }, rows.count { it.optString("status") == "TP3" }, rows.count { it.optString("status") == "SL" }, recent)
    }
}
