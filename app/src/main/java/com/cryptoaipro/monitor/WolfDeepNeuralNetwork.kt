package com.cryptoaipro.monitor

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Small on-device deep feed-forward neural network.
 * Architecture: 7 normalized technical features -> 16 tanh -> 8 tanh -> 1 sigmoid.
 * Online fine-tuned from measured paper-signal outcomes; not pretrained and not a guaranteed predictor.
 */
internal object WolfDeepNeuralNetwork {
    private const val INPUTS = 7
    private const val H1 = 16
    private const val H2 = 8
    private const val KEY = "wolf_dnn_v1"
    private const val LEGACY_KEY = "wolf_model_weights"
    private const val REPLAY_KEY = "wolf_dnn_replay_v1"
    private const val MAX_REPLAY = 256

    private data class Model(
        val w1: Array<DoubleArray>, val b1: DoubleArray,
        val w2: Array<DoubleArray>, val b2: DoubleArray,
        val w3: DoubleArray, var b3: Double
    )

    private fun sigmoid(x: Double): Double {
        val z = x.coerceIn(-30.0, 30.0)
        return 1.0 / (1.0 + exp(-z))
    }

    private fun features(s: MarketSignal): DoubleArray {
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
        return doubleArrayOf(trend * direction, rsi * direction, adx, volume, pattern * direction, volatility, (s.score / 100.0).coerceIn(-1.0, 1.0))
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
        Model(matrix("w1", H1, INPUTS), vector("b1", H1),
            matrix("w2", H2, H1), vector("b2", H2),
            vector("w3", H2), j.optDouble("b3", 0.0).coerceIn(-8.0, 8.0))
    }.getOrNull()

    private fun load(p: SharedPreferences): Model {
        val saved = p.getString(KEY, null)?.let(::decode)
        if (saved != null) return saved
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

    fun predict(prefs: SharedPreferences, signal: MarketSignal): Double =
        forward(load(prefs), features(signal)).output.coerceIn(0.01, 0.99)

    /**
     * Online replay learning: every confirmed paper outcome is retained in a bounded,
     * local replay buffer. Small balanced batches reduce catastrophic forgetting from
     * training only on the newest result. This updates the on-device scorer, not the
     * ten pretrained ensemble and never changes APK code.
     */
    @Synchronized
    fun learn(prefs: SharedPreferences, signal: MarketSignal, outcome: Double) {
        if (!outcome.isFinite()) return
        val x = features(signal)
        if (x.any { !it.isFinite() }) return
        val target = outcome.coerceIn(0.0, 1.0)
        val replay = runCatching {
            JSONArray(prefs.getString(REPLAY_KEY, "[]") ?: "[]")
        }.getOrDefault(JSONArray())

        replay.put(JSONObject().apply {
            put("x", JSONArray().also { values -> x.forEach { values.put(it) } })
            put("y", target)
            put("time", System.currentTimeMillis())
        })
        while (replay.length() > MAX_REPLAY) replay.remove(0)

        val examples = (0 until replay.length()).mapNotNull { index ->
            val row = replay.optJSONObject(index) ?: return@mapNotNull null
            val values = row.optJSONArray("x") ?: return@mapNotNull null
            if (values.length() != INPUTS) return@mapNotNull null
            val features = DoubleArray(INPUTS) { values.optDouble(it, Double.NaN) }
            val label = row.optDouble("y", Double.NaN)
            if (features.any { !it.isFinite() } || !label.isFinite()) null
            else features to label.coerceIn(0.0, 1.0)
        }
        if (examples.isEmpty()) return

        // Use up to 12 examples from each class when available; keep the newest examples.
        val positives = examples.filter { it.second >= 0.5 }.takeLast(12)
        val negatives = examples.filter { it.second < 0.5 }.takeLast(12)
        // Do not adapt weights from a one-sided sample stream. Keep collecting until
        // both outcomes have enough observations to form a minimally balanced batch.
        if (positives.size < 4 || negatives.size < 4) {
            prefs.edit()
                .putString(REPLAY_KEY, replay.toString())
                .putInt("wolf_replay_examples", examples.size)
                .apply()
            return
        }
        val batch = positives + negatives
        val model = load(prefs)
        repeat(2) {
            batch.forEach { (features, label) -> trainStep(model, features, label, 0.003) }
        }

        prefs.edit()
            .putString(KEY, encode(model))
            .putString(REPLAY_KEY, replay.toString())
            .putInt("wolf_replay_examples", examples.size)
            .putLong("wolf_last_replay_training", System.currentTimeMillis())
            .apply()
    }

    private fun trainStep(m: Model, x: DoubleArray, target: Double, rate: Double) {
        val f = forward(m, x)
        val d3 = f.output - target
        val gradW3 = DoubleArray(H2) { j -> d3 * f.h2[j] }
        val delta2 = DoubleArray(H2) { j -> d3 * m.w3[j] * (1.0 - f.h2[j] * f.h2[j]) }
        val delta1 = DoubleArray(H1) { j ->
            val back = (0 until H2).sumOf { k -> m.w2[k][j] * delta2[k] }
            back * (1.0 - f.h1[j] * f.h1[j])
        }
        for (j in 0 until H2) {
            m.w3[j] = (m.w3[j] - rate * (gradW3[j] + 0.0005 * m.w3[j])).coerceIn(-5.0, 5.0)
            for (k in 0 until H1) {
                m.w2[j][k] = (m.w2[j][k] - rate * (delta2[j] * f.h1[k] + 0.0005 * m.w2[j][k])).coerceIn(-5.0, 5.0)
            }
            m.b2[j] = (m.b2[j] - rate * delta2[j]).coerceIn(-5.0, 5.0)
        }
        m.b3 = (m.b3 - rate * d3).coerceIn(-5.0, 5.0)
        for (j in 0 until H1) {
            for (k in 0 until INPUTS) {
                m.w1[j][k] = (m.w1[j][k] - rate * (delta1[j] * x[k] + 0.0005 * m.w1[j][k])).coerceIn(-5.0, 5.0)
            }
            m.b1[j] = (m.b1[j] - rate * delta1[j]).coerceIn(-5.0, 5.0)
        }
    }
