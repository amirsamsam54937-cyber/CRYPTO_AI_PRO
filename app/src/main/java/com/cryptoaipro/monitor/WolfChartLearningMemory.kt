package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Chart-reading curriculum and durable experience journal.
 *
 * It teaches by converting closed OHLCV windows into explainable feature/lesson records,
 * then attaching fee-aware resolved outcomes. It does NOT silently rewrite model weights.
 * The journal survives process death through SharedPreferences; cloud transport is a
 * separate authenticated adapter and must not be reported as connected until configured.
 */
internal object WolfChartLearningMemory {
    private const val PREFS = "wolfblack_chart_memory_v1"
    private const val LESSONS = "lessons"
    private const val OUTCOMES = "outcomes"
    private const val MAX_RECORDS = 5000

    data class Lesson(
        val id: String,
        val symbol: String,
        val timeframe: String,
        val candleCount: Int,
        val trend: String,
        val structure: String,
        val volatility: String,
        val volume: String,
        val momentum: String,
        val patterns: List<String>,
        val support: Double?,
        val resistance: Double?,
        val createdAt: Long
    )

    data class Outcome(
        val lessonId: String,
        val symbol: String,
        val side: String,
        val horizonBars: Int,
        val netReturnPct: Double,
        val maxAdversePct: Double,
        val maxFavorablePct: Double,
        val feesPct: Double,
        val resolvedAt: Long
    )

    fun study(symbol: String, timeframe: String, candles: List<DoubleArray>): Lesson? {
        val bars = candles.filter { row ->
            row.size >= 6 && row.take(5).all { it.isFinite() } &&
                row[0] > 0 && row[1] >= max(row[0], row[3]) &&
                row[2] <= min(row[0], row[3]) && row[2] > 0 && row[4] >= 0
        }.takeLast(240)
        if (bars.size < 80) return null
        val closes = bars.map { it[3] }
        val highs = bars.map { it[1] }
        val lows = bars.map { it[2] }
        val volumes = bars.map { it[4] }
        val last = closes.last()
        val ema20 = ema(closes, 20)
        val ema50 = ema(closes, 50)
        val ema200 = ema(closes, 200)
        val recentRange = (highs.takeLast(20).maxOrNull()!! - lows.takeLast(20).minOrNull()!!) / last * 100.0
        val atrPct = bars.takeLast(14).mapIndexed { i, b ->
            val previous = closes.getOrElse(bars.size - 14 + i - 1) { b[3] }
            max(b[1] - b[2], max(abs(b[1] - previous), abs(b[2] - previous)))
        }.average() / last * 100.0
        val avgVol = volumes.takeLast(20).average().coerceAtLeast(1e-12)
        val relVol = volumes.last() / avgVol
        val momentum = if (closes[closes.lastIndex - 12] > 0)
            (last / closes[closes.lastIndex - 12] - 1.0) * 100.0 else 0.0
        val trend = when {
            last > ema20 && ema20 > ema50 && ema50 > ema200 -> "صعودی چندمیانگینی"
            last < ema20 && ema20 < ema50 && ema50 < ema200 -> "نزولی چندمیانگینی"
            last > ema50 -> "بازیابی/بالای EMA50"
            last < ema50 -> "ضعف/زیر EMA50"
            else -> "رنج"
        }
        val structure = when {
            higherHighs(highs) && higherLows(lows) -> "HH/HL؛ ساختار صعودی"
            lowerHighs(highs) && lowerLows(lows) -> "LH/LL؛ ساختار نزولی"
            else -> "ساختار خنثی یا در حال چرخش"
        }
        val volatility = when {
            atrPct >= 3.0 -> "بسیار پرنوسان"
            atrPct >= 1.2 -> "پرنوسان"
            atrPct <= 0.35 -> "فشردگی نوسان"
            else -> "متوسط"
        }
        val volume = when {
            relVol >= 2.0 -> "حجم انفجاری نسبت به میانگین"
            relVol >= 1.25 -> "حجم تأییدکننده"
            relVol <= 0.65 -> "حجم ضعیف"
            else -> "حجم عادی"
        }
        val patterns = buildList {
            val a = bars[bars.lastIndex]
            val body = abs(a[3] - a[0])
            val range = (a[1] - a[2]).coerceAtLeast(1e-12)
            val upper = a[1] - max(a[0], a[3])
            val lower = min(a[0], a[3]) - a[2]
            if (body / range < 0.12) add("دوجی/تردید")
            if (lower / range > 0.58 && body / range < 0.3) add("پین‌بار با سایه پایینی")
            if (upper / range > 0.58 && body / range < 0.3) add("پین‌بار با سایه بالایی")
            if (bars.size >= 3) {
                val p = bars[bars.lastIndex - 1]
                if (a[3] > a[0] && p[3] < p[0] && a[3] > p[0] && a[0] < p[3]) add("پوشای صعودی")
                if (a[3] < a[0] && p[3] > p[0] && a[0] > p[3] && a[3] < p[0]) add("پوشای نزولی")
            }
            if (recentRange < 2.0 && atrPct < 0.5) add("فشردگی/احتمال شکست؛ جهت نامشخص")
            if (relVol > 2.0 && abs(momentum) > 1.0) add("حرکت همراه با افزایش حجم")
        }
        val priorHigh = highs.dropLast(1).takeLast(40).maxOrNull()
        val priorLow = lows.dropLast(1).takeLast(40).minOrNull()
        return Lesson(
            UUID.randomUUID().toString(), symbol, timeframe, bars.size, trend, structure,
            volatility, volume, "مومنتوم ۱۲ کندلی: %.2f%%".format(java.util.Locale.US, momentum),
            patterns, priorLow, priorHigh, System.currentTimeMillis()
        )
    }

