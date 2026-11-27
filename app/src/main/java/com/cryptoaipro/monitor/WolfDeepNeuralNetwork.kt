package com.cryptoaipro.monitor

import android.content.SharedPreferences
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Small on-device deep feed-forward neural network.
 * Architecture: 36 normalized multi-domain features -> 32 tanh -> 16 tanh -> 1 sigmoid.
 * Inputs include OHLCV shape, market structure, multi-timeframe auction context, volatility,
 * volume participation, news/sentiment, macro sentiment, order-book/trade-flow imbalance and funding.
 * Online learning uses only completed, fee-aware journal outcomes; it is not a calibrated win probability.
 */
internal object WolfDeepNeuralNetwork {
    internal const val FEATURE_COUNT = 36
    private const val INPUTS = FEATURE_COUNT
    private const val H1 = 32
    private const val H2 = 16
    private const val KEY = "wolf_dnn_v2"
    private const val LEGACY_DEEP_KEY = "wolf_dnn_v1"
    private const val LEGACY_KEY = "wolf_model_weights"
    private const val SAMPLE_COUNT_KEY = "wolf_dnn_reviewed_samples"

    private data class Model(
        val w1: Array<DoubleArray>, val b1: DoubleArray,
        val w2: Array<DoubleArray>, val b2: DoubleArray,
        val w3: DoubleArray, var b3: Double
    )

    private fun sigmoid(x: Double): Double {
        val z = x.coerceIn(-30.0, 30.0)
        return 1.0 / (1.0 + exp(-z))
    }

