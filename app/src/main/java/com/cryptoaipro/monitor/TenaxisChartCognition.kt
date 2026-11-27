package com.cryptoaipro.monitor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * TENAXIS chart cognition: OHLCV/market-structure feature extraction plus a persistent
 * three-class neural learner. Labels come only from subsequently observed closed candles.
 * It is research/advisory until independent chronological and cross-symbol validation passes.
 */
internal object TenaxisChartCognition {
    const val FEATURE_COUNT = 67
    private const val H1 = 24
    private const val H2 = 12
    private const val HORIZON_BARS = 12
    private const val MIN_REVIEWED_FOR_ADVISORY = 300
    private const val PREFS = "tenaxis_chart_cognition_v1"
    private const val MODEL_KEY = "model_v1"
    private const val COUNT_KEY = "reviewed_v1"

    data class Prediction(val down: Double, val neutral: Double, val up: Double, val reviewed: Int) {
        val direction: String get() = when {
            up >= down && up >= neutral -> "UP"
            down >= up && down >= neutral -> "DOWN"
            else -> "NEUTRAL"
        }
        val confidencePct: Int get() = (maxOf(down, neutral, up) * 100.0).toInt().coerceIn(0, 100)
    }

    data class Report(
        val score: Int,
        val regime: String,
        val structure: String,
        val liquidityRead: String,
        val patterns: List<String>,
        val observations: List<String>,
        val prediction: Prediction?,
        val reviewedSamples: Int,
        val features: DoubleArray
    )

