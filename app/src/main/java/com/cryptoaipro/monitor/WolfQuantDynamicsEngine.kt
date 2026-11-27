package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Quantitative dynamics engine: robust, candle-based state estimation.
 * The "physics" vocabulary is descriptive analogy, not a claim that markets obey
 * a deterministic physical law. Input rows: [open, high, low, close, volume, timestamp].
 */
internal object WolfQuantDynamicsEngine {
    enum class State { TREND, BALANCED, COMPRESSION, CHAOTIC, TRANSITION, INSUFFICIENT }
    data class Scenario(val horizonBars: Int, val expectedMovePct: Double, val lowerBandPct: Double, val upperBandPct: Double)
    data class Report(
        val available: Boolean, val state: State, val stateConfidence: Double,
        val trendVelocityPct: Double, val accelerationPct: Double, val realizedVolPct: Double,
        val volatilityRatio: Double, val efficiencyRatio: Double, val returnEntropy: Double,
        val varianceRatio: Double, val kalmanPrice: Double, val kalmanTrendPct: Double,
        val momentumEnergy: Double, val directionalBias: String,
        val trendProbability: Double, val rangeProbability: Double,
        val chaosProbability: Double, val compressionProbability: Double,
        val scenarios: List<Scenario>, val riskDirective: String, val notes: List<String>
    )

    fun analyze(input: List<DoubleArray>): Report {
        val bars = input.filter { c -> c.size >= 6 && c.take(6).all { it.isFinite() } &&
            c[0] > 0 && c[1] >= max(c[0],c[3]) && c[2] <= min(c[0],c[3]) &&
            c[2] > 0 && c[3] > 0 && c[4] >= 0 && c[5] > 0
        }.sortedBy { it[5] }.distinctBy { it[5].toLong() }.takeLast(240)
        if (bars.size < 100) return empty()
        val closes = bars.map { it[3] }
        val returns = closes.zipWithNext { a,b -> ln(b/a) * 100.0 }
        val last = closes.last()
        val recent = returns.takeLast(48)
        val older = returns.dropLast(48).takeLast(96)
        val sigma = stdev(recent).coerceAtLeast(1e-9)
        val oldSigma = stdev(older).coerceAtLeast(1e-9)
        val volRatio = sigma / oldSigma
        val velocity = recent.takeLast(12).average()
        val previousVelocity = recent.dropLast(12).takeLast(12).average()
        val acceleration = velocity - previousVelocity
        val efficiency = efficiencyRatio(closes.takeLast(40))
        val entropy = signEntropy(recent.takeLast(48))
        val varianceRatio = varianceRatio(recent, 4)
        val (kalman, trend) = alphaBeta(closes.takeLast(80))
        val momentum = velocity * velocity / (sigma * sigma).coerceAtLeast(1e-12)
        val trendScore = (abs(velocity) / sigma).coerceIn(0.0, 4.0) * .65 + efficiency * 2.0 +
            (if (varianceRatio > 1.15) .6 else 0.0)
        val rangeScore = (1.0-efficiency) * 2.0 + (if (varianceRatio < .9) .5 else 0.0)
        val chaosScore = max(0.0, volRatio-1.25)*1.8 + max(0.0, entropy-.82)*2.0
        val compressionScore = max(0.0, .78-volRatio)*2.2 + max(0.0, .35-efficiency)
        val probs = softmax(listOf(trendScore, rangeScore, chaosScore, compressionScore, .45))
        val maxProb = probs.maxOrNull() ?: 0.0
        val state = when (probs.indexOf(maxProb)) {
            0 -> State.TREND
            1 -> State.BALANCED
            2 -> State.CHAOTIC
            3 -> State.COMPRESSION
            else -> State.TRANSITION
        }
        val bias = when {
            state == State.CHAOTIC || state == State.COMPRESSION -> "جهت معتبر نیست؛ صبر"
            velocity > sigma*.12 && trend > 0 -> "تمایل صعودی؛ نیازمند تأیید ساختار"
            velocity < -sigma*.12 && trend < 0 -> "تمایل نزولی؛ نیازمند تأیید ساختار"
            else -> "بدون برتری جهت‌دار روشن"
        }
        val horizons = listOf(3, 6, 12)
        val drift = (velocity*.55 + trend*.45).coerceIn(-sigma*1.5, sigma*1.5)
        val scenarios = horizons.map { h ->
            val move = (drift*h).coerceIn(-sigma*sqrt(h.toDouble())*2.0, sigma*sqrt(h.toDouble())*2.0)
            val band = sigma*sqrt(h.toDouble())*1.28
            Scenario(h, move, move-band, move+band)
        }
        val directive = when(state) {
            State.CHAOTIC -> "کاهش ریسک یا عدم معامله؛ نوسان نسبت به سابق جهش کرده"
            State.COMPRESSION -> "شکست را پیش‌بینی نکن؛ فقط پس از بسته‌شدن و پذیرش قیمت بررسی کن"
            State.BALANCED -> "استراتژی بازگشت به میانگین؛ از تعقیب قیمت در میانه دامنه پرهیز کن"
            State.TREND -> "فقط هم‌جهت روند و پس از پولبک/تأیید؛ حدضرر بر مبنای نوسان"
            State.TRANSITION -> "تغییر رژیم محتمل؛ اندازه موقعیت محافظه‌کارانه یا صبر"
            else -> "داده ناکافی؛ معامله ممنوع"
        }
        return Report(true,state,maxProb,velocity,acceleration,sigma,volRatio,efficiency,entropy,
            varianceRatio,kalman,trend,momentum,bias,probs[0],probs[1],probs[2],probs[3],
            scenarios,directive,listOf(
                "سرعت و شتاب از بازده لگاریتمی کندل‌ها محاسبه شده‌اند؛ واحد آن‌ها درصد به‌ازای هر کندل است.",
                "آشفتگی با آنتروپی علامت بازده و نسبت نوسان اخیر به گذشته تقریب زده می‌شود.",
                "نسبت واریانس و فیلتر آلفا-بتا برای سنجش تداوم حرکت و تخمین روند پنهان استفاده شده‌اند.",
                "سناریوها باندهای آماری شرطی‌اند، نه پیش‌گویی قطعی و نه احتمال تضمینی رسیدن قیمت."
            ))
    }

