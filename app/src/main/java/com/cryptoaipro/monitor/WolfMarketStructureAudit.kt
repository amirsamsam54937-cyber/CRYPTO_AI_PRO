package com.cryptoaipro.monitor

/**
 * Causal market-structure audit over completed OHLCV candles.
 * Pivots are only confirmed after rightBars candles have closed, preventing look-ahead.
 * This module is descriptive only: it does not change signal direction or confidence.
 */
internal object WolfMarketStructureAudit {
    data class Report(
        val available: Boolean,
        val bias: String,
        val lastSwingHigh: Double,
        val lastSwingLow: Double,
        val event: String,
        val liquiditySweep: String,
        val pivotCount: Int,
        val explanation: String
    )

    private data class Pivot(val index: Int, val price: Double, val high: Boolean)

    fun analyze(
        candles: List<DoubleArray>,
        leftBars: Int = 3,
        rightBars: Int = 2,
        maxBars: Int = 120
    ): Report {
        require(leftBars >= 1 && rightBars >= 1 && maxBars >= leftBars + rightBars + 5)
        val valid = candles.asSequence()
            .filter { c ->
                c.size >= 5 && c.take(5).all { it.isFinite() } &&
                    c[0] > 0.0 && c[1] > 0.0 && c[2] > 0.0 && c[3] > 0.0 &&
                    c[1] >= maxOf(c[0], c[3]) && c[2] <= minOf(c[0], c[3]) &&
                    c[1] >= c[2] && c[4] >= 0.0
            }
            .toList()
            .takeLast(maxBars)
        if (valid.size < leftBars + rightBars + 5) return unavailable()

        val pivots = mutableListOf<Pivot>()
        // The right-side confirmation window must be fully closed before a pivot is known.
        for (i in leftBars until valid.size - rightBars) {
            val high = valid[i][1]
            val low = valid[i][2]
            val leftHighs = (i - leftBars until i).map { valid[it][1] }
            val rightHighs = (i + 1..i + rightBars).map { valid[it][1] }
            val leftLows = (i - leftBars until i).map { valid[it][2] }
            val rightLows = (i + 1..i + rightBars).map { valid[it][2] }
            if (leftHighs.all { high > it } && rightHighs.all { high >= it }) pivots += Pivot(i, high, true)
            if (leftLows.all { low < it } && rightLows.all { low <= it }) pivots += Pivot(i, low, false)
        }
        val lastHigh = pivots.lastOrNull { it.high }
        val lastLow = pivots.lastOrNull { !it.high }
        if (lastHigh == null || lastLow == null) return unavailable(pivots.size)

        val current = valid.last()
        val close = current[3]
        val prior = valid[valid.lastIndex - 1]
        val brokeHigh = close > lastHigh.price
        val brokeLow = close < lastLow.price
        val sweptHigh = current[1] > lastHigh.price && close < lastHigh.price
        val sweptLow = current[2] < lastLow.price && close > lastLow.price
        val event = when {
            brokeHigh -> "شکست صعودی ساختار (BOS)"
            brokeLow -> "شکست نزولی ساختار (BOS)"
            sweptHigh -> "جمع‌آوری نقدینگی بالای سقف؛ بازگشت قیمت"
            sweptLow -> "جمع‌آوری نقدینگی زیر کف؛ بازگشت قیمت"
            close > prior[3] && close > (current[0] + current[1]) / 2.0 -> "حرکت صعودی؛ شکست ساختار تأیید نشده"
            close < prior[3] && close < (current[0] + current[2]) / 2.0 -> "حرکت نزولی؛ شکست ساختار تأیید نشده"
            else -> "ساختار بدون شکست تازه"
        }
        val bias = when {
            brokeHigh || sweptLow -> "صعودی"
            brokeLow || sweptHigh -> "نزولی"
            else -> "خنثی / نامشخص"
        }
        val sweep = when {
            sweptHigh -> "سقف نقدینگی لمس و پس گرفته شد"
            sweptLow -> "کف نقدینگی لمس و پس گرفته شد"
            else -> "در آخرین کندل sweep تأیید نشد"
        }
        return Report(
            true, bias, lastHigh.price, lastLow.price, event, sweep, pivots.size,
            "پیوت‌ها با تأیید $rightBars کندل بعدی محاسبه شده‌اند؛ این گزارش توصیفی است و سیگنال را مجاز یا تضمین نمی‌کند."
        )
    }

    private fun unavailable(pivots: Int = 0) = Report(
        false, "نامشخص", 0.0, 0.0, "داده ساختاری کافی نیست",
        "قابل ارزیابی نیست", pivots,
        "برای ممیزی ساختار، کندل‌های معتبر و پیوت‌های تأییدشده کافی نیست."
    )
}
