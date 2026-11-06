package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Permanent local paper-signal journal. Public prices only; never places orders. */
internal object WolfSignalOutcomeJournal {
    private const val PREFS = "wolf_signal_outcomes"
    private const val KEY = "records_v3"
    private const val LEGACY_KEYS = arrayOf("records_v2", "records_v1")
    private const val MAX_RECORDS = 5000
    private const val SIGNAL_TIMEFRAME = "15m"
    private const val VALIDITY_MS = 15L * 60L * 1000L

    data class Record(
        val symbol: String, val side: String, val created: Long, val expiresAt: Long,
        val timeframe: String, val entry: Double, val stop: Double,
        val tp1: Double, val tp2: Double, val tp3: Double,
        val status: String, val closed: Boolean, val confidence: Int, val score: Int,
        val reason: String, val leverage: Int, val change: Double
    )
    data class Snapshot(
        val total: Int, val active: Int, val tp1: Int, val tp2: Int, val tp3: Int,
        val stopped: Int, val expired: Int, val recent: List<String>, val records: List<Record>
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

        rows.forEach { record ->
            if (record.optBoolean("closed")) return@forEach
            val created = record.optLong("created")
            val expiresAt = record.optLong("expiresAt", created + VALIDITY_MS)
            record.put("expiresAt", expiresAt)
            record.put("timeframe", record.optString("timeframe", SIGNAL_TIMEFRAME))

            val signal = current[record.optString("symbol")]
            if (signal != null && now - created >= 15_000L) {
                val price = signal.price
                val long = record.optString("side") == "LONG_BIAS"
                val stop = record.optDouble("stop")
                val t1 = record.optDouble("tp1")
                val t2 = record.optDouble("tp2")
                val t3 = record.optDouble("tp3")
                val stopHit = if (long) price <= stop else price >= stop
                val tp1Hit = if (long) price >= t1 else price <= t1
                val tp2Hit = if (long) price >= t2 else price <= t2
                val tp3Hit = if (long) price >= t3 else price <= t3

                if (stopHit) {
                    record.put("status", "SL")
                    record.put("closed", true)
                    record.put("closedAt", now)
                } else if (tp3Hit) {
                    record.put("status", "TP3")
                    record.put("closed", true)
                    record.put("closedAt", now)
                } else if (tp2Hit) record.put("status", "TP2")
                else if (tp1Hit) record.put("status", "TP1")
                record.put("lastChecked", now)
            }

            if (!record.optBoolean("closed") && now >= expiresAt) {
                record.put("status", "EXPIRED")
                record.put("closed", true)
                record.put("closedAt", now)
            }
        }

        val lastBySignal = rows.filter { it.optLong("expiresAt", it.optLong("created") + VALIDITY_MS) > now }
            .associate { (it.optString("symbol") + "|" + it.optString("side")) to it.optLong("created") }
            .toMutableMap()

        signals.filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }.forEach { s ->
            val signalKey = s.symbol + "|" + s.direction
            val last = lastBySignal[signalKey] ?: 0L
            if (now - last >= VALIDITY_MS) {
                rows.add(JSONObject().apply {
                    put("symbol", s.symbol)
                    put("side", s.direction)
                    put("created", now)
                    put("expiresAt", now + VALIDITY_MS)
                    put("timeframe", SIGNAL_TIMEFRAME)
                    put("entry", s.entry)
                    put("stop", s.stop)
                    put("tp1", s.tp1)
                    put("tp2", s.tp2)
                    put("tp3", s.tp3)
                    put("status", "ACTIVE")
                    put("closed", false)
                    put("confidence", s.confidence)
                    put("score", s.score)
                    put("reason", s.reason)
                    put("leverage", s.leverage)
                    put("change", s.change)
                })
                lastBySignal[signalKey] = now
            }
        }

        val bounded = rows.takeLast(MAX_RECORDS)
        val saved = JSONArray().apply { bounded.forEach { put(it) } }
        val editor = prefs.edit().putString(KEY, saved.toString())
        LEGACY_KEYS.forEach { editor.remove(it) }
        editor.apply()
        return snapshot(bounded)
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
            ?: LEGACY_KEYS.firstNotNullOfOrNull { prefs.getString(it, null) } ?: "[]"
        val rows = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return snapshot((0 until rows.length()).mapNotNull { rows.optJSONObject(it) })
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
                    r.optDouble("tp2"), r.optDouble("tp3"), r.optString("status", "ACTIVE"),
                    r.optBoolean("closed"), r.optInt("confidence"), r.optInt("score"),
                    r.optString("reason"), r.optInt("leverage", 1), r.optDouble("change")
                )
            }.getOrNull()
        }
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa", "IR")).apply {
            timeZone = TimeZone.getTimeZone("Asia/Tehran")
        }
        val recent = records.take(8).map { r ->
            dateFormat.format(Date(r.created)) + " • " + r.symbol + " • " +
                (if (r.side == "LONG_BIAS") "LONG" else "SHORT") + " • " + statusLabel(r.status)
        }
        return Snapshot(
            rows.size,
            rows.count { it.optLong("expiresAt", it.optLong("created") + VALIDITY_MS) > System.currentTimeMillis() },
            rows.count { it.optString("status") == "TP1" },
            rows.count { it.optString("status") == "TP2" },
            rows.count { it.optString("status") == "TP3" },
            rows.count { it.optString("status") == "SL" },
            rows.count { it.optString("status") == "EXPIRED" },
            recent,
            records
        )
    }

    fun statusLabel(status: String): String = when (status) {
        "TP1" -> "حد سود اول لمس شد"
        "TP2" -> "حد سود دوم لمس شد"
        "TP3" -> "حد سود سوم لمس شد"
        "SL" -> "حد ضرر لمس شد"
        "EXPIRED" -> "منقضی‌شده • پایان تایم‌فریم"
        else -> "فعال • در انتظار نتیجه"
    }
}