    private fun features(s: MarketSignal, context: Context? = null): DoubleArray {
        val direction = if (s.direction == "SHORT_BIAS") -1.0 else 1.0
        val trend = if (s.technical.ema20 >= s.technical.ema50) 1.0 else -1.0
        val rsi = ((s.rsi - 50.0) / 50.0).coerceIn(-1.0, 1.0)
        val adx = (s.technical.adx / 50.0).coerceIn(0.0, 1.0)
        val volume = ((s.technical.relativeVolume - 1.0) / 2.0).coerceIn(-1.0, 1.0)
        val pattern = when {
            s.technical.candlePattern.contains("صعودی") || s.technical.candlePattern.contains("چکش") -> 1.0
            s.technical.candlePattern.contains("نزولی") || s.technical.candlePattern.contains("ثاقب") -> -1.0
            else -> 0.0
        }
        val volatility = ((s.technical.atrPercent - 2.0) / 5.0).coerceIn(-1.0, 1.0)
        var news = 0.0
        var marketSentiment = 0.0
        var fearGreed = 0.0
        var flow = 0.0
        var book = 0.0
        var funding = 0.0
        if (context != null) runCatching {
            val prefs = context.getSharedPreferences("wolf_background_intelligence", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("market_news_updated_at", 0L) in 0L..7200000L) {
                val articles = org.json.JSONArray(prefs.getString("market_news", "[]"))
                val base = s.symbol.uppercase().replace(Regex("[^A-Z0-9]"), "").removeSuffix("USDT").removeSuffix("USD")
                val aliases = when (base) {
                    "BTC" -> listOf("bitcoin", "btc")
                    "ETH" -> listOf("ethereum", "ether", "eth")
                    "SOL" -> listOf("solana", "sol")
                    "XRP" -> listOf("ripple", "xrp")
                    else -> listOf(base.lowercase())
                }
                val scores = (0 until articles.length()).mapNotNull { i ->
                    val a = articles.optJSONObject(i) ?: return@mapNotNull null
                    val headline = a.optString("title") + " " + a.optString("description")
                    if (aliases.any { headline.contains(it, ignoreCase = true) })
                        ZoroSentimentNetwork.analyzeText(headline).toDouble() / 100.0 else null
                }
                if (scores.isNotEmpty()) news = scores.average()
            }
            val sentiment = org.json.JSONObject(prefs.getString("sentiment_network_snapshot", "{}"))
            marketSentiment = (sentiment.optDouble("marketScore", 0.0) / 100.0).coerceIn(-1.0, 1.0)
            if (sentiment.has("fearGreed") && !sentiment.isNull("fearGreed"))
                fearGreed = ((sentiment.optDouble("fearGreed", 50.0) - 50.0) / 50.0).coerceIn(-1.0, 1.0)
            val flows = org.json.JSONArray(prefs.getString("flow_reports", "[]"))
            val wanted = s.symbol.uppercase().replace(Regex("[^A-Z0-9]"), "")
            for (i in 0 until flows.length()) {
                val f = flows.optJSONObject(i) ?: continue
                val key = f.optString("symbol").uppercase().replace(Regex("[^A-Z0-9]"), "")
                if (key == wanted && now - f.optLong("updatedAt", 0L) in 0L..1800000L) {
                    flow = f.optDouble("imbalance", 0.0).coerceIn(-1.0, 1.0)
                    book = f.optDouble("bookImbalance", 0.0).coerceIn(-1.0, 1.0)
                    funding = (f.optDouble("funding", 0.0) * 100.0).coerceIn(-1.0, 1.0)
                    break
                }
            }
        }

        // Non-indicator market cognition: auction/structure reading and weighted timeframe context.
        val bars = s.candles.filter { it.size >= 6 && it.take(6).all(Double::isFinite) &&
            it[0] > 0.0 && it[1] >= maxOf(it[0], it[3]) && it[2] <= minOf(it[0], it[3]) &&
            it[2] > 0.0 && it[4] >= 0.0 }.takeLast(96)
        val last = bars.lastOrNull()
        val chartRead = runCatching { ZoroHumanMarketReader.analyze(bars) }.getOrNull()
        val chartStructure = ((chartRead?.score ?: 0) / 100.0).coerceIn(-1.0, 1.0)
        val frames = s.timeframeAnalysis.filter { it.available }
        val timeframeConsensus = if (frames.isEmpty()) 0.0 else
            (ZoroTimeframeAnalysisEngine.consensusScore(frames) / 100.0).coerceIn(-1.0, 1.0)
        val timeframeCoverage = (frames.size / 8.0).coerceIn(0.0, 1.0)
        val atrPrice = (s.technical.atrPercent / 100.0 * s.price).coerceAtLeast(s.price * 0.001)
        fun distance(level: Double): Double = if (level.isFinite() && level > 0.0 && atrPrice > 0.0)
            ((s.price - level) / atrPrice).coerceIn(-5.0, 5.0) / 5.0 else 0.0
        val supportDistance = distance(s.technical.support)
        val resistanceDistance = distance(s.technical.resistance)
        val vwapDistance = distance(s.technical.vwap)
        val obv = (s.technical.obvTrend / 5.0).coerceIn(-1.0, 1.0)
        val mfi = ((s.technical.mfi - 50.0) / 50.0).coerceIn(-1.0, 1.0)
        val cci = (s.technical.cci / 200.0).coerceIn(-1.0, 1.0)
        val roc = (s.technical.rocPercent / 5.0).coerceIn(-1.0, 1.0)
        val sweepText = s.technical.liquiditySweep
        val sweep = when {
            sweepText.contains("صعودی") || sweepText.contains("کف") || sweepText.contains("پایین") -> 1.0
            sweepText.contains("نزولی") || sweepText.contains("سقف") || sweepText.contains("بالا") -> -1.0
            else -> 0.0
        }
        val fvgText = s.technical.fairValueGap
        val fvg = when {
            fvgText.contains("صعودی") || fvgText.contains("Bull", true) -> 1.0
            fvgText.contains("نزولی") || fvgText.contains("Bear", true) -> -1.0
            else -> 0.0
        }
        val macd = if (s.technical.macdLine.isFinite() && s.technical.macdSignalLine.isFinite())
            if (s.technical.macdLine >= s.technical.macdSignalLine) 1.0 else -1.0 else 0.0
        val bodyPressure = last?.let {
            ((it[3] - it[0]) / (it[1] - it[2]).coerceAtLeast(1e-12)).coerceIn(-1.0, 1.0)
        } ?: 0.0
        val wickImbalance = last?.let {
            val range = (it[1] - it[2]).coerceAtLeast(1e-12)
            val upper = it[1] - maxOf(it[0], it[3])
            val lower = minOf(it[0], it[3]) - it[2]
            ((lower - upper) / range).coerceIn(-1.0, 1.0)
        } ?: 0.0
        val volumeZ = if (bars.size >= 12) {
            val prior = bars.dropLast(1).takeLast(30).map { it[4] }
            val avg = prior.average()
            val variance = prior.map { (it - avg) * (it - avg) }.average()
            ((last?.get(4) ?: avg) - avg) / kotlin.math.sqrt(variance.coerceAtLeast(1e-12))
        } else 0.0
        val volumeSurprise = (volumeZ / 3.0).coerceIn(-1.0, 1.0)
        val rangePosition = if (bars.size >= 20 && s.price.isFinite()) {
            val recent = bars.takeLast(20)
            val lo = recent.minOf { it[2] }; val hi = recent.maxOf { it[1] }
            if (hi > lo) (((s.price - lo) / (hi - lo)) * 2.0 - 1.0).coerceIn(-1.0, 1.0) else 0.0
        } else 0.0
        fun returnFeature(lookback: Int): Double {
            if (bars.size <= lookback) return 0.0
            val old = bars[bars.lastIndex - lookback][3]
            return if (old > 0.0) ((s.price / old - 1.0) * 100.0 / 5.0).coerceIn(-1.0, 1.0) else 0.0
        }
        val return3 = returnFeature(3)
        val return12 = returnFeature(12)
        val returns = bars.zipWithNext().mapNotNull { (a, b) ->
            if (a[3] > 0.0) (b[3] / a[3] - 1.0) else null
        }
        val volRatio = if (returns.size >= 20) {
            val short = returns.takeLast(5).map { it * it }.average()
            val long = returns.takeLast(20).map { it * it }.average()
            (kotlin.math.sqrt(short / long.coerceAtLeast(1e-12)) - 1.0).coerceIn(-1.0, 1.0)
        } else 0.0
        val utcHour = last?.getOrNull(5)?.takeIf { it > 0.0 }?.let {
            java.time.Instant.ofEpochMilli(it.toLong()).atZone(java.time.ZoneOffset.UTC).hour
        } ?: 0
        val hourAngle = 2.0 * Math.PI * utcHour / 24.0
        val sessionSin = kotlin.math.sin(hourAngle)
        val sessionCos = kotlin.math.cos(hourAngle)
        val regime = s.technical.regime.lowercase()
        val regimeFeature = when {
            regime.contains("trend") || regime.contains("روند") -> 0.75
            regime.contains("range") || regime.contains("رنج") || regime.contains("خنثی") -> -0.25
            regime.contains("high") || regime.contains("بالا") -> -0.75
            else -> 0.0
        }

        return doubleArrayOf(
            trend * direction, rsi * direction, adx, volume, pattern * direction, volatility,
            (s.score / 100.0).coerceIn(-1.0, 1.0), news * direction,
            marketSentiment * direction, fearGreed * direction, flow * direction,
            book * direction, -funding * direction,
            chartStructure, timeframeConsensus, timeframeCoverage,
            supportDistance, resistanceDistance, vwapDistance,
            obv, mfi, cci, roc, sweep, fvg, macd,
            bodyPressure, wickImbalance, volumeSurprise, rangePosition,
            return3, return12, volRatio, sessionSin, sessionCos, regimeFeature
        ).map { it.takeIf(Double::isFinite)?.coerceIn(-1.0, 1.0) ?: 0.0 }.toDoubleArray()
    }

