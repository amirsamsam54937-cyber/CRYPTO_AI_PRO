package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OHLCV-native discretionary-style chart reader.
 * Scores are evidence indices, not probabilities. Volume delta is a candle-location proxy,
 * not exchange aggressor data; liquidity pools are inferred, never observed hidden orders.
 */
object ZoroHumanMarketReader {
    data class Readout(
        val available: Boolean, val score: Int, val phase: String, val structure: String,
        val auction: String, val flow: String, val poc: Double?, val valueLow: Double?,
        val valueHigh: Double?, val patterns: List<String>, val bullishScenario: String,
        val bearishScenario: String, val evidence: List<String>, val warnings: List<String>
    ) {
        fun blocks(side: String): Boolean = available && when (side.uppercase()) {
            "LONG_BIAS" -> score <= -38
            "SHORT_BIAS" -> score >= 38
            else -> false
        }
    }
    private data class Pivot(val index: Int, val price: Double, val high: Boolean)
    private data class Profile(val poc: Double, val low: Double, val high: Double)

    fun analyze(input: List<DoubleArray>): Readout {
        val bars = input.filter(::valid).sortedBy { it[5] }.distinctBy { it[5].toLong() }.takeLast(240)
        if (bars.size < 80) return unavailable("حداقل ۸۰ کندل معتبر OHLCV لازم است")
        val o=bars.map{it[0]}; val h=bars.map{it[1]}; val l=bars.map{it[2]}
        val c=bars.map{it[3]}; val v=bars.map{it[4]}
        val last=bars.last(); val price=last[3]
        val tr=(1 until bars.size).map { i -> max(h[i]-l[i],max(abs(h[i]-c[i-1]),abs(l[i]-c[i-1]))) }
        val atr=avg(tr.takeLast(14)).coerceAtLeast(price*0.0005)
        val ps=pivots(h,l,3,3); val highs=ps.filter{it.high}.takeLast(8); val lows=ps.filter{!it.high}.takeLast(8)
        val structure=structure(highs,lows,price)
        val profile=profile(bars,28)
        val deltaProxy=bars.takeLast(20).sumOf { b ->
            val r=(b[1]-b[2]).coerceAtLeast(1e-12)
            ((b[3]-b[2])-(b[1]-b[3]))/r*b[4]
        }
        val flowRatio=(deltaProxy/bars.takeLast(20).sumOf{it[4]}.coerceAtLeast(1e-12)).coerceIn(-1.0,1.0)
        val relativeVolume=v.last()/avg(v.takeLast(20)).coerceAtLeast(1e-12)
        val patterns=candlePatterns(o,h,l,c)
        val swingHigh=highs.lastOrNull()?.price; val swingLow=lows.lastOrNull()?.price
        val sweepHigh=swingHigh!=null && last[1]>swingHigh && price<swingHigh
        val sweepLow=swingLow!=null && last[2]<swingLow && price>swingLow
        val priorHigh=h.dropLast(1).takeLast(31).maxOrNull() ?: h.maxOrNull()!!
        val priorLow=l.dropLast(1).takeLast(31).minOrNull() ?: l.minOrNull()!!
        val failedHigh=last[1]>priorHigh && price<priorHigh
        val failedLow=last[2]<priorLow && price>priorLow
        val body=abs(last[3]-last[0]); val range=(last[1]-last[2]).coerceAtLeast(1e-12)
        val closeLocation=((price-last[2])/range).coerceIn(0.0,1.0)
        val effortNoResult=relativeVolume>=1.8 && body/range<=0.22
        val recentRanges=(1..12).map{h[h.lastIndex-it]-l[l.lastIndex-it]}
        val oldRanges=(13..36).map{h[h.lastIndex-it]-l[l.lastIndex-it]}
        val compression=avg(recentRanges)<avg(oldRanges)*0.68
        val expansion=range>atr*1.6 && relativeVolume>=1.35
        val slope=linearSlope(c.takeLast(30))/price

        var score=when {
            structure.contains("شکست صعودی") -> 35
            structure.contains("شکست نزولی") -> -35
            structure.contains("سقف و کف بالاتر") -> 28
            structure.contains("سقف و کف پایین‌تر") -> -28
            else -> 0
        }
        if(profile!=null) score += when {
            price>profile.high -> 8; price<profile.low -> -8
            price>profile.poc -> 4; price<profile.poc -> -4; else -> 0
        }
        score+=(flowRatio*18.0).toInt()
        if(sweepLow||failedLow) score+=18
        if(sweepHigh||failedHigh) score-=18
        if(slope>0.00025) score+=8
        if(slope< -0.00025) score-=8
        if(patterns.any{it.startsWith("بازگشت صعودی")}) score+=8
        if(patterns.any{it.startsWith("بازگشت نزولی")}) score-=8
        if(expansion&&closeLocation>=0.78) score+=7
        if(expansion&&closeLocation<=0.22) score-=7
        if(effortNoResult) score=(score*0.75).toInt()
        score=score.coerceIn(-100,100)

        val phase=when {
            sweepLow&&relativeVolume>=1.3 -> "Spring احتمالی • جاروب کف و بازپس‌گیری"
            sweepHigh&&relativeVolume>=1.3 -> "Upthrust احتمالی • شکست ناموفق سقف"
            slope>0.0002&&profile!=null&&price<profile.poc -> "گذار احتمالی از Markup به توزیع مجدد"
            slope< -0.0002&&profile!=null&&price>profile.poc -> "گذار احتمالی از Markdown به انباشت"
            slope>0.0002 -> "Markup احتمالی • روند صعودی"
            slope< -0.0002 -> "Markdown احتمالی • روند نزولی"
            compression -> "تراکم دامنه • جهت شکست هنوز معلوم نیست"
            else -> "تعادل/رنج • نیازمند شکست و پذیرش قیمت"
        }
        val auction=when {
            sweepLow -> "حراج زیر کف ناموفق بود؛ قیمت سطح را بازپس گرفته است."
            sweepHigh -> "حراج بالای سقف ناموفق بود؛ قیمت زیر سطح برگشته است."
            profile!=null&&price>profile.high -> "بیرون Value Area بالا؛ پذیرش قیمت باید با ماندگاری تأیید شود."
            profile!=null&&price<profile.low -> "بیرون Value Area پایین؛ پذیرش قیمت باید با ماندگاری تأیید شود."
            profile!=null&&abs(price-profile.poc)<=atr*0.25 -> "نزدیک POC؛ ناحیه تعادل و احتمال چرخش دوطرفه."
            else -> "قیمت در/نزدیک محدوده ارزش؛ وسط رنج مزیت معاملاتی محدودتری دارد."
        }
        val flow=when {
            effortNoResult -> "حجم بالا با پیشروی اندک قیمت؛ جذب سفارش یا توقف حرکت محتمل، اما جهت هنوز تأیید نشده."
            flowRatio>=0.18 -> "فشار خرید تخمینی از محل بسته‌شدن کندل‌ها غالب است."
            flowRatio<=-0.18 -> "فشار فروش تخمینی از محل بسته‌شدن کندل‌ها غالب است."
            else -> "جریان حجمی خرید/فروش از OHLCV متعادل است."
        }
        val chartPatterns=chartPatterns(highs,lows)
        val allPatterns=(patterns+chartPatterns+listOfNotNull(
            if(sweepLow) "جاروب کف و بازپس‌گیری" else null,
            if(sweepHigh) "جاروب سقف و رد قیمت" else null,
            if(failedHigh) "شکست ناموفق سقف محدوده" else null,
            if(failedLow) "شکست ناموفق کف محدوده" else null,
            if(compression) "فشردگی دامنه" else null,
            if(expansion) "گسترش دامنه همراه حجم" else null
        )).distinct()
        val evidence=mutableListOf(
            "ساختار سوئینگ: "+structure,
            "جریان حجمی تخمینی ۲۰ کندل: "+fmt(flowRatio*100.0)+"٪ (جانشین دلتا، نه دلتا واقعی)",
            "حجم آخرین کندل: "+fmt(relativeVolume)+" برابر میانگین ۲۰ کندل",
            "رفتار حراج: "+auction,
            "فاز احتمالی: "+phase
        )
        if(profile!=null) evidence+="پروفایل حجم تقریبی: POC "+fmt(profile.poc)+" • ناحیه ارزش "+fmt(profile.low)+" تا "+fmt(profile.high)
        if(effortNoResult) evidence+="Effort vs Result: حجم زیاد اما بدنه کوچک؛ احتمال جذب/خستگی"
        val warnings=mutableListOf<String>()
        if(compression) warnings+="بازار فشرده است؛ شکست زودهنگام می‌تواند فیک‌اوت باشد."
        if(effortNoResult) warnings+="حجم بالا بدون جابه‌جایی قیمت؛ ورود تعقیبی پرریسک است."
        if(relativeVolume<0.65) warnings+="حجم پایین است؛ اعتبار شکست کاهش می‌یابد."
        if(abs(score)<25) warnings+="ساختار جهت قاطع ندارد؛ سناریوی پایه انتظار است."
        if(sweepHigh||sweepLow) warnings+="جاروب از سایه و بسته‌شدن کندل استنباط شده؛ لیکوییدیشن واقعی نیست."
        val bull=if(swingHigh!=null&&price<=swingHigh)
            "بسته‌شدن بالای سقف ساختاری "+fmt(swingHigh)+" و حفظ آن در پولبک، همراه حجم بالاتر از میانگین."
        else if(profile!=null) "بازپس‌گیری POC در "+fmt(profile.poc)+" و پذیرش قیمت بالای Value Area High در "+fmt(profile.high)+"."
        else "شکست و تثبیت بالای سقف ۳۲ کندل اخیر همراه حجم."
        val bear=if(swingLow!=null&&price>=swingLow)
            "بسته‌شدن زیر کف ساختاری "+fmt(swingLow)+" و پولبک ناموفق، همراه حجم تأییدکننده."
        else if(profile!=null) "از دست‌رفتن POC در "+fmt(profile.poc)+" و پذیرش قیمت زیر Value Area Low در "+fmt(profile.low)+"."
        else "شکست و تثبیت زیر کف ۳۲ کندل اخیر همراه حجم."
        return Readout(true,score,phase,structure,auction,flow,profile?.poc,profile?.low,profile?.high,
            allPatterns,bull,bear,evidence,warnings)
    }