    private fun efficiencyRatio(x: List<Double>): Double {
        if (x.size < 3) return 0.0
        val net=abs(x.last()-x.first())
        val path=x.zipWithNext { a,b -> abs(b-a) }.sum().coerceAtLeast(1e-12)
        return (net/path).coerceIn(0.0,1.0)
    }
    private fun signEntropy(x: List<Double>): Double {
        val up=x.count { it>0 }.toDouble()/x.size
        val down=x.count { it<0 }.toDouble()/x.size
        val flat=(1.0-up-down).coerceAtLeast(0.0)
        return listOf(up,down,flat).filter { it>0 }.sumOf { -it*ln(it) }/ln(3.0)
    }
    private fun varianceRatio(r: List<Double>, lag: Int): Double {
        if(r.size < lag*5) return 1.0
        val one=variance(r).coerceAtLeast(1e-12)
        val agg=(0 until r.size-lag+1).map { i -> r.subList(i,i+lag).sum() }
        return variance(agg)/(lag*one)
    }
    private fun alphaBeta(x: List<Double>): Pair<Double,Double> {
        var level=x.first(); var slope=0.0
        val alpha=.22; val beta=.035
        for(value in x.drop(1)) {
            val predicted=level+slope
            val residual=value-predicted
            level=predicted+alpha*residual
            slope+=beta*residual
        }
        return level to slope
    }
    private fun softmax(scores: List<Double>): List<Double> {
        val m=scores.maxOrNull() ?: 0.0
        val e=scores.map { exp((it-m).coerceIn(-30.0,30.0)) }
        val sum=e.sum().coerceAtLeast(1e-12)
        return e.map { it/sum }
    }
    private fun variance(x: List<Double>): Double {
        if(x.size<2) return 0.0
        val mean=x.average()
        return x.sumOf { (it-mean)*(it-mean) }/(x.size-1)
    }
    private fun stdev(x: List<Double>)=sqrt(variance(x))
    private fun empty()=Report(false,State.INSUFFICIENT,0.0,0.0,0.0,0.0,0.0,0.0,0.0,1.0,
        0.0,0.0,0.0,"نامشخص",0.0,0.0,0.0,0.0,emptyList(),
        "معامله ممنوع تا دریافت حداقل ۱۰۰ کندل معتبر",listOf("داده کافی برای مدل دینامیک بازار وجود ندارد."))
}
