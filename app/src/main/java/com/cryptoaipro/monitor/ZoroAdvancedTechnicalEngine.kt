package com.cryptoaipro.monitor

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Explainable OHLCV technical-analysis stack. Scores are evidence indices, never win probabilities.
 * Input rows are [open, high, low, close, volume, timestamp].
 */
object ZoroAdvancedTechnicalEngine {
    data class Metric(val name: String, val value: String, val interpretation: String)
    data class Level(val name: String, val price: Double)
    data class Report(
        val available: Boolean,
        val bias: String,
        val evidenceScore: Int,
        val trend: String,
        val regime: String,
        val structure: String,
        val momentum: String,
        val volumeState: String,
        val smartMoney: String,
        val candlePatterns: List<String>,
        val chartPatterns: List<String>,
        val indicators: List<Metric>,
        val levels: List<Level>,
        val higherTimeframe: String,
        val warnings: List<String>,
        val education: List<String>
    ) {
        companion object {
            fun unavailable(reason: String) = Report(
                false, "نامشخص", 0, "نامشخص", "نامشخص", "نامشخص", "نامشخص",
                "نامشخص", "نامشخص", emptyList(), emptyList(), emptyList(), emptyList(),
                "دادهٔ تایم‌فریم بالاتر موجود نیست", listOf(reason), emptyList()
            )
        }
    }

    private data class Pivot(val index: Int, val price: Double, val high: Boolean)

