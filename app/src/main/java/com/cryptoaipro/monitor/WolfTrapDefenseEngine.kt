package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Trap-defense layer built only from closed OHLCV candles.
 * Detects failed auction / stop-run proxies and refuses to label them as confirmed
 * exchange liquidations, spoofing, or intentional manipulation.
 */
internal object WolfTrapDefenseEngine {
    enum class Trap { NONE, BUY_SIDE_TRAP, SELL_SIDE_TRAP, TWO_SIDED_UNCERTAINTY }

    data class Report(
        val available: Boolean,
        val trap: Trap,
        val severity: Int,
        val level: Double?,
        val volumeMultiple: Double,
        val acceptanceConfirmed: Boolean,
        val blocksLong: Boolean,
        val blocksShort: Boolean,
        val evidence: List<String>,
        val explanation: String
    )

    fun analyze(input: List<DoubleArray>, lookback: Int = 48): Report {
        val bars = input.asSequence().filter(::valid).sortedBy { it[5] }
            .distinctBy { it[5].toLong() }.toList().takeLast(lookback.coerceIn(20, 160))
        if (bars.size < 22) return unavailable()
        val last = bars.last()
        val prior = bars.dropLast(2).takeLast(20)
        val priorHigh = prior.maxOf { it[1] }
        val priorLow = prior.minOf { it[2] }
        val range = (last[1] - last[2]).coerceAtLeast(1e-12)
        val closeLocation = ((last[3] - last[2]) / range).coerceIn(0.0, 1.0)
        val body = abs(last[3] - last[0])
        val upperWick = last[1] - max(last[0], last[3])
        val lowerWick = min(last[0], last[3]) - last[2]
        val avgVolume = prior.takeLast(20).map { it[4] }.average().coerceAtLeast(1e-12)
        val volumeMultiple = last[4] / avgVolume
        val atr = (1 until bars.size).toList().takeLast(14).map { i ->
            max(bars[i][1] - bars[i][2],
                max(abs(bars[i][1] - bars[i - 1][3]), abs(bars[i][2] - bars[i - 1][3])))
        }.average().coerceAtLeast(last[3] * 0.0001)
        val tolerance = max(atr * 0.08, last[3] * 0.00015)

        val sweptHigh = last[1] > priorHigh + tolerance && last[3] < priorHigh
        val sweptLow = last[2] < priorLow - tolerance && last[3] > priorLow
        val upperRejection = upperWick / range >= 0.48 && closeLocation <= 0.42
        val lowerRejection = lowerWick / range >= 0.48 && closeLocation >= 0.58
        val effortNoResult = volumeMultiple >= 1.8 && body / range <= 0.28

        // A breakout is accepted only after two completed closes outside the level.
        val previousClose = bars[bars.lastIndex - 1][3]
        val acceptanceUp = last[3] > priorHigh + tolerance &&
            previousClose > priorHigh && closeLocation >= 0.65
        val acceptanceDown = last[3] < priorLow - tolerance &&
            previousClose < priorLow && closeLocation <= 0.35

        val evidence = mutableListOf<String>()
        var buyTrap = false
        var sellTrap = false
        var severity = 0
        if (sweptHigh) {
            buyTrap = true
            severity += 42
            evidence += "قیمت بالای سقف ۲۰ کندل نفوذ کرد اما زیر سطح بسته شد؛ تله احتمالی خریداران"
        }
        if (sweptLow) {
            sellTrap = true
            severity += 42
            evidence += "قیمت زیر کف ۲۰ کندل نفوذ کرد اما بالای سطح بسته شد؛ تله احتمالی فروشندگان"
        }
        if (upperRejection && last[1] >= priorHigh - tolerance && !acceptanceUp) {
            buyTrap = true
            severity += 16
            evidence += "سایه بالایی و بسته‌شدن ضعیف نزدیک مقاومت"
        }
        if (lowerRejection && last[2] <= priorLow + tolerance && !acceptanceDown) {
            sellTrap = true
            severity += 16
            evidence += "سایه پایینی و بسته‌شدن قوی نزدیک حمایت"
        }
        if (effortNoResult) {
            severity += 10
            evidence += "حجم حداقل ۱٫۸ برابر میانگین با پیشروی اندک قیمت؛ جذب سفارش یا توقف حرکت محتمل"
        }
        if (acceptanceUp) evidence += "شکست سقف با دو بسته‌شدن متوالی بیرون سطح پذیرفته شده است"
        if (acceptanceDown) evidence += "شکست کف با دو بسته‌شدن متوالی بیرون سطح پذیرفته شده است"

        // Confirmed acceptance overrides a one-candle wick warning in that same direction.
        if (acceptanceUp) buyTrap = false
        if (acceptanceDown) sellTrap = false
        val trap = when {
            buyTrap && sellTrap -> Trap.TWO_SIDED_UNCERTAINTY
            buyTrap -> Trap.BUY_SIDE_TRAP
            sellTrap -> Trap.SELL_SIDE_TRAP
            else -> Trap.NONE
        }
        severity = when (trap) {
            Trap.BUY_SIDE_TRAP, Trap.SELL_SIDE_TRAP -> severity.coerceIn(0, 100)
            Trap.TWO_SIDED_UNCERTAINTY -> max(severity, 65).coerceIn(0, 100)
            Trap.NONE -> if (acceptanceUp || acceptanceDown) 0 else severity.coerceAtMost(25)
        }
        return Report(
            true, trap, severity, when (trap) {
                Trap.BUY_SIDE_TRAP -> priorHigh
                Trap.SELL_SIDE_TRAP -> priorLow
                else -> null
            }, volumeMultiple, acceptanceUp || acceptanceDown,
            blocksLong = trap == Trap.BUY_SIDE_TRAP || trap == Trap.TWO_SIDED_UNCERTAINTY,
            blocksShort = trap == Trap.SELL_SIDE_TRAP || trap == Trap.TWO_SIDED_UNCERTAINTY,
            evidence = evidence.distinct(),
            explanation = when (trap) {
                Trap.BUY_SIDE_TRAP -> "احتمال تله لانگ/شکار نقدینگی بالای سقف؛ ورود لانگ تا تأیید مجدد ممنوع"
                Trap.SELL_SIDE_TRAP -> "احتمال تله شورت/شکار نقدینگی زیر کف؛ ورود شورت تا تأیید مجدد ممنوع"
                Trap.TWO_SIDED_UNCERTAINTY -> "نشانه‌های تله دوطرفه؛ بازار آشفته است و تصمیم صحیح فعلاً عدم معامله است"
                Trap.NONE -> if (acceptanceUp || acceptanceDown) "شکست با پذیرش اولیه؛ همچنان نیازمند مدیریت ریسک"
                    else "تله قاطع تأیید نشد؛ نبود شواهد به معنی نبود دستکاری نیست"
            }
        )
    }

    private fun valid(c: DoubleArray): Boolean =
        c.size >= 6 && c.take(6).all { it.isFinite() } &&
            c[0] > 0.0 && c[1] >= max(c[0], c[3]) && c[2] <= min(c[0], c[3]) &&
            c[2] > 0.0 && c[3] > 0.0 && c[4] >= 0.0 && c[5] > 0.0

    private fun unavailable() = Report(
        false, Trap.NONE, 0, null, 0.0, false, false, false,
        emptyList(), "برای تشخیص تله حداقل ۲۲ کندل معتبر لازم است؛ هیچ نتیجه‌ای حدس زده نمی‌شود"
    )
}
