package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Deep candle-native chart forensics. OHLCV estimates only; not hidden order-book facts.
 * Rows: [open, high, low, close, volume, timestamp].
 */
internal object WolfChartForensicsEngine {
    data class Zone(val label: String, val low: Double, val high: Double, val strength: Int)
    data class Report(
        val available: Boolean, val profile: String, val poc: Double?, val valueLow: Double?,
        val valueHigh: Double?, val vwap: Double?, val vwapDistancePct: Double?,
        val rsi: Double?, val rsiDivergence: String, val obvDivergence: String,
        val structure: String, val gaps: List<Zone>, val supplyDemand: List<Zone>,
        val volatility: String, val candleRead: String, val cautions: List<String>
    )

    fun analyze(input: List<DoubleArray>): Report {
        val bars = input.filter(::valid).sortedBy { it[5] }.distinctBy { it[5].toLong() }.takeLast(240)
        if (bars.size < 80) return empty("برای ممیزی عمیق چارت حداقل ۸۰ کندل معتبر لازم است")
        val last = bars.last()
        val low = bars.minOf { it[2] }; val high = bars.maxOf { it[1] }
        val width = (high - low).coerceAtLeast(1e-12)
        val bins = 32
        val profile = DoubleArray(bins)
        bars.forEach { c ->
            val typical = (c[1] + c[2] + c[3]) / 3.0
            val i = (((typical - low) / width) * bins).toInt().coerceIn(0, bins - 1)
            profile[i] += c[4]
        }
        val total = profile.sum().coerceAtLeast(1e-12)
        val pocIndex = profile.indices.maxByOrNull { profile[it] } ?: 0
        val poc = low + (pocIndex + .5) / bins * width
        val included = linkedSetOf(pocIndex)
        var vol = profile[pocIndex]; var l = pocIndex - 1; var r = pocIndex + 1
        while (vol / total < .70 && (l >= 0 || r < bins)) {
            val lv = if (l >= 0) profile[l] else -1.0
            val rv = if (r < bins) profile[r] else -1.0
            if (rv > lv) { included += r; vol += profile[r]; r++ }
            else if (l >= 0) { included += l; vol += profile[l]; l-- } else break
        }
        val vaLow = low + included.minOrNull()!! / bins * width
        val vaHigh = low + (included.maxOrNull()!! + 1.0) / bins * width
        val vBars = bars.takeLast(48)
        val vVol = vBars.sumOf { it[4] }.coerceAtLeast(1e-12)
        val vwap = vBars.sumOf { ((it[1] + it[2] + it[3]) / 3.0) * it[4] } / vVol
        val closes = bars.map { it[3] }
        val rsi = rsi(closes, 14)
        val rsiDiv = rsiDivergence(bars)
        val obvDiv = obvDivergence(bars)
        val highs = (2 until bars.lastIndex - 2).filter { i ->
            (i-2..i+2).all { j -> j == i || bars[i][1] >= bars[j][1] }
        }.takeLast(2)
        val lows = (2 until bars.lastIndex - 2).filter { i ->
            (i-2..i+2).all { j -> j == i || bars[i][2] <= bars[j][2] }
        }.takeLast(2)
        val structure = when {
            highs.size == 2 && lows.size == 2 &&
                bars[highs[1]][1] > bars[highs[0]][1] && bars[lows[1]][2] > bars[lows[0]][2] ->
                "ساختار HH/HL صعودی"
            highs.size == 2 && lows.size == 2 &&
                bars[highs[1]][1] < bars[highs[0]][1] && bars[lows[1]][2] < bars[lows[0]][2] ->
                "ساختار LH/LL نزولی"
            else -> "ساختار متداخل؛ تغییر روند تأیید نشده"
        }
        val gaps = mutableListOf<Zone>()
        for (i in max(2, bars.size - 30) until bars.size) {
            val a = bars[i-2]; val c = bars[i]
            if (c[2] > a[1]) gaps += Zone("FVG صعودی", a[1], c[2], 65)
            if (c[1] < a[2]) gaps += Zone("FVG نزولی", c[1], a[2], 65)
        }
        val zones = mutableListOf<Zone>()
        val avgVol = bars.takeLast(35).map { it[4] }.average().coerceAtLeast(1e-12)
        for (i in max(1, bars.size - 35) until bars.lastIndex) {
            val c = bars[i]
            val body = abs(c[3] - c[0])
            val previousBody = bars.subList(max(0, i-10), i).map { abs(it[3]-it[0]) }.average().coerceAtLeast(1e-12)
            if (body > previousBody * 1.8 && c[4] > avgVol * 1.25) {
                zones += Zone(if (c[3] > c[0]) "تقاضای احتمالی" else "عرضه احتمالی",
                    min(c[0],c[3]), max(c[0],c[3]), (body / (high-low) * 1000).toInt().coerceIn(1,100))
            }
        }
        val cRange = (last[1]-last[2]).coerceAtLeast(1e-12)
        val bodyRatio = abs(last[3]-last[0])/cRange
        val avgRange = bars.takeLast(20).map { it[1]-it[2] }.average().coerceAtLeast(1e-12)
        val volatility = when {
            cRange > avgRange*2 -> "کندل گسترشی؛ ریسک جهش نوسان"
            bars.takeLast(8).map { it[1]-it[2] }.average() < avgRange*.55 -> "فشردگی دامنه؛ جهت شکست نامعلوم"
            else -> "نوسان معمول"
        }
        val candle = when {
            (last[1]-max(last[0],last[3]))/cRange > .55 && bodyRatio < .3 -> "رد قیمت از بالا؛ فشار فروش احتمالی"
            (min(last[0],last[3])-last[2])/cRange > .55 && bodyRatio < .3 -> "رد قیمت از پایین؛ فشار خرید احتمالی"
            bodyRatio > .78 && last[3] > last[0] -> "بدنه صعودی قوی؛ نیازمند تأیید کندل بعد"
            bodyRatio > .78 && last[3] < last[0] -> "بدنه نزولی قوی؛ نیازمند تأیید کندل بعد"
            else -> "قصد کندل قطعی نیست"
        }
        return Report(true,
            "POC≈" + fmt(poc) + " • Value Area 70%: " + fmt(vaLow) + " تا " + fmt(vaHigh),
            poc, vaLow, vaHigh, vwap, (last[3]/vwap-1)*100, rsi, rsiDiv, obvDiv,
            structure, gaps.takeLast(8), zones.sortedByDescending { it.strength }.takeLast(8),
            volatility, candle,
            listOf(
                "پروفایل حجم از قیمت میانگین کندل تخمین زده شده؛ داده واقعی Volume-at-Price نیست.",
                "FVG و عرضه/تقاضا نواحی احتمالی‌اند، نه سفارش‌های پنهان یا تضمین واکنش.",
                "واگرایی به‌تنهایی سیگنال ورود نیست؛ تأیید قیمت و مدیریت ریسک لازم است."
            ))
    }