    fun analyze(primary: List<DoubleArray>, referencePrice: Double, higher: List<DoubleArray> = emptyList()): Report {
        val bars = primary.filter { valid(it) }.sortedBy { it[5] }.distinctBy { it[5].toLong() }
        if (bars.size < 80) return Report.unavailable("برای تحلیل جامع حداقل ۸۰ کندل معتبر لازم است")
        val o = bars.map { it[0] }; val h = bars.map { it[1] }; val l = bars.map { it[2] }
        val c = bars.map { it[3] }; val v = bars.map { it[4] }
        val last = c.last()
        if (!last.isFinite() || last <= 0.0 || !referencePrice.isFinite() || referencePrice <= 0.0)
            return Report.unavailable("قیمت مرجع یا کندل پایانی معتبر نیست")

        val ema9 = ema(c, 9); val ema20 = ema(c, 20); val ema50 = ema(c, 50)
        val ema100 = ema(c, 100); val ema200 = ema(c, 200)
        val sma20 = avg(c.takeLast(20)); val sma50 = avg(c.takeLast(50)); val sma200 = avg(c.takeLast(200))
        val rsi = rsi(c, 14); val atr = atr(h, l, c, 14)
        val atrPct = atr / last * 100.0
        val bandMid = sma20
        val deviation = sqrt(c.takeLast(20).map { (it-bandMid)*(it-bandMid) }.average().coerceAtLeast(0.0))
        val bbUpper = bandMid + 2.0 * deviation
        val bbLower = bandMid - 2.0 * deviation
        val bbPosition = if (bbUpper > bbLower) ((last-bbLower)/(bbUpper-bbLower)).coerceIn(0.0,1.0) else 0.5
        val bbWidth = if (bandMid > 0.0) (bbUpper-bbLower)/bandMid*100.0 else 0.0
        val macdFast = emaSeries(c, 12); val macdSlow = emaSeries(c, 26)
        val macdLine = macdFast.last() - macdSlow.last()
        val macdSeries = macdFast.indices.map { macdFast[it]-macdSlow[it] }
        val macdSignal = ema(macdSeries, 9)
        val macdHist = macdLine-macdSignal
        val stoch = stochRsi(c, 14)
        val adx = adx(h,l,c,14)
        val cci = cci(h,l,c,20)
        val williams = williamsR(h,l,c,14)
        val roc = if (c.size > 12 && c[c.size-13] > 0.0) (last/c[c.size-13]-1.0)*100.0 else 0.0
        val mfi = mfi(h,l,c,v,14)
        val vwap = vwap(h,l,c,v,48)
        val relativeVolume = v.last() / avg(v.takeLast(20)).coerceAtLeast(1e-12)
        val obv = obv(c,v)
        val obvTrend = slope(obv.takeLast(20))
        val ichiTenkan = midpoint(h.takeLast(9), l.takeLast(9))
        val ichiKijun = midpoint(h.takeLast(26), l.takeLast(26))
        val ichiSpanA = (ichiTenkan+ichiKijun)/2.0
        val ichiSpanB = midpoint(h.takeLast(52), l.takeLast(52))
        val supertrend = supertrendDirection(h,l,c,10,3.0)
        val psar = parabolicSar(h,l,c)
        val cmf = chaikinMoneyFlow(h,l,c,v,20)
        val aroon = aroonOscillator(h,l,25)
        val donchian = channelPosition(h,l,c,20)
        val keltner = keltnerPosition(h,l,c,20,2.0)

        val pivots = pivots(h,l,2,2).takeLast(30)
        val highs = pivots.filter { it.high }
        val lows = pivots.filter { !it.high }
        val structure = structureText(highs,lows,last)
        val candlePatterns = candlePatterns(o,h,l,c)
        val chartPatterns = chartPatterns(highs,lows,last)
        val fvg = fairValueGap(bars)
        val sweep = liquiditySweep(bars,highs,lows)
        val bos = breakOfStructure(last,highs,lows)
        val block = orderBlock(bars,atr)
        val smartMoney = listOf(bos,sweep,fvg,block).filter { it.isNotBlank() && it != "مشاهده نشد" }
            .joinToString(" • ").ifBlank { "شواهد واضح نقدینگی/ساختار هوشمند در آخرین کندل‌ها دیده نشد" }

        val recentHigh = h.takeLast(120).maxOrNull() ?: last
        val recentLow = l.takeLast(120).minOrNull() ?: last
        val range = (recentHigh-recentLow).coerceAtLeast(0.0)
        val fib = if (range > 0.0) listOf(
            Level("فیبوناچی ۳۸٫۲٪", recentHigh-range*0.382),
            Level("فیبوناچی ۵۰٪", recentHigh-range*0.5),
            Level("فیبوناچی ۶۱٫۸٪", recentHigh-range*0.618)
        ) else emptyList()
        val support = lows.takeLast(3).map { it.price }.filter { it < last }.maxOrNull()
        val resistance = highs.takeLast(3).map { it.price }.filter { it > last }.minOrNull()
        val levels = buildList {
            if (support != null) add(Level("حمایت ساختاری",support))
            if (resistance != null) add(Level("مقاومت ساختاری",resistance))
            addAll(fib)
            add(Level("VWAP",vwap))
            val previousHigh=h.getOrElse(h.lastIndex-1){h.last()}
            val previousLow=l.getOrElse(l.lastIndex-1){l.last()}
            val previousClose=c.getOrElse(c.lastIndex-1){c.last()}
            val pivot=(previousHigh+previousLow+previousClose)/3.0
            add(Level("Pivot کلاسیک",pivot))
            add(Level("Pivot R1",2.0*pivot-previousLow))
            add(Level("Pivot S1",2.0*pivot-previousHigh))
            add(Level("ابر ایچیموکو A",ichiSpanA))
            add(Level("ابر ایچیموکو B",ichiSpanB))
        }.filter { it.price.isFinite() && it.price > 0.0 }.distinctBy { (it.price*1e8).toLong() }

        val trendVotes = listOf(ema9-ema20,ema20-ema50,ema50-ema100,ema100-ema200,last-sma50,last-sma200)
            .map { sign(it) }
        val trendScore = trendVotes.average()
        val momentumScore = listOf(
            signed(rsi-50.0,25.0), signed(macdHist,last*0.001), signed(cci,180.0),
            signed(williams+50.0,50.0), signed(roc,4.0), signed(stoch-50.0,50.0)
        ).average()
        val volumeScore = listOf(
            signed(last-vwap,atr.coerceAtLeast(last*0.001)),
            signed(mfi-50.0,25.0),
            sign(obvTrend),
            signed(relativeVolume-1.0,1.5) * sign(last-c.getOrElse(c.size-2){last})
        ).average()
        val structureScore = when {
            bos.contains("صعودی") -> 0.8
            bos.contains("نزولی") -> -0.8
            structure.contains("صعودی") -> 0.55
            structure.contains("نزولی") -> -0.55
            else -> 0.0
        }
        val regime = when {
            atrPct >= 4.0 -> "نوسان بسیار بالا"
            bbWidth <= 1.2 -> "فشردگی/احتمال گسترش نوسان"
            adx >= 25.0 -> "رونددار"
            adx <= 16.0 -> "رنج/کم‌روند"
            else -> "گذار/نوسان متعادل"
        }
        val volatilityScore = when {
            atrPct >= 5.0 -> -0.35
            atrPct in 0.2..2.5 -> 0.15
            else -> 0.0
        }
        val combined = (0.32*trendScore + 0.24*momentumScore + 0.18*volumeScore +
            0.20*structureScore + 0.06*volatilityScore).coerceIn(-1.0,1.0)
        val evidence = (combined*100.0).toInt().coerceIn(-100,100)
        val bias = when {
            evidence >= 22 -> "تمایل صعودی"
            evidence <= -22 -> "تمایل نزولی"
            else -> "خنثی/متناقض"
        }
        val trend = when {
            trendScore >= 0.55 -> "صعودی چندمیانگینی"
            trendScore <= -0.55 -> "نزولی چندمیانگینی"
            else -> "ترکیبی/رنج"
        }
        val momentum = when {
            momentumScore >= 0.25 -> "مومنتوم مثبت"
            momentumScore <= -0.25 -> "مومنتوم منفی"
            else -> "مومنتوم خنثی"
        }
        val volumeState = when {
            relativeVolume >= 1.8 && volumeScore > 0.15 -> "حجم بالا و همسو با خریداران"
            relativeVolume >= 1.8 && volumeScore < -0.15 -> "حجم بالا و همسو با فروشندگان"
            relativeVolume < 0.65 -> "حجم پایین؛ شکست‌ها نیازمند تأیید"
            else -> "حجم متعادل"
        }
        val higherReport = if (higher.size >= 80) analyze(higher, referencePrice, emptyList()) else null
        val higherText = higherReport?.let { "۱ساعته: " + it.bias + " (" + it.evidenceScore + "/100)" }
            ?: "دادهٔ تایم‌فریم بالاتر کافی نیست"
        val warnings = mutableListOf<String>()
        if (bars.size < 200) warnings += "برای SMA/EMA دویست‌دوره‌ای تاریخچه کامل نیست"
        if (atrPct >= 5.0) warnings += "نوسان بسیار بالا؛ حد ضرر باید با ATR و اندازه پوزیشن تنظیم شود"
        if (relativeVolume < 0.65) warnings += "حجم نسبی ضعیف است"
        if (adx < 16.0) warnings += "ADX پایین است؛ سیگنال‌های روندی در بازار رنج خطاپذیرترند"
        if (abs(evidence) < 22) warnings += "شواهد چندگانه هم‌جهت نیستند؛ از اجبار به LONG/SHORT پرهیز شود"
        if (higherReport != null && higherReport.evidenceScore*evidence < 0) warnings += "تعارض جهت تایم‌فریم فعلی و ۱ساعته"
        if (candlePatterns.isEmpty()) warnings += "الگوی کندلی پرقدرت در آخرین کندل تأیید نشد"

        val metrics = listOf(
            Metric("RSI 14",fmt(rsi),when { rsi>=70 -> "اشباع خرید"; rsi<=30 -> "اشباع فروش"; else -> "ناحیه میانی" }),
            Metric("Stoch RSI",fmt(stoch),when { stoch>=80 -> "اشباع خرید"; stoch<=20 -> "اشباع فروش"; else -> "میانی" }),
            Metric("MACD 12/26/9",fmt(macdHist),if(macdHist>=0) "هیستوگرام مثبت" else "هیستوگرام منفی"),
            Metric("ADX 14",fmt(adx),if(adx>=25) "روند قوی‌تر" else "قدرت روند پایین/متوسط"),
            Metric("ATR 14",fmt(atrPct)+"٪","نوسان نسبت به قیمت"),
            Metric("Bollinger 20,2",fmt(bbPosition*100)+"٪","موقعیت قیمت در باند؛ عرض "+fmt(bbWidth)+"٪"),
            Metric("EMA 9/20",fmt(ema9/ema20*100-100)+"٪","فاصله نسبی دو میانگین"),
            Metric("SMA 20/50",fmt(sma20/sma50*100-100)+"٪","جهت میانگین کوتاه/میان‌مدت"),
            Metric("VWAP",fmt(last/vwap*100-100)+"٪","فاصله قیمت از VWAP"),
            Metric("CCI 20",fmt(cci),if(cci>100) "مومنتوم مثبت قوی" else if(cci< -100) "مومنتوم منفی قوی" else "محدوده معمول"),
            Metric("Williams %R",fmt(williams),if(williams> -20) "اشباع خرید" else if(williams< -80) "اشباع فروش" else "میانی"),
            Metric("ROC 12",fmt(roc)+"٪",if(roc>=0) "بازده مومنتوم مثبت" else "بازده مومنتوم منفی"),
            Metric("MFI 14",fmt(mfi),if(mfi>=80) "ورود پول/اشباع خرید" else if(mfi<=20) "خروج پول/اشباع فروش" else "جریان پول میانی"),
            Metric("Relative Volume",fmt(relativeVolume)+"x",volumeState),
            Metric("OBV trend",fmt(obvTrend),"شیب حجم تجمعی"),
            Metric("Ichimoku",if(last>=ichiSpanA && last>=ichiSpanB) "بالای ابر" else if(last<ichiSpanA && last<ichiSpanB) "زیر ابر" else "داخل ابر","تنکان "+fmt(ichiTenkan)+" • کیجون "+fmt(ichiKijun)),
            Metric("Supertrend 10,3",supertrend,"جهت روند با باندهای ATR و بازگشت روند"),
            Metric("Parabolic SAR",fmt(psar),if(last>psar) "قیمت بالای SAR" else "قیمت زیر SAR"),
            Metric("Chaikin Money Flow",fmt(cmf),if(cmf>0.05) "انباشت پول" else if(cmf< -0.05) "توزیع پول" else "جریان پول خنثی"),
            Metric("Aroon Oscillator",fmt(aroon),if(aroon>50) "روند صعودی تازه‌تر" else if(aroon< -50) "روند نزولی تازه‌تر" else "قدرت روند متعادل"),
            Metric("Donchian 20",fmt(donchian)+"٪","موقعیت قیمت در کانال ۲۰ دوره"),
            Metric("Keltner 20,2",fmt(keltner)+"٪","موقعیت قیمت در کانال EMA/ATR"),
            Metric("Fibonacci",fib.joinToString(" / ") { fmt(it.price) },"سطوح بازگشتی از دامنه ۱۲۰ کندل")
        )
        return Report(
            true,bias,evidence,trend,regime,structure,momentum,volumeState,smartMoney,
            candlePatterns,chartPatterns,metrics,levels,higherText,warnings.distinct(),
            listOf(
                "RSI و Stoch RSI مومنتوم را می‌سنجند؛ اشباع به‌تنهایی سیگنال برگشت نیست.",
                "MACD و میانگین‌ها جهت و شتاب روند را نشان می‌دهند؛ در رنج سیگنال کاذب زیاد می‌شود.",
                "ADX قدرت روند است، نه جهت آن؛ VWAP و MFI برای زمینه حجم/قیمت به‌کار می‌روند.",
                "BOS/CHOCH، FVG و Liquidity Sweep مفاهیم ساختار و نقدینگی‌اند و باید با بسته‌شدن کندل تأیید شوند.",
                "الگوهای کلاسیک و کندلی احتمالی‌اند؛ شکست خط گردن/سطح و حجم تأیید لازم دارند."
            )
        )
    }