    private fun unavailable(reason:String)=Readout(false,0,"نامشخص","داده کافی نیست","نامشخص","نامشخص",
        null,null,null,emptyList(),"در انتظار داده معتبر","در انتظار داده معتبر",emptyList(),listOf(reason))
    private fun valid(x:DoubleArray)=x.size>=6&&x.take(6).all{it.isFinite()}&&x[0]>0&&
        x[1]>=max(x[0],x[3])&&x[2]<=min(x[0],x[3])&&x[2]>0&&x[3]>0&&x[4]>=0&&x[5]>0
    private fun pivots(h:List<Double>,l:List<Double>,left:Int,right:Int):List<Pivot>{
        val out=mutableListOf<Pivot>()
        for(i in left until h.size-right){
            if((i-left until i+right+1).all{j->j==i||h[i]>=h[j]})out+=Pivot(i,h[i],true)
            if((i-left until i+right+1).all{j->j==i||l[i]<=l[j]})out+=Pivot(i,l[i],false)
        }
        return out.sortedBy{it.index}
    }
    private fun structure(h:List<Pivot>,l:List<Pivot>,p:Double):String{
        if(h.size<2||l.size<2)return "ساختار در حال شکل‌گیری"
        val a=h.takeLast(2);val b=l.takeLast(2)
        return when{
            a[1].price>a[0].price&&b[1].price>b[0].price->"سقف و کف بالاتر • ساختار صعودی"
            a[1].price<a[0].price&&b[1].price<b[0].price->"سقف و کف پایین‌تر • ساختار نزولی"
            p>a.last().price->"شکست صعودی سقف ساختاری"
            p<b.last().price->"شکست نزولی کف ساختاری"
            else->"ساختار رنج/مختلط"
        }
    }
    private fun profile(bars:List<DoubleArray>,bins:Int):Profile?{
        val low=bars.minOf{it[2]};val high=bars.maxOf{it[1]}
        if(!low.isFinite()||!high.isFinite()||high<=low)return null
        val vol=DoubleArray(bins)
        bars.forEach{b->
            val typical=(b[1]+b[2]+b[3])/3.0
            val idx=(((typical-low)/(high-low))*bins).toInt().coerceIn(0,bins-1)
            vol[idx]+=b[4]
        }
        val total=vol.sum();if(total<=0)return null
        val poc=vol.indices.maxByOrNull{vol[it]}?:return null
        var left=poc;var right=poc;var accumulated=vol[poc]
        while(accumulated<total*0.70&&(left>0||right<bins-1)){
            val lv=if(left>0)vol[left-1] else -1.0
            val rv=if(right<bins-1)vol[right+1] else -1.0
            if(rv>lv){right++;accumulated+=max(0.0,rv)}
            else if(left>0){left--;accumulated+=max(0.0,lv)}else break
        }
        val step=(high-low)/bins
        return Profile(low+(poc+0.5)*step,low+left*step,low+(right+1)*step)
    }
    private fun candlePatterns(o:List<Double>,h:List<Double>,l:List<Double>,c:List<Double>):List<String>{
        val out=mutableListOf<String>();val i=c.lastIndex
        val body=abs(c[i]-o[i]);val range=(h[i]-l[i]).coerceAtLeast(1e-12)
        val upper=h[i]-max(o[i],c[i]);val lower=min(o[i],c[i])-l[i]
        val bull=c[i]>o[i];val bear=c[i]<o[i];val loc=(c[i]-l[i])/range
        if(body/range<=0.08)out+="Doji • بلاتکلیفی"
        if(lower>=body*2.2&&upper<=body*0.8&&loc>=0.62)out+="بازگشت صعودی احتمالی • Hammer"
        if(upper>=body*2.2&&lower<=body*0.8&&loc<=0.38)out+="بازگشت نزولی احتمالی • Shooting Star"
        if(body/range>=0.88)out+=if(bull)"Bullish Marubozu" else if(bear)"Bearish Marubozu" else ""
        if(i>=1){
            val pb=abs(c[i-1]-o[i-1])
            if(bull&&c[i-1]<o[i-1]&&o[i]<=c[i-1]&&c[i]>=o[i-1]&&body>pb)out+="بازگشت صعودی • Bullish Engulfing"
            if(bear&&c[i-1]>o[i-1]&&o[i]>=c[i-1]&&c[i]<=o[i-1]&&body>pb)out+="بازگشت نزولی • Bearish Engulfing"
            if(h[i]<h[i-1]&&l[i]>l[i-1])out+="Inside Bar • فشردگی"
            if(h[i]>h[i-1]&&l[i]<l[i-1])out+="Outside Bar • گسترش دامنه"
            if(pb>0&&body<pb*0.55&&min(o[i],c[i])>=min(o[i-1],c[i-1])&&max(o[i],c[i])<=max(o[i-1],c[i-1]))out+="Harami • مکث"
            if(c[i-1]<o[i-1]&&bull&&c[i]>(o[i-1]+c[i-1])/2&&o[i]<c[i-1])out+="Piercing Line"
            if(c[i-1]>o[i-1]&&bear&&c[i]<(o[i-1]+c[i-1])/2&&o[i]>c[i-1])out+="Dark Cloud Cover"
            if(abs(h[i]-h[i-1])/h[i]<0.0015&&bear)out+="Tweezer Top احتمالی"
            if(abs(l[i]-l[i-1])/l[i]<0.0015&&bull)out+="Tweezer Bottom احتمالی"
        }
        if(i>=2){
            val mb=abs(c[i-1]-o[i-1])<=abs(c[i-2]-o[i-2])*0.35
            if(c[i-2]<o[i-2]&&mb&&bull&&c[i]>(o[i-2]+c[i-2])/2)out+="Morning Star احتمالی"
            if(c[i-2]>o[i-2]&&mb&&bear&&c[i]<(o[i-2]+c[i-2])/2)out+="Evening Star احتمالی"
            if(c[i-2]<o[i-2]&&c[i-1]>o[i-1]&&bull&&c[i]>c[i-1])out+="Three White Soldiers احتمالی"
            if(c[i-2]>o[i-2]&&c[i-1]<o[i-1]&&bear&&c[i]<c[i-1])out+="Three Black Crows احتمالی"
        }
        return out.filter{it.isNotBlank()}.distinct()
    }
    private fun chartPatterns(h:List<Pivot>,l:List<Pivot>):List<String>{
        val out=mutableListOf<String>()
        if(h.size>=2&&abs(h[h.lastIndex].price-h[h.lastIndex-1].price)/max(h.last().price,h[h.lastIndex-1].price)<0.012)out+="Double Top احتمالی • شکست گردن لازم است"
        if(l.size>=2&&abs(l[l.lastIndex].price-l[l.lastIndex-1].price)/max(l.last().price,l[l.lastIndex-1].price)<0.012)out+="Double Bottom احتمالی • شکست گردن لازم است"
        if(h.size>=3&&l.size>=3){
            val a=h.takeLast(3).map{it.price};val b=l.takeLast(3).map{it.price}
            if(a[1]>a[0]&&a[1]>a[2]&&abs(a[0]-a[2])/a[1]<0.025)out+="Head and Shoulders احتمالی"
            if(b[1]<b[0]&&b[1]<b[2]&&abs(b[0]-b[2])/b[1]<0.025)out+="Inverse Head and Shoulders احتمالی"
        }
        return out
    }
    private fun avg(x:List<Double>)=if(x.isEmpty())0.0 else x.average()
    private fun linearSlope(x:List<Double>):Double{
        if(x.size<3)return 0.0
        val mx=(x.size-1)/2.0;val my=x.average();var cov=0.0;var variance=0.0
        x.forEachIndexed{i,y->cov+=(i-mx)*(y-my);variance+=(i-mx)*(i-mx)}
        return if(variance<=0)0.0 else cov/variance
    }
    private fun fmt(x:Double)=String.format(java.util.Locale.US,"%.6f",x).trimEnd('0').trimEnd('.')
}
