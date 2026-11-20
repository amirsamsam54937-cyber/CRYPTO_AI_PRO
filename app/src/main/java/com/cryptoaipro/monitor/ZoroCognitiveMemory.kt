package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Four-tier on-device cognitive memory.
 * Tier 1: rolling short-term market context.
 * Tier 2: immutable signal episodes.
 * Tier 3: rolling symbol/timeframe/regime summaries.
 * Tier 4: long-lived model lineage and validated knowledge checkpoints.
 *
 * This stores evidence and model lineage; it does not pretend that snapshots are
 * supervised labels or that a model has improved without outcome validation.
 */
internal object ZoroCognitiveMemory {
    private const val PREFS = "zoro_cognitive_memory"
    private const val SHORT = "short_term_v1"
    private const val EPISODES = "episodes_v1"
    private const val MEDIUM = "medium_patterns_v1"
    private const val DEEP = "deep_knowledge_v1"
    private const val SHORT_LIMIT = 96
    private const val EPISODE_LIMIT = 5000
    private const val MEDIUM_LIMIT = 300

    data class Status(
        val shortTerm: Int,
        val episodes: Int,
        val mediumPatterns: Int,
        val deepCheckpoints: Int,
        val lastUpdated: Long
    )

    @Synchronized
    fun observe(context: Context, signal: MarketSignal, now: Long = System.currentTimeMillis()) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val short = read(p.getString(SHORT, "[]"))
        val episodes = read(p.getString(EPISODES, "[]"))
        val medium = read(p.getString(MEDIUM, "[]"))
        val deep = read(p.getString(DEEP, "[]"))

        val event = JSONObject().apply {
            put("at", now)
            put("symbol", signal.symbol)
            put("direction", signal.direction)
            put("neuralDirection", signal.neuralDirection)
            put("neuralScore", signal.neuralScore)
            put("neuralSource", signal.neuralModelSource)
            put("timeframe", signal.tradeTimeframe)
            put("price", signal.price)
            put("rsi", signal.rsi)
            put("adx", signal.technical.adx)
            put("atrPct", signal.technical.atrPercent)
            put("relativeVolume", signal.technical.relativeVolume)
            put("regime", signal.technical.regime)
            put("newsAndFlowContext", signal.reason.take(1200))
            put("deepDnnProbability", signal.deepNeuralSuccessProbability ?: JSONObject.NULL)
            put("deepDnnSamples", signal.deepNeuralReviewedSamples)
            put("tier", signal.signalTier)
        }

        short.put(event)
        episodes.put(JSONObject(event.toString()))
        trim(short, SHORT_LIMIT)
        trim(episodes, EPISODE_LIMIT)

        val key = listOf(signal.symbol, signal.tradeTimeframe, signal.technical.regime).joinToString("|")
        var bucket: JSONObject? = null
        for (i in 0 until medium.length()) {
            val candidate = medium.optJSONObject(i) ?: continue
            if (candidate.optString("key") == key) { bucket = candidate; break }
        }
        if (bucket == null) {
            bucket = JSONObject().put("key", key).put("symbol", signal.symbol)
                .put("timeframe", signal.tradeTimeframe).put("regime", signal.technical.regime)
                .put("observations", 0).put("lastSeen", 0L)
            medium.put(bucket)
        }
        bucket.put("observations", bucket.optInt("observations") + 1)
        bucket.put("lastSeen", now)
        bucket.put("lastNeuralDirection", signal.neuralDirection)
        bucket.put("lastNeuralScore", signal.neuralScore)
        bucket.put("lastDeepDnnSamples", signal.deepNeuralReviewedSamples)
        trim(medium, MEDIUM_LIMIT)

        // Preserve a lineage checkpoint only when the app's own strict model gate says
        // the pretrained ensemble passed chronological holdout validation.
        if (WolfPretrainedEnsemble.status().validatedForScoring) {
            val checkpoint = JSONObject().apply {
                put("at", now)
                put("model", "wolf_pretrained_ensemble")
                put("status", "validated_for_scoring")
                put("members", WolfPretrainedEnsemble.status().members)
                put("samples", WolfPretrainedEnsemble.status().samples)
                put("corpusRows", WolfPretrainedEnsemble.status().corpusRows)
                put("symbol", signal.symbol)
                put("timeframe", signal.tradeTimeframe)
            }
            val last = deep.optJSONObject(deep.length() - 1)
            if (last == null || last.optString("model") != checkpoint.optString("model") ||
                last.optInt("samples") != checkpoint.optInt("samples")) deep.put(checkpoint)
            trim(deep, 200)
        }

        p.edit()
            .putString(SHORT, short.toString())
            .putString(EPISODES, episodes.toString())
            .putString(MEDIUM, medium.toString())
            .putString(DEEP, deep.toString())
            .putLong("last_updated", now)
            .apply()
    }

    fun status(context: Context): Status {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Status(
            read(p.getString(SHORT, "[]")).length(),
            read(p.getString(EPISODES, "[]")).length(),
            read(p.getString(MEDIUM, "[]")).length(),
            read(p.getString(DEEP, "[]")).length(),
            p.getLong("last_updated", 0L)
        )
    }

    private fun read(raw: String?): JSONArray =
        runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }

    private fun trim(array: JSONArray, limit: Int) {
        if (array.length() <= limit) return
        val kept = JSONArray()
        for (i in (array.length() - limit) until array.length()) kept.put(array.get(i))
        while (array.length() > 0) array.remove(0)
        for (i in 0 until kept.length()) array.put(kept.get(i))
    }
}