    private fun supertrendDirection(h: List<Double>,l: List<Double>,c: List<Double>,period: Int,multiplier: Double): String {
        if(c.size<period+3) return "نامشخص"
        val ranges=(1 until c.size).map { i -> max(h[i]-l[i],max(abs(h[i]-c[i-1]),abs(l[i]-c[i-1]))) }
        var atrValue=avg(ranges.take(period))
        var upper=(h[period]+l[period])/2.0+multiplier*atrValue
        var lower=(h[period]+l[period])/2.0-multiplier*atrValue
        var directionUp=c[period]>=((h[period]+l[period])/2.0)
        for(i in period+1 until c.size) {
            atrValue=avg(ranges.subList(max(0,i-period),i))
            val mid=(h[i]+l[i])/2.0
            val basicUpper=mid+multiplier*atrValue
            val basicLower=mid-multiplier*atrValue
            upper=if(basicUpper<upper || c[i-1]>upper) basicUpper else upper
            lower=if(basicLower>lower || c[i-1]<lower) basicLower else lower
            directionUp=if(directionUp) c[i]>=lower else c[i]>upper
        }
        return if(directionUp) "صعودی" else "نزولی"
    }
    private fun parabolicSar(h: List<Double>,l: List<Double>,c: List<Double>): Double {
        if(c.size<3) return c.lastOrNull() ?: 0.0
        var up=c[1]>=c[0]
        var sar=if(up) min(l[0],l[1]) else max(h[0],h[1])
        var extreme=if(up) max(h[0],h[1]) else min(l[0],l[1])
        var acceleration=0.02
        for(i in 2 until c.size) {
            sar += acceleration*(extreme-sar)
            if(up) {
                sar=min(sar,min(l[i-1],l[i-2]))
                if(l[i]<sar) { up=false; sar=extreme; extreme=l[i]; acceleration=0.02 }
                else if(h[i]>extreme) { extreme=h[i]; acceleration=min(0.20,acceleration+0.02) }
            } else {
                sar=max(sar,max(h[i-1],h[i-2]))
                if(h[i]>sar) { up=true; sar=extreme; extreme=h[i]; acceleration=0.02 }
                else if(l[i]<extreme) { extreme=l[i]; acceleration=min(0.20,acceleration+0.02) }
            }
        }
        return sar
    }
    private fun chaikinMoneyFlow(h: List<Double>,l: List<Double>,c: List<Double>,v: List<Double>,period: Int): Double {
        val start=max(0,c.size-period); var flow=0.0; var volume=0.0
        for(i in start until c.size) {
            val range=(h[i]-l[i]).coerceAtLeast(1e-12)
            val multiplier=((c[i]-l[i])-(h[i]-c[i]))/range
            flow+=multiplier*v[i]; volume+=v[i]
        }
        return if(volume<=1e-12) 0.0 else flow/volume
    }
    private fun aroonOscillator(h: List<Double>,l: List<Double>,period: Int): Double {
        val hs=h.takeLast(period); val ls=l.takeLast(period)
        val highIndex=hs.indices.maxByOrNull { hs[it] } ?: return 0.0
        val lowIndex=ls.indices.minByOrNull { ls[it] } ?: return 0.0
        val up=(highIndex+1).toDouble()/period*100.0
        val down=(lowIndex+1).toDouble()/period*100.0
        return up-down
    }
    private fun channelPosition(h: List<Double>,l: List<Double>,c: List<Double>,period: Int): Double {
        val hi=h.takeLast(period).maxOrNull() ?: return 50.0
        val lo=l.takeLast(period).minOrNull() ?: return 50.0
        return if(hi-lo<=1e-12) 50.0 else ((c.last()-lo)/(hi-lo)*100.0).coerceIn(0.0,100.0)
    }
    private fun keltnerPosition(h: List<Double>,l: List<Double>,c: List<Double>,period: Int,multiplier: Double): Double {
        val middle=ema(c,period); val width=atr(h,l,c,period)*multiplier
        return if(width<=1e-12) 50.0 else ((c.last()-(middle-width))/(2.0*width)*100.0).coerceIn(0.0,100.0)
    }