    fun remember(context: Context, lesson: Lesson) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = runCatching { JSONArray(prefs.getString(LESSONS, "[]")) }.getOrElse { JSONArray() }
        rows.put(lesson.toJson())
        trim(rows)
        prefs.edit().putString(LESSONS, rows.toString()).apply()
    }

    fun resolve(context: Context, outcome: Outcome) {
        require(outcome.netReturnPct.isFinite() && outcome.feesPct.isFinite())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = runCatching { JSONArray(prefs.getString(OUTCOMES, "[]")) }.getOrElse { JSONArray() }
        rows.put(outcome.toJson())
        trim(rows)
        prefs.edit().putString(OUTCOMES, rows.toString()).apply()
    }

    fun recentLessons(context: Context, limit: Int = 100): List<JSONObject> =
        read(context, LESSONS, limit)

    fun recentOutcomes(context: Context, limit: Int = 100): List<JSONObject> =
        read(context, OUTCOMES, limit)

    fun counts(context: Context): Pair<Int, Int> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (runCatching { JSONArray(p.getString(LESSONS, "[]")).length() }.getOrDefault(0)) to
            (runCatching { JSONArray(p.getString(OUTCOMES, "[]")).length() }.getOrDefault(0))
    }

    private fun read(context: Context, key: String, limit: Int): List<JSONObject> {
        val a = runCatching {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]"))
        }.getOrElse { JSONArray() }
        return (max(0, a.length() - limit) until a.length()).mapNotNull { i ->
            runCatching { a.getJSONObject(i) }.getOrNull()
        }.reversed()
    }

    private fun trim(a: JSONArray) {
        while (a.length() > MAX_RECORDS) a.remove(0)
    }

    private fun Lesson.toJson() = JSONObject().apply {
        put("id", id); put("symbol", symbol); put("timeframe", timeframe)
        put("candleCount", candleCount); put("trend", trend); put("structure", structure)
        put("volatility", volatility); put("volume", volume); put("momentum", momentum)
        put("patterns", JSONArray(patterns)); put("support", support ?: JSONObject.NULL)
        put("resistance", resistance ?: JSONObject.NULL); put("createdAt", createdAt)
    }

    private fun Outcome.toJson() = JSONObject().apply {
        put("lessonId", lessonId); put("symbol", symbol); put("side", side)
        put("horizonBars", horizonBars); put("netReturnPct", netReturnPct)
        put("maxAdversePct", maxAdversePct); put("maxFavorablePct", maxFavorablePct)
        put("feesPct", feesPct); put("resolvedAt", resolvedAt)
    }

    private fun ema(v: List<Double>, period: Int): Double {
        val alpha = 2.0 / (period + 1.0)
        return v.fold(v.first()) { acc, x -> alpha * x + (1.0 - alpha) * acc }
    }

    private fun higherHighs(v: List<Double>) = v.takeLast(40).takeLast(20).maxOrNull()!! >
        v.takeLast(40).take(20).maxOrNull()!!
    private fun higherLows(v: List<Double>) = v.takeLast(40).takeLast(20).minOrNull()!! >
        v.takeLast(40).take(20).minOrNull()!!
    private fun lowerHighs(v: List<Double>) = !higherHighs(v)
    private fun lowerLows(v: List<Double>) = !higherLows(v)
}