    private fun rsiDivergence(b: List<DoubleArray>): String {
        val pivots = (2 until b.lastIndex-2).filter { i ->
            (i-2..i+2).all { j -> j==i || b[i][2] <= b[j][2] }
        }.takeLast(2)
        if (pivots.size < 2) return "پیوت کافی برای واگرایی RSI نیست"
        val a=pivots[0]; val z=pivots[1]
        val closes=b.map { it[3] }; val ra=rsi(closes.take(a+1),14); val rz=rsi(closes.take(z+1),14)
        return when {
            b[z][2] < b[a][2] && rz > ra+3 -> "واگرایی مثبت احتمالی RSI"
            b[z][2] > b[a][2] && rz < ra-3 -> "واگرایی منفی احتمالی RSI"
            else -> "واگرایی RSI تأیید نشد"
        }
    }
    private fun obvDivergence(b: List<DoubleArray>): String {
        val s=b.takeLast(30); var obv=0.0
        for(i in 1 until s.size) obv += when {
            s[i][3]>s[i-1][3] -> s[i][4]
            s[i][3]<s[i-1][3] -> -s[i][4]
            else -> 0.0
        }
        val change=s.last()[3]/s.first()[3]-1
        return when {
            change>.004 && obv<0 -> "واگرایی منفی OBV"
            change<-.004 && obv>0 -> "واگرایی مثبت OBV"
            else -> "واگرایی OBV مشاهده نشد"
        }
    }
    private fun rsi(c: List<Double>, p: Int): Double {
        var gain=0.0; var loss=0.0
        for(i in max(1,c.size-p) until c.size) {
            val d=c[i]-c[i-1]; if(d>0) gain+=d else loss-=d
        }
        return if(loss<=1e-12) 100.0 else 100-100/(1+gain/loss)
    }
    private fun fmt(x: Double)=String.format(java.util.Locale.US,"%.6f",x)
    private fun valid(c: DoubleArray)=c.size>=6 && c.take(6).all { it.isFinite() } &&
        c[0]>0 && c[1]>=max(c[0],c[3]) && c[2]<=min(c[0],c[3]) && c[2]>0 && c[3]>0 && c[4]>=0 && c[5]>0
    private fun empty(reason: String)=Report(false,reason,null,null,null,null,null,null,
        "داده کافی نیست","داده کافی نیست","داده کافی نیست",emptyList(),emptyList(),
        "نامشخص","نامشخص",listOf(reason))
}
