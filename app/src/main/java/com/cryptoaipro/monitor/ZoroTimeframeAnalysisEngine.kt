package com.cryptoaipro.monitor

/** Multi-timeframe OHLCV reader for every interval exposed by XT's futures kline REST endpoint. */
internal object ZoroTimeframeAnalysisEngine {
    data class Frame(
        val timeframe: String,
        val available: Boolean,
        val score: Int,
        val direction: String,
        val phase: String,
        val structure: String,
        val patterns: List<String>,
        val bullishScenario: String,
        val bearishScenario: String,
        val candleCount: Int,
        val lastClosedAt: Long,
        val warnings: List<String>
    )

    private val order = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w")
    private val weights = mapOf(
        "1m" to 0.45, "5m" to 0.70, "15m" to 0.90, "30m" to 1.0,
        "1h" to 1.25, "4h" to 1.55, "1d" to 1.85, "1w" to 2.10
    )

    fun analyze(frames: Map<String, List<DoubleArray>>): List<Frame> =
        order.mapNotNull { timeframe ->
            val candles = frames[timeframe] ?: return@mapNotNull null
            val read = ZoroHumanMarketReader.analyze(candles)
            val score = if (read.available) read.score else 0
            Frame(
                timeframe = timeframe,
                available = read.available,
                score = score,
                direction = when {
                    !read.available -> "WAIT"
                    score >= 25 -> "LONG"
                    score <= -25 -> "SHORT"
                    else -> "WAIT"
                },
                phase = read.phase,
                structure = read.structure,
                patterns = read.patterns,
                bullishScenario = read.bullishScenario,
                bearishScenario = read.bearishScenario,
                candleCount = candles.size,
                lastClosedAt = candles.lastOrNull()?.getOrNull(5)?.toLong() ?: 0L,
                warnings = read.warnings
            )
        }

    /** Weighted evidence index, not a probability. Higher timeframes carry more context weight. */
    fun consensusScore(frames: List<Frame>): Int {
        val usable = frames.filter { it.available && it.timeframe in weights }
        if (usable.isEmpty()) return 0
        val denominator = usable.sumOf { weights.getValue(it.timeframe) }
        if (denominator <= 0.0) return 0
        return usable.sumOf { it.score * weights.getValue(it.timeframe) }
            .div(denominator).toInt().coerceIn(-100, 100)
    }

    fun opposingCount(frames: List<Frame>, side: String): Int {
        val target = side.uppercase()
        if (target != "LONG_BIAS" && target != "SHORT_BIAS") return 0
        val opposing = if (target == "LONG_BIAS") "SHORT" else "LONG"
        return frames.count { it.available && it.direction == opposing }
    }
}