    private fun valid(x: DoubleArray): Boolean = x.size>=6 && x.take(6).all { it.isFinite() } &&
        x[0]>0 && x[1]>=max(x[0],x[3]) && x[2]<=min(x[0],x[3]) && x[2]>0 && x[4]>=0 && x[5]>0

    private fun avg(x: List<Double>): Double = if(x.isEmpty()) 0.0 else x.average()
    private fun midpoint(high: List<Double>, low: List<Double>): Double =
        if(high.isEmpty() || low.isEmpty()) 0.0 else (high.maxOrNull()!!+low.minOrNull()!!)/2.0

    private fun emaSeries(x: List<Double>, period: Int): List<Double> {
        if(x.isEmpty()) return emptyList()
        val alpha=2.0/(period+1.0); var value=x.first()
        return x.mapIndexed { i,v -> if(i==0) value else { value=alpha*v+(1-alpha)*value; value } }
    }
    private fun ema(x: List<Double>, period: Int): Double = emaSeries(x,period).lastOrNull() ?: 0.0
    private fun rsi(x: List<Double>, period: Int): Double {
        if(x.size<=period) return 50.0
        val changes=x.zipWithNext { a,b -> b-a }.takeLast(period*3)
        var gain=changes.filter { it>0 }.sum()/period
        var loss=changes.filter { it<0 }.sum().let { -it/period }
        for(change in changes.drop(period)) {
            gain=(gain*(period-1)+max(change,0.0))/period
            loss=(loss*(period-1)+max(-change,0.0))/period
        }
        return if(loss<=1e-12) 100.0 else 100.0-100.0/(1.0+gain/loss)
    }
    private fun stochRsi(x: List<Double>, period: Int): Double {
        if(x.size<period*2) return 50.0
        val series=(period until x.size).map { rsi(x.take(it+1),period) }.takeLast(period)
        val lo=series.minOrNull() ?: 0.0; val hi=series.maxOrNull() ?: 100.0
        return if(hi-lo<1e-9) 50.0 else ((series.last()-lo)/(hi-lo)*100.0).coerceIn(0.0,100.0)
    }
    private fun atr(h: List<Double>, l: List<Double>, c: List<Double>, period: Int): Double {
        val tr=(1 until c.size).map { i -> max(h[i]-l[i],max(abs(h[i]-c[i-1]),abs(l[i]-c[i-1]))) }
        return avg(tr.takeLast(period))
    }
    private fun adx(h: List<Double>, l: List<Double>, c: List<Double>, period: Int): Double {
        if(c.size<period+2) return 0.0
        val tr=mutableListOf<Double>(); val plus=mutableListOf<Double>(); val minus=mutableListOf<Double>()
        for(i in 1 until c.size) {
            val up=h[i]-h[i-1]; val down=l[i-1]-l[i]
            tr += max(h[i]-l[i],max(abs(h[i]-c[i-1]),abs(l[i]-c[i-1])))
            plus += if(up>down && up>0) up else 0.0
            minus += if(down>up && down>0) down else 0.0
        }
        val dx=tr.indices.map { i ->
            val start=max(0,i-period+1)
            val trSum=tr.subList(start,i+1).sum().coerceAtLeast(1e-12)
            val p=plus.subList(start,i+1).sum()/trSum*100.0
            val m=minus.subList(start,i+1).sum()/trSum*100.0
            if(p+m<=1e-12) 0.0 else abs(p-m)/(p+m)*100.0
        }
        return avg(dx.takeLast(period)).coerceIn(0.0,100.0)
    }
    private fun cci(h: List<Double>,l: List<Double>,c: List<Double>,period: Int): Double {
        val tp=(max(0,h.size-period) until h.size).map { (h[it]+l[it]+c[it])/3.0 }
        if(tp.isEmpty()) return 0.0
        val mean=avg(tp); val deviation=avg(tp.map { abs(it-mean) })
        return if(deviation<=1e-12) 0.0 else (tp.last()-mean)/(0.015*deviation)
    }
    private fun williamsR(h: List<Double>,l: List<Double>,c: List<Double>,period: Int): Double {
        val hi=h.takeLast(period).maxOrNull() ?: return -50.0
        val lo=l.takeLast(period).minOrNull() ?: return -50.0
        return if(hi-lo<=1e-12) -50.0 else (hi-c.last())/(hi-lo)*-100.0
    }
    private fun mfi(h: List<Double>,l: List<Double>,c: List<Double>,v: List<Double>,period: Int): Double {
        if(c.size<period+1) return 50.0
        var positive=0.0; var negative=0.0
        for(i in c.size-period until c.size) {
            val flow=((h[i]+l[i]+c[i])/3.0)*v[i]
            val prev=(h[i-1]+l[i-1]+c[i-1])/3.0
            if((h[i]+l[i]+c[i])/3.0>prev) positive+=flow else negative+=flow
        }
        return if(negative<=1e-12) 100.0 else 100.0-100.0/(1.0+positive/negative)
    }
    private fun vwap(h: List<Double>,l: List<Double>,c: List<Double>,v: List<Double>,period: Int): Double {
        val start=max(0,c.size-period); var pv=0.0; var vol=0.0
        for(i in start until c.size) { val typical=(h[i]+l[i]+c[i])/3.0; pv+=typical*v[i]; vol+=v[i] }
        return if(vol>0) pv/vol else c.last()
    }
    private fun obv(c: List<Double>,v: List<Double>): List<Double> {
        var value=0.0; val out=mutableListOf(0.0)
        for(i in 1 until c.size) { value += when { c[i]>c[i-1] -> v[i]; c[i]<c[i-1] -> -v[i]; else -> 0.0 }; out += value }
        return out
    }
    private fun slope(x: List<Double>): Double {
        if(x.size<3) return 0.0
        val meanX=(x.size-1)/2.0; val meanY=x.average()
        var cov=0.0; var variance=0.0
        x.forEachIndexed { i,y -> cov+=(i-meanX)*(y-meanY); variance+=(i-meanX)*(i-meanX) }
        return if(variance<=0) 0.0 else cov/variance
    }
    private fun sign(x: Double): Double = when { !x.isFinite() || abs(x)<1e-12 -> 0.0; x>0 -> 1.0; else -> -1.0 }
    private fun signed(x: Double, scale: Double): Double = if(scale<=0) 0.0 else (x/scale).coerceIn(-1.0,1.0)
    private fun fmt(x: Double): String = String.format(Locale.US,"%.2f",if(x.isFinite()) x else 0.0)