    private class Store(context: Context) : SQLiteOpenHelper(
        context.applicationContext, "tenaxis_chart_experience.db", null, 2
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE chart_examples (" +
                    "id TEXT PRIMARY KEY, symbol TEXT NOT NULL, timeframe TEXT NOT NULL, " +
                    "candle_time INTEGER NOT NULL, reference_close REAL NOT NULL, " +
                    "features TEXT NOT NULL, label INTEGER, forward_return REAL, resolved_at INTEGER)"
            )
            db.execSQL("CREATE INDEX chart_examples_pending ON chart_examples(symbol,timeframe,label,candle_time)")
            createMemoryTable(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createMemoryTable(db)
        }
        private fun createMemoryTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS memory_events (" +
                "id TEXT PRIMARY KEY, kind TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "payload TEXT NOT NULL, sync_state TEXT NOT NULL DEFAULT 'LOCAL_ONLY')")
            db.execSQL("CREATE INDEX IF NOT EXISTS memory_events_time ON memory_events(created_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS memory_events_kind ON memory_events(kind,created_at)")
        }
    }

    private data class Model(
        val w1: Array<DoubleArray>, val b1: DoubleArray,
        val w2: Array<DoubleArray>, val b2: DoubleArray,
        val w3: Array<DoubleArray>, val b3: DoubleArray
    )

    fun analyze(context: Context, signal: MarketSignal): Report {
        val primary = closedBars(valid(signal.timeframeCandles["15m"] ?: signal.candles), 15L * 60L * 1000L)
        val hourly = closedBars(valid(signal.timeframeCandles["1h"] ?: signal.neuralCandles), 60L * 60L * 1000L)
        val fourHour = closedBars(valid(signal.timeframeCandles["4h"] ?: emptyList()), 4L * 60L * 60L * 1000L)
        val x = extractFeatures(primary, hourly, fourHour, signal.technical, signal.advancedTechnical, signal.humanChartReadout)
        if (primary.size >= 80) {
            runCatching { captureAndResolve(context, signal.symbol, "15m", primary, x) }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reviewed = prefs.getInt(COUNT_KEY, 0)
        val prediction = if (reviewed >= MIN_REVIEWED_FOR_ADVISORY) runCatching {
            predict(loadModel(prefs), x, reviewed)
        }.getOrNull() else null
        val score = chartScore(x)
        val trend = when {
            x[6] > 0.25 && x[7] > 0.20 && x[8] > 0.10 -> "روند صعودی چندبازه‌ای"
            x[6] < -0.25 && x[7] < -0.20 && x[8] < -0.10 -> "روند نزولی چندبازه‌ای"
            abs(x[6]) < 0.12 && abs(x[7]) < 0.12 -> "رنج/تعادل"
            else -> "گذار یا روند مختلط"
        }
        val structure = when {
            x[23] > 0.5 && x[24] > 0.0 -> "HH/HL و شکست ساختار صعودی محتمل"
            x[23] < -0.5 && x[24] < 0.0 -> "LH/LL و شکست ساختار نزولی محتمل"
            else -> "ساختار ترکیبی؛ شکست معتبر قطعی نیست"
        }
        val liquidity = when {
            x[27] > 0.5 -> "جاروب نقدینگی سمت فروش و بازپس‌گیری کف احتمالی"
            x[28] > 0.5 -> "جاروب نقدینگی سمت خرید و بازپس‌گیری سقف احتمالی"
            else -> "خوشه‌های نقدینگی از سقف/کف‌های تکراری تخمین زده می‌شوند؛ سفارش پنهان مشاهده نشده"
        }
        val patterns = buildList {
            if (x[10] > 0.62 && x[12] > 0.35) add("پین‌بار با سایه پایینی")
            if (x[11] > 0.62 && x[12] < -0.35) add("پین‌بار با سایه بالایی")
            if (x[13] > 0.65) add("کندل با بدنه/مومنتوم قوی")
            if (x[29] > 0.5) add("FVG صعودی احتمالی")
            if (x[30] > 0.5) add("FVG نزولی احتمالی")
            if (x[17] < -0.35) add("فشردگی نوسان")
            if (x[16] > 0.55) add("انبساط نوسان")
        }
        val observations = buildList {
            add("بازده لگاریتمی چندافقی، EMAهای 9/20/50/200 و شیب روند")
            add("ساختار سقف/کف، BOS/CHOCH تقریبی، استخرهای نقدینگی و sweep/reclaim")
            add("آناتومی کندل، شکاف ارزش منصفانه، حجم نسبی و فشار حجم")
            add("ATR، نوسان تحقق‌یافته، فشردگی/انبساط و پروفایل حجم تقریبی")
            add("هم‌جهتی مستقل 15m/1h/4h؛ داده مفقود به‌عنوان خنثی ثبت می‌شود")
        }
        return Report(score, trend, structure, liquidity, patterns, observations, prediction, reviewed, x)
    }

    /** Append-only local episodic memory. No retention cap and no exchange secrets are accepted. */
    fun recordMemoryEvent(context: Context, kind: String, stableId: String, payload: String) {
        if (kind.isBlank() || stableId.isBlank() || payload.length > 48_000) return
        val helper = Store(context)
        runCatching {
            val values = android.content.ContentValues().apply {
                put("id", stableId)
                put("kind", kind.take(80))
                put("created_at", System.currentTimeMillis())
                put("payload", payload)
                put("sync_state", "LOCAL_ONLY")
            }
            helper.writableDatabase.insertWithOnConflict(
                "memory_events", null, values, SQLiteDatabase.CONFLICT_IGNORE
            )
        }
        helper.close()
    }

    fun memoryStats(context: Context): Pair<Int, Int> {
        val helper = Store(context)
        return try {
            val db = helper.readableDatabase
            val events = db.rawQuery("SELECT COUNT(*) FROM memory_events", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            val charts = db.rawQuery("SELECT COUNT(*) FROM chart_examples", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            events to charts
        } finally {
            helper.close()
        }
    }

    /** 67 normalized chart features; no future candles are read. */
    fun extractFeatures(
        primaryInput: List<DoubleArray>,
        hourlyInput: List<DoubleArray> = emptyList(),
        fourHourInput: List<DoubleArray> = emptyList(),
        technical: TechnicalSnapshot? = null,
        advanced: ZoroAdvancedTechnicalEngine.Report? = null,
        human: ZoroHumanMarketReader.Readout? = null
    ): DoubleArray {
        val bars = valid(primaryInput)
        if (bars.size < 30) return DoubleArray(FEATURE_COUNT)
        val closes = bars.map { it[3] }
        val last = bars.last()
        val price = last[3].coerceAtLeast(1e-12)
        val out = DoubleArray(FEATURE_COUNT)
        fun ret(n: Int): Double {
            val old = closes.getOrNull(closes.lastIndex - n) ?: return 0.0
            return ((price / old.coerceAtLeast(1e-12)) - 1.0).coerceIn(-0.5, 0.5) * 2.0
        }
        for ((i, n) in listOf(1, 3, 6, 12, 24, 48).withIndex()) out[i] = ret(n)
        val emaPeriods = listOf(9, 20, 50, 200)
        for (i in emaPeriods.indices) {
            val e = ema(closes, emaPeriods[i])
            out[6 + i] = ((price / e.coerceAtLeast(1e-12) - 1.0) * 18.0).coerceIn(-1.0, 1.0)
        }
        val range = (last[1] - last[2]).coerceAtLeast(1e-12)
        val body = (last[3] - last[0]) / range
        val upper = (last[1] - max(last[0], last[3])) / range
        val lower = (min(last[0], last[3]) - last[2]) / range
        val closeLocation = ((last[3] - last[2]) / range) * 2.0 - 1.0
        out[10] = lower.coerceIn(0.0, 1.0)
        out[11] = upper.coerceIn(0.0, 1.0)
        out[12] = body.coerceIn(-1.0, 1.0)
        out[13] = closeLocation.coerceIn(-1.0, 1.0)
        val vols = bars.takeLast(30).map { it[4] }
        val avgVol = vols.average().coerceAtLeast(1e-12)
        val volSd = sqrt(vols.map { (it - avgVol) * (it - avgVol) }.average()).coerceAtLeast(1e-12)
        out[14] = ((last[4] / avgVol - 1.0) / 3.0).coerceIn(-1.0, 1.0)
        out[15] = (((last[4] - avgVol) / volSd) / 4.0).coerceIn(-1.0, 1.0)
        out[16] = (bars.takeLast(20).sumOf { if (it[3] >= it[0]) it[4] else -it[4] } /
            bars.takeLast(20).sumOf { it[4] }.coerceAtLeast(1e-12)).coerceIn(-1.0, 1.0)
        val atr = atr(bars, 14)
        out[17] = (atr / price * 35.0).coerceIn(0.0, 1.0)
        val returns = closes.takeLast(21).zipWithNext { a, b -> ln(b / a.coerceAtLeast(1e-12)) }
        val realized = sqrt(returns.map { it * it }.average().coerceAtLeast(0.0))
        out[18] = (realized * 20.0).coerceIn(0.0, 1.0)
        val range20 = (bars.takeLast(20).maxOf { it[1] } - bars.takeLast(20).minOf { it[2] }) / price
        out[19] = (range20 * 8.0).coerceIn(0.0, 1.0)
        out[20] = ((rsi(closes, 14) - 50.0) / 50.0).coerceIn(-1.0, 1.0)
        val macd = ema(closes, 12) - ema(closes, 26)
        out[21] = (macd / price * 120.0).coerceIn(-1.0, 1.0)
        out[22] = adxProxy(bars)
        val highs = bars.takeLast(60).map { it[1] }
        val lows = bars.takeLast(60).map { it[2] }
        val firstHalfHigh = highs.take(30).maxOrNull() ?: price
        val secondHalfHigh = highs.takeLast(30).maxOrNull() ?: price
        val firstHalfLow = lows.take(30).minOrNull() ?: price
        val secondHalfLow = lows.takeLast(30).minOrNull() ?: price
        out[23] = (((secondHalfHigh - firstHalfHigh) / price) * 40.0).coerceIn(-1.0, 1.0)
        out[24] = (((secondHalfLow - firstHalfLow) / price) * 40.0).coerceIn(-1.0, 1.0)
        out[25] = ((price / (highs.maxOrNull() ?: price) - 1.0) * 12.0).coerceIn(-1.0, 0.0)
        out[26] = ((price / (lows.minOrNull() ?: price) - 1.0) * 12.0).coerceIn(0.0, 1.0)
        val tol = max(atr / price * 0.22, 0.0005)
        val priorHighs = bars.dropLast(1).takeLast(60).map { it[1] }
        val priorLows = bars.dropLast(1).takeLast(60).map { it[2] }
        val nearHighTouches = priorHighs.count { abs(it / price - 1.0) <= tol }
        val nearLowTouches = priorLows.count { abs(it / price - 1.0) <= tol }
        out[27] = if (nearLowTouches >= 2 && last[2] < (priorLows.minOrNull() ?: last[2]) && last[3] > last[2]) 1.0 else 0.0
        out[28] = if (nearHighTouches >= 2 && last[1] > (priorHighs.maxOrNull() ?: last[1]) && last[3] < last[1]) 1.0 else 0.0
        out[29] = if (bars.size >= 3 && last[2] > bars[bars.lastIndex - 2][1]) 1.0 else 0.0
        out[30] = if (bars.size >= 3 && last[1] < bars[bars.lastIndex - 2][2]) 1.0 else 0.0
        val profileBars = bars.takeLast(96)
        val minPrice = profileBars.minOf { it[2] }
        val maxPrice = profileBars.maxOf { it[1] }
        val bins = DoubleArray(24)
        val span = (maxPrice - minPrice).coerceAtLeast(1e-12)
        profileBars.forEach { b ->
            val index = (((((b[1] + b[2] + b[3]) / 3.0) - minPrice) / span) * 23.0).toInt().coerceIn(0, 23)
            bins[index] += b[4]
        }
        val poc = minPrice + (bins.indices.maxByOrNull { bins[it] } ?: 0) / 23.0 * span
        out[31] = ((price / poc.coerceAtLeast(1e-12) - 1.0) * 12.0).coerceIn(-1.0, 1.0)
        out[32] = ((price - minPrice) / span * 2.0 - 1.0).coerceIn(-1.0, 1.0)
        out[33] = timeframeTrend(hourlyInput)
        out[34] = timeframeTrend(fourHourInput)
        out[35] = (out[6] + out[7] + out[8] + out[9]) / 4.0
        out[36] = ((ema(closes, 9) - ema(closes, 20)) / price * 80.0).coerceIn(-1.0, 1.0)
        out[37] = ((ema(closes, 50) - ema(closes, 200)) / price * 80.0).coerceIn(-1.0, 1.0)
        out[38] = ((last[3] - last[0]) / price * 80.0).coerceIn(-1.0, 1.0)
        out[39] = ((bars.takeLast(5).sumOf { it[4] } / bars.takeLast(30).sumOf { it[4] }.coerceAtLeast(1e-12)) * 6.0 - 1.0).coerceIn(-1.0, 1.0)
        // Reuse the full ZORO technical stack instead of teaching the network indicator-only snapshots.
        if (technical != null) {
            out[40] = (technical.cci / 200.0).coerceIn(-1.0, 1.0)
            out[41] = (technical.rocPercent / 10.0).coerceIn(-1.0, 1.0)
            out[42] = ((technical.mfi - 50.0) / 50.0).coerceIn(-1.0, 1.0)
            out[43] = technical.obvTrend.coerceIn(-1.0, 1.0)
            out[44] = directionValue(technical.supertrendDirection)
            out[45] = directionValue(technical.liquiditySweep)
            out[46] = directionValue(technical.fairValueGap)
            out[47] = directionValue(technical.rsiDivergence)
            out[48] = ((technical.macdLine - technical.macdSignalLine) / price * 120.0).coerceIn(-1.0, 1.0)
            out[49] = if (technical.buySideLiquidity > 0.0) ((technical.buySideLiquidity / price - 1.0) * 15.0).coerceIn(-1.0, 1.0) else 0.0
            out[50] = if (technical.sellSideLiquidity > 0.0) ((technical.sellSideLiquidity / price - 1.0) * 15.0).coerceIn(-1.0, 1.0) else 0.0
            out[51] = (technical.liquidityStrength / 100.0).coerceIn(0.0, 1.0)
        }
        if (advanced?.available == true) {
            out[52] = (advanced.evidenceScore / 100.0).coerceIn(-1.0, 1.0)
            out[54] = directionValue(advanced.indicators.firstOrNull { it.name == "Ichimoku" }?.value.orEmpty())
            out[55] = (metricNumber(advanced, "Chaikin Money Flow") ?: 0.0).coerceIn(-1.0, 1.0)
            out[56] = ((metricNumber(advanced, "Aroon Oscillator") ?: 0.0) / 100.0).coerceIn(-1.0, 1.0)
            out[57] = (((metricNumber(advanced, "Donchian 20") ?: 50.0) / 50.0) - 1.0).coerceIn(-1.0, 1.0)
            out[58] = (((metricNumber(advanced, "Keltner 20,2") ?: 50.0) / 50.0) - 1.0).coerceIn(-1.0, 1.0)
            out[59] = (((metricNumber(advanced, "Williams %R") ?: -50.0) + 50.0) / 50.0).coerceIn(-1.0, 1.0)
            out[60] = (((metricNumber(advanced, "Stoch RSI") ?: 50.0) - 50.0) / 50.0).coerceIn(-1.0, 1.0)
            out[61] = (((metricNumber(advanced, "Bollinger 20,2") ?: 50.0) - 50.0) / 50.0).coerceIn(-1.0, 1.0)
            out[62] = ((metricNumber(advanced, "VWAP") ?: 0.0) / 4.0).coerceIn(-1.0, 1.0)
            val psar = metricNumber(advanced, "Parabolic SAR")
            if (psar != null && psar > 0.0) out[63] = ((price / psar - 1.0) * 40.0).coerceIn(-1.0, 1.0)
            val supports = advanced.levels.filter { it.name.contains("حمایت") || it.name.contains("Pivot S") }.map { it.price }
            val resistances = advanced.levels.filter { it.name.contains("مقاومت") || it.name.contains("Pivot R") }.map { it.price }
            out[64] = supports.filter { it > 0.0 }.minByOrNull { abs(price - it) }?.let { ((price / it - 1.0) * 15.0).coerceIn(-1.0,1.0) } ?: 0.0
            out[65] = resistances.filter { it > 0.0 }.minByOrNull { abs(price - it) }?.let { ((price / it - 1.0) * 15.0).coerceIn(-1.0,1.0) } ?: 0.0
            val fibs = advanced.levels.filter { it.name.contains("فیبوناچی") }
            out[66] = fibs.minByOrNull { abs(price - it.price) }?.let { ((price / it.price - 1.0) * 30.0).coerceIn(-1.0,1.0) } ?: 0.0
        }
        if (human?.available == true) out[53] = (human.score / 100.0).coerceIn(-1.0, 1.0)
        return DoubleArray(FEATURE_COUNT) { i -> out[i].takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0 }
    }

    private fun captureAndResolve(context: Context, symbol: String, timeframe: String, bars: List<DoubleArray>, x: DoubleArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val helper = Store(context)
        val db = helper.writableDatabase
        val last = bars.last()
        val time = last[5].toLong()
        val key = "$symbol|$timeframe|$time"
        val featureText = x.joinToString(",") { it.toString() }
        val insert = android.content.ContentValues().apply {
            put("id", key); put("symbol", symbol); put("timeframe", timeframe)
            put("candle_time", time); put("reference_close", last[3]); put("features", featureText)
        }
        db.insertWithOnConflict("chart_examples", null, insert, SQLiteDatabase.CONFLICT_IGNORE)
        val cursor = db.query("chart_examples", arrayOf("id","candle_time","features"), 
            "symbol=? AND timeframe=? AND label IS NULL", arrayOf(symbol,timeframe), null,null,"candle_time ASC")
        val resolved = mutableListOf<Pair<DoubleArray,Int>>()
        cursor.use {
            while (it.moveToNext()) {
                val candleTime = it.getLong(1)
                val index = bars.indexOfFirst { row -> row[5].toLong() == candleTime }
                if (index < 0 || index + HORIZON_BARS >= bars.size) continue
                val reference = bars[index][3].coerceAtLeast(1e-12)
                val future = bars[index + HORIZON_BARS][3]
                val change = (future / reference - 1.0) * 100.0
                val label = when { change >= 0.25 -> 2; change <= -0.25 -> 0; else -> 1 }
                val values = it.getString(2).split(",").mapNotNull { value -> value.toDoubleOrNull() }
                if (values.size != FEATURE_COUNT) continue
                db.execSQL("UPDATE chart_examples SET label=?,forward_return=?,resolved_at=? WHERE id=?",
                    arrayOf(label, change, System.currentTimeMillis(), it.getString(0)))
                resolved += values.toDoubleArray() to label
            }
        }
        if (resolved.isNotEmpty()) {
            var model = loadModel(prefs)
            resolved.forEach { (features, label) -> model = train(model, features, label) }
            prefs.edit().putString(MODEL_KEY, encode(model))
                .putInt(COUNT_KEY, prefs.getInt(COUNT_KEY, 0) + resolved.size).apply()
        }
        helper.close()
    }

    private fun train(model: Model, x: DoubleArray, label: Int): Model {
        repeat(2) {
            val h1 = DoubleArray(H1) { i -> tanh(model.b1[i] + (0 until FEATURE_COUNT).sumOf { j -> model.w1[i][j] * x[j] }) }
            val h2 = DoubleArray(H2) { i -> tanh(model.b2[i] + (0 until H1).sumOf { j -> model.w2[i][j] * h1[j] }) }
            val logits = DoubleArray(3) { i -> model.b3[i] + (0 until H2).sumOf { j -> model.w3[i][j] * h2[j] } }
            val maxLogit = logits.maxOrNull() ?: 0.0
            val probs = DoubleArray(3) { i -> exp((logits[i] - maxLogit).coerceIn(-30.0, 30.0)) }
            val sum = probs.sum().coerceAtLeast(1e-12)
            for (i in probs.indices) probs[i] /= sum
            val d3 = DoubleArray(3) { i -> probs[i] - if (i == label) 1.0 else 0.0 }
            val d2 = DoubleArray(H2) { j ->
                (0 until 3).sumOf { i -> model.w3[i][j] * d3[i] } * (1.0 - h2[j] * h2[j])
            }
            val d1 = DoubleArray(H1) { j ->
                (0 until H2).sumOf { k -> model.w2[k][j] * d2[k] } * (1.0 - h1[j] * h1[j])
            }
            val rate = 0.0015
            for (i in 0 until 3) {
                for (j in 0 until H2) model.w3[i][j] = (model.w3[i][j] - rate * d3[i] * h2[j]).coerceIn(-4.0,4.0)
                model.b3[i] = (model.b3[i] - rate * d3[i]).coerceIn(-4.0,4.0)
            }
            for (i in 0 until H2) {
                for (j in 0 until H1) model.w2[i][j] = (model.w2[i][j] - rate * d2[i] * h1[j]).coerceIn(-4.0,4.0)
                model.b2[i] = (model.b2[i] - rate * d2[i]).coerceIn(-4.0,4.0)
            }
            for (i in 0 until H1) {
                for (j in 0 until FEATURE_COUNT) model.w1[i][j] = (model.w1[i][j] - rate * d1[i] * x[j]).coerceIn(-4.0,4.0)
                model.b1[i] = (model.b1[i] - rate * d1[i]).coerceIn(-4.0,4.0)
            }
        }
        return model
    }

    private fun predict(model: Model, x: DoubleArray, reviewed: Int): Prediction {
        val h1 = DoubleArray(H1) { i -> tanh(model.b1[i] + (0 until FEATURE_COUNT).sumOf { j -> model.w1[i][j] * x[j] }) }
        val h2 = DoubleArray(H2) { i -> tanh(model.b2[i] + (0 until H1).sumOf { j -> model.w2[i][j] * h1[j] }) }
        val logits = DoubleArray(3) { i -> model.b3[i] + (0 until H2).sumOf { j -> model.w3[i][j] * h2[j] } }
        val maxLogit = logits.maxOrNull() ?: 0.0
        val p = DoubleArray(3) { i -> exp((logits[i] - maxLogit).coerceIn(-30.0,30.0)) }
        val total = p.sum().coerceAtLeast(1e-12)
        return Prediction(p[0]/total,p[1]/total,p[2]/total,reviewed)
    }

    private fun loadModel(prefs: android.content.SharedPreferences): Model {
        val raw = prefs.getString(MODEL_KEY, null)
        if (raw != null) runCatching {
            val j = JSONObject(raw)
            fun matrix(key:String, rows:Int, cols:Int)=Array(rows){r->val a=j.getJSONArray(key).getJSONArray(r);DoubleArray(cols){a.getDouble(it)}}
            fun vector(key:String,n:Int)=DoubleArray(n){j.getJSONArray(key).getDouble(it)}
            return Model(matrix("w1",H1,FEATURE_COUNT),vector("b1",H1),matrix("w2",H2,H1),vector("b2",H2),matrix("w3",3,H2),vector("b3",3))
        }
        var seed = 0x54454E41584953L
        fun next():Double { seed = seed * 6364136223846793005L + 1442695040888963407L; return (((seed ushr 33).toDouble()/Int.MAX_VALUE.toDouble())*2.0-1.0)*0.035 }
        return Model(Array(H1){DoubleArray(FEATURE_COUNT){next()}},DoubleArray(H1),
            Array(H2){DoubleArray(H1){next()}},DoubleArray(H2),
            Array(3){DoubleArray(H2){next()}},DoubleArray(3))
    }

    private fun encode(m:Model):String {
        fun v(a:DoubleArray)=JSONArray().also{x->a.forEach{x.put(it)}}
        fun matrix(a:Array<DoubleArray>)=JSONArray().also{x->a.forEach{row->x.put(v(row))}}
        return JSONObject().put("w1",matrix(m.w1)).put("b1",v(m.b1)).put("w2",matrix(m.w2))
            .put("b2",v(m.b2)).put("w3",matrix(m.w3)).put("b3",v(m.b3)).toString()
    }

    private fun metricNumber(report: ZoroAdvancedTechnicalEngine.Report, name: String): Double? {
        val raw = report.indicators.firstOrNull { it.name == name }?.value ?: return null
        val numeric = raw.filter { it.isDigit() || it == '.' || it == '-' }
        return numeric.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun directionValue(text: String): Double {
        val value = text.lowercase()
        return when {
            value.contains("صعودی") || value.contains("مثبت") || value.contains("bull") ||
                value.contains("بالای ابر") || value.contains("برگشت صعودی") -> 1.0
            value.contains("نزولی") || value.contains("منفی") || value.contains("bear") ||
                value.contains("زیر ابر") || value.contains("برگشت نزولی") -> -1.0
            else -> 0.0
        }
    }

    private fun chartScore(x: DoubleArray): Int =
        ((x[0]*8 + x[1]*8 + x[2]*8 + x[3]*6 + x[6]*8 + x[7]*7 + x[8]*6 +
            x[20]*5 + x[23]*6 + x[24]*6 + x[27]*5 - x[28]*5 + x[33]*5 + x[34]*6 +
            x[40]*3 + x[42]*3 + x[44]*4 + x[45]*5 + x[46]*3 + x[52]*4 + x[53]*5 +
            x[54]*2 + x[55]*2 + x[56]*2 + x[64]*2 + x[65]*2) * 1.45)
            .toInt().coerceIn(-100,100)

    private fun timeframeTrend(input: List<DoubleArray>): Double {
        val b = valid(input)
        if (b.size < 50) return 0.0
        val c = b.map { it[3] }
        val p = c.last()
        val e20 = ema(c,20); val e50 = ema(c,50)
        return (((if (p > e20) 0.5 else -0.5) + (if (e20 > e50) 0.5 else -0.5))).coerceIn(-1.0,1.0)
    }
    private fun closedBars(input: List<DoubleArray>, intervalMs: Long): List<DoubleArray> {
        val now = System.currentTimeMillis()
        return input.filter { row ->
            row.size >= 6 && row[5].isFinite() &&
                row[5].toLong() > 0L && now - row[5].toLong() >= intervalMs
        }
    }

    private fun valid(input: List<DoubleArray>) = input.filter {
        it.size >= 6 && it.take(5).all { value -> value.isFinite() } && it[0] > 0.0 &&
            it[1] >= max(it[0],it[3]) && it[2] <= min(it[0],it[3]) &&
            it[2] > 0.0 && it[4] >= 0.0 && it[5].isFinite()
    }
    private fun ema(v:List<Double>,n:Int):Double { val a=2.0/(n+1.0); return v.fold(v.first()){s,x->a*x+(1-a)*s} }
    private fun rsi(v:List<Double>,n:Int):Double {
        if(v.size<=n) return 50.0
        var g=0.0;var l=0.0
        for(i in v.size-n until v.size){val d=v[i]-v[i-1];if(d>=0)g+=d else l-=d}
        return if(l<=1e-12) 100.0 else 100.0-100.0/(1.0+g/l)
    }
    private fun atr(b:List<DoubleArray>,n:Int):Double {
        val start=(b.size-n).coerceAtLeast(1)
        return (start until b.size).map{i->max(b[i][1]-b[i][2],max(abs(b[i][1]-b[i-1][3]),abs(b[i][2]-b[i-1][3])))}.average()
    }
    private fun adxProxy(b:List<DoubleArray>):Double {
        if(b.size<30)return 0.0
        val c=b.takeLast(30)
        val up=c.last()[1]-c.first()[1];val down=c.last()[2]-c.first()[2]
        return ((up-down)/(c.last()[3].coerceAtLeast(1e-12))*12.0).coerceIn(-1.0,1.0)
    }
}