    private fun fresh(): Model {
        var seed = 0x574F4C46L
        fun next(): Double {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return (((seed ushr 33).toDouble() / Int.MAX_VALUE.toDouble()) * 2.0 - 1.0) * 0.12
        }
        val w1 = Array(H1) { DoubleArray(INPUTS) { next() } }
        val w2 = Array(H2) { DoubleArray(H1) { next() } }
        val w3 = DoubleArray(H2) { next() }
        return Model(w1, DoubleArray(H1), w2, DoubleArray(H2), w3, 0.0)
    }

    private fun encode(m: Model): String {
        fun arr(values: DoubleArray) = JSONArray().also { a -> values.forEach { a.put(it) } }
        fun matrix(values: Array<DoubleArray>) = JSONArray().also { a -> values.forEach { a.put(arr(it)) } }
        return JSONObject().put("w1", matrix(m.w1)).put("b1", arr(m.b1))
            .put("w2", matrix(m.w2)).put("b2", arr(m.b2))
            .put("w3", arr(m.w3)).put("b3", m.b3).toString()
    }

    private fun decode(raw: String): Model? = runCatching {
        val j = JSONObject(raw)
        fun vector(key: String, size: Int): DoubleArray {
            val a = j.getJSONArray(key)
            require(a.length() == size)
            return DoubleArray(size) { a.getDouble(it).coerceIn(-8.0, 8.0) }
        }
        fun matrix(key: String, rows: Int, cols: Int): Array<DoubleArray> {
            val a = j.getJSONArray(key)
            require(a.length() == rows)
            return Array(rows) { r ->
                val row = a.getJSONArray(r)
                require(row.length() == cols)
                DoubleArray(cols) { c -> row.getDouble(c).coerceIn(-8.0, 8.0) }
            }
        }
        val oldW1 = j.getJSONArray("w1")
        require(oldW1.length() == H1)
        val migratedW1 = Array(H1) { rowIndex ->
            val row = oldW1.getJSONArray(rowIndex)
            require(row.length() in setOf(INPUTS, 13, 7))
            DoubleArray(INPUTS) { col -> if (col < row.length()) row.getDouble(col).coerceIn(-8.0, 8.0) else 0.0 }
        }
        Model(migratedW1, vector("b1", H1),
            matrix("w2", H2, H1), vector("b2", H2),
            vector("w3", H2), j.optDouble("b3", 0.0).coerceIn(-8.0, 8.0))
    }.getOrNull()