    private fun pivots(h: List<Double>,l: List<Double>,left: Int,right: Int): List<Pivot> {
        val out=mutableListOf<Pivot>()
        for(i in left until h.size-right) {
            val isHigh=(i-left until i+right+1).all { j -> j==i || h[i]>=h[j] }
            val isLow=(i-left until i+right+1).all { j -> j==i || l[i]<=l[j] }
            if(isHigh) out+=Pivot(i,h[i],true)
            if(isLow) out+=Pivot(i,l[i],false)
        }
        return out.sortedBy { it.index }
    }
    private fun structureText(highs: List<Pivot>,lows: List<Pivot>,last: Double): String {
        if(highs.size<2 || lows.size<2) return "ساختار در حال شکل‌گیری"
        val hh=highs.takeLast(2); val ll=lows.takeLast(2)
        return when {
            hh[1].price>hh[0].price && ll[1].price>ll[0].price -> "سقف‌ها و کف‌های بالاتر؛ ساختار صعودی"
            hh[1].price<hh[0].price && ll[1].price<ll[0].price -> "سقف‌ها و کف‌های پایین‌تر؛ ساختار نزولی"
            last>hh.last().price -> "شکست سقف ساختاری؛ احتمال ادامه صعود"
            last<ll.last().price -> "شکست کف ساختاری؛ احتمال ادامه نزول"
            else -> "ساختار خنثی/فشرده"
        }
    }
    private fun candlePatterns(o: List<Double>,h: List<Double>,l: List<Double>,c: List<Double>): List<String> {
        val out=mutableListOf<String>(); val i=c.lastIndex
        val body=abs(c[i]-o[i]); val range=(h[i]-l[i]).coerceAtLeast(1e-12)
        val upper=h[i]-max(o[i],c[i]); val lower=min(o[i],c[i])-l[i]
        val bullish=c[i]>o[i]; val bearish=c[i]<o[i]
        if(body/range<=0.1) out+="دوجی • بلاتکلیفی"
        if(lower>=body*2.0 && upper<=body*0.8 && c[i]>(h[i]+l[i])/2.0) out+="چکش احتمالی"
        if(upper>=body*2.0 && lower<=body*0.8 && c[i]<(h[i]+l[i])/2.0) out+="ستاره ثاقب احتمالی"
        if(i>=1) {
            val prevBody=abs(c[i-1]-o[i-1])
            if(bullish && c[i-1]<o[i-1] && o[i]<=c[i-1] && c[i]>=o[i-1] && body>prevBody) out+="پوشای صعودی"
            if(bearish && c[i-1]>o[i-1] && o[i]>=c[i-1] && c[i]<=o[i-1] && body>prevBody) out+="پوشای نزولی"
            if(c[i-1]<o[i-1] && bullish && o[i]<l[i-1]+(h[i-1]-l[i-1])*0.35 && c[i]>(o[i-1]+c[i-1])/2.0) out+="خط نفوذی صعودی"
            if(c[i-1]>o[i-1] && bearish && o[i]>h[i-1]-(h[i-1]-l[i-1])*0.35 && c[i]<(o[i-1]+c[i-1])/2.0) out+="پوشش ابر تیره"
            if(h[i]<h[i-1] && l[i]>l[i-1]) out+="Inside Bar • فشردگی"
            if(h[i]>h[i-1] && l[i]<l[i-1]) out+="Outside Bar • گسترش دامنه"
            if(body<=prevBody*0.55 && (min(o[i],c[i])>=min(o[i-1],c[i-1])) &&
                (max(o[i],c[i])<=max(o[i-1],c[i-1]))) out+="هارامی/کاهش بدنه"
        }
        if(i>=2) {
            val firstBear=c[i-2]<o[i-2]; val middle=abs(c[i-1]-o[i-1])<abs(c[i-2]-o[i-2])*0.45
            if(firstBear && middle && bullish && c[i]>(o[i-2]+c[i-2])/2.0) out+="ستاره صبحگاهی احتمالی"
            val firstBull=c[i-2]>o[i-2]
            if(firstBull && middle && bearish && c[i]<(o[i-2]+c[i-2])/2.0) out+="ستاره شامگاهی احتمالی"
        }
        if(body/range>0.88) out+=if(bullish) "ماروبوزوی صعودی" else if(bearish) "ماروبوزوی نزولی" else ""
        return out.filter { it.isNotBlank() }.distinct()
    }
    private fun chartPatterns(highs: List<Pivot>,lows: List<Pivot>,last: Double): List<String> {
        val out=mutableListOf<String>(); val hs=highs.takeLast(5); val ls=lows.takeLast(5)
        if(hs.size>=2) {
            val a=hs[hs.size-2].price; val b=hs.last().price
            if(abs(a-b)/max(a,b)<0.012) out+="قله دوقلو احتمالی؛ شکست خط گردن لازم است"
        }
        if(ls.size>=2) {
            val a=ls[ls.size-2].price; val b=ls.last().price
            if(abs(a-b)/max(a,b)<0.012) out+="کف دوقلو احتمالی؛ شکست خط گردن لازم است"
        }
        if(hs.size>=3) {
            val x=hs.takeLast(3)
            if(x[1].price>x[0].price*1.004 && x[1].price>x[2].price*1.004 &&
                abs(x[0].price-x[2].price)/x[1].price<0.025) out+="سر و شانه احتمالی"
            if(x[1].price<x[0].price*0.996 && x[1].price<x[2].price*0.996 &&
                abs(x[0].price-x[2].price)/x[1].price<0.025) out+="سر و شانه معکوس احتمالی"
        }
        if(hs.size>=3 && ls.size>=3) {
            val highSlope=slope(hs.takeLast(3).map { it.price })
            val lowSlope=slope(ls.takeLast(3).map { it.price })
            val scale=last.coerceAtLeast(1e-12)
            if(abs(highSlope)/scale<0.001 && lowSlope/scale>0.0003) out+="مثلث صعودی احتمالی"
            if(abs(lowSlope)/scale<0.001 && highSlope/scale< -0.0003) out+="مثلث نزولی احتمالی"
            if(highSlope*lowSlope>0 && abs(highSlope-lowSlope)/scale>0.0002) out+="کانال/گوه روندی احتمالی"
        }
        return out.ifEmpty { listOf("الگوی کلاسیک قطعی تشخیص داده نشد؛ منتظر شکست معتبر") }
    }
    private fun fairValueGap(bars: List<DoubleArray>): String {
        for(i in bars.size-1 downTo max(2,bars.size-18)) {
            val a=bars[i-2]; val b=bars[i]
            if(a[1]<b[2]) return "FVG صعودی احتمالی بین " + fmt(a[1]) + " و " + fmt(b[2])
            if(a[2]>b[1]) return "FVG نزولی احتمالی بین " + fmt(b[1]) + " و " + fmt(a[2])
        }
        return "مشاهده نشد"
    }
    private fun liquiditySweep(bars: List<DoubleArray>,highs: List<Pivot>,lows: List<Pivot>): String {
        val last=bars.last()
        val oldHigh=highs.dropLast(1).lastOrNull()?.price
        val oldLow=lows.dropLast(1).lastOrNull()?.price
        if(oldHigh!=null && last[1]>oldHigh && last[3]<oldHigh) return "جمع‌آوری نقدینگی بالای سقف و بسته‌شدن زیر آن"
        if(oldLow!=null && last[2]<oldLow && last[3]>oldLow) return "جمع‌آوری نقدینگی زیر کف و بسته‌شدن بالای آن"
        return "مشاهده نشد"
    }
    private fun breakOfStructure(last: Double,highs: List<Pivot>,lows: List<Pivot>): String {
        val hi=highs.lastOrNull()?.price; val lo=lows.lastOrNull()?.price
        return when { hi!=null && last>hi -> "BOS صعودی: بسته‌شدن بالای سقف ساختاری"
            lo!=null && last<lo -> "BOS نزولی: بسته‌شدن زیر کف ساختاری"
            else -> "شکست ساختاری تازه تأیید نشده" }
    }
    private fun orderBlock(bars: List<DoubleArray>,atr: Double): String {
        val last=bars.last(); val threshold=max(atr*1.5,last[3]*0.002)
        for(i in bars.size-2 downTo max(0,bars.size-9)) {
            val x=bars[i]; val impulse=abs(last[3]-x[3])
            if(impulse>=threshold && ((x[3]<x[0] && last[3]>x[0]) || (x[3]>x[0] && last[3]<x[0])))
                return if(x[3]<x[0]) "بلوک سفارش صعودی احتمالی حوالی " + fmt(x[0]) else "بلوک سفارش نزولی احتمالی حوالی " + fmt(x[0])
        }
        return "مشاهده نشد"
    }
}
