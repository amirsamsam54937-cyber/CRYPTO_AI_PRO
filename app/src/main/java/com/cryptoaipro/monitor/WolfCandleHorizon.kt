package com.cryptoaipro.monitor

/** Enforces the same closed-hour candle boundary used by the 1h-trained, 6h-horizon model. */
internal object WolfCandleHorizon {
    const val HOUR_MS = 60L * 60L * 1000L
    private const val NEUTRAL_RETURN = 0.0025

    fun closedHourlyCandles(candles: List<DoubleArray>, nowMs: Long): List<DoubleArray> {
        return candles.asSequence()
            .filter { row ->
                if (row.size < 6) return@filter false
                val values = row.take(6)
                if (values.any { !it.isFinite() }) return@filter false
                val openTime = row[5]
                if (openTime <= 0.0 || openTime != kotlin.math.floor(openTime)) return@filter false
                if (openTime + HOUR_MS > nowMs.toDouble()) return@filter false
                val open = row[0]
                val high = row[1]
                val low = row[2]
                val close = row[3]
                open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0 &&
                    high >= maxOf(open, close) && low <= minOf(open, close) && high >= low
            }
            .sortedBy { it[5] }
            .distinctBy { it[5].toLong() }
            .toList()
    }

    /** null means the move stayed inside the training neutral band and must not score a vote. */
    fun directionalOutcome(baseClose: Double, futureClose: Double): Boolean? {
        require(baseClose.isFinite() && futureClose.isFinite() && baseClose > 0.0 && futureClose > 0.0)
        val forwardReturn = futureClose / baseClose - 1.0
        return when {
            forwardReturn > NEUTRAL_RETURN -> true
            forwardReturn < -NEUTRAL_RETURN -> false
            else -> null
        }
    }
}