    private fun load(p: SharedPreferences): Model {
        val saved = p.getString(KEY, null)?.let(::decode)
        if (saved != null) return saved
        // Migrate the existing 13-feature learner into the richer model. New non-indicator
        // inputs start at zero weight and are learned only from subsequently closed outcomes.
        val legacyDeep = p.getString(LEGACY_DEEP_KEY, null)?.let(::decode)
        if (legacyDeep != null) {
            p.edit().putString(KEY, encode(legacyDeep)).apply()
            return legacyDeep
        }
        // One-time migration from the earlier linear learner.
        val m = fresh()
        val old = p.getString(LEGACY_KEY, null)
        if (old != null) runCatching {
            val a = JSONArray(old)
            for (i in 0 until minOf(INPUTS, a.length())) m.w1[0][i] = a.optDouble(i, 0.0).coerceIn(-1.0, 1.0)
        }
        return m
    }

    private data class Forward(val h1: DoubleArray, val h2: DoubleArray, val output: Double)

    private fun forward(m: Model, x: DoubleArray): Forward {
        val h1 = DoubleArray(H1) { i -> tanh(m.b1[i] + (0 until INPUTS).sumOf { j -> m.w1[i][j] * x[j] }) }
        val h2 = DoubleArray(H2) { i -> tanh(m.b2[i] + (0 until H1).sumOf { j -> m.w2[i][j] * h1[j] }) }
        val out = sigmoid(m.b3 + (0 until H2).sumOf { j -> m.w3[j] * h2[j] })
        return Forward(h1, h2, out)
    }

    fun featureVector(signal: MarketSignal, context: Context? = null): DoubleArray = features(signal, context)
    fun reviewedSamples(prefs: SharedPreferences): Int = prefs.getInt(SAMPLE_COUNT_KEY, 0)
    fun predict(prefs: SharedPreferences, signal: MarketSignal, context: Context? = null): Double =
        forward(load(prefs), features(signal, context)).output.coerceIn(0.01, 0.99)
    @Synchronized
    fun learn(prefs: SharedPreferences, signal: MarketSignal, outcome: Double) =
        learn(prefs, features(signal), outcome)
    @Synchronized
    fun learn(prefs: SharedPreferences, xInput: DoubleArray, outcome: Double) {
        require(xInput.size in 13..INPUTS && xInput.all { it.isFinite() })
        val m = load(prefs)
        val x = DoubleArray(INPUTS) { i -> if (i < xInput.size) xInput[i].coerceIn(-1.0, 1.0) else 0.0 }
        val target = outcome.coerceIn(0.0, 1.0)
        // A few conservative passes over the newly reviewed example.
        repeat(4) {
            val f = forward(m, x)
            val d3 = (f.output - target)
            val gradW3 = DoubleArray(H2) { j -> d3 * f.h2[j] }
            val delta2 = DoubleArray(H2) { j -> d3 * m.w3[j] * (1.0 - f.h2[j] * f.h2[j]) }
            val delta1 = DoubleArray(H1) { j ->
                val back = (0 until H2).sumOf { k -> m.w2[k][j] * delta2[k] }
                back * (1.0 - f.h1[j] * f.h1[j])
            }
            val rate = 0.008
            for (j in 0 until H2) {
                m.w3[j] = (m.w3[j] - rate * (gradW3[j] + 0.0005 * m.w3[j])).coerceIn(-5.0, 5.0)
                for (k in 0 until H1) m.w2[j][k] = (m.w2[j][k] - rate * (delta2[j] * f.h1[k] + 0.0005 * m.w2[j][k])).coerceIn(-5.0, 5.0)
                m.b2[j] = (m.b2[j] - rate * delta2[j]).coerceIn(-5.0, 5.0)
            }
            m.b3 = (m.b3 - rate * d3).coerceIn(-5.0, 5.0)
            for (j in 0 until H1) {
                for (k in 0 until INPUTS) m.w1[j][k] = (m.w1[j][k] - rate * (delta1[j] * x[k] + 0.0005 * m.w1[j][k])).coerceIn(-5.0, 5.0)
                m.b1[j] = (m.b1[j] - rate * delta1[j]).coerceIn(-5.0, 5.0)
            }
        }
        prefs.edit().putString(KEY, encode(m))
            .putInt(SAMPLE_COUNT_KEY, prefs.getInt(SAMPLE_COUNT_KEY, 0) + 1).apply()
    }
}
