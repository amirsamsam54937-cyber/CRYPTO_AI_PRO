package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class WolfAgentVote(
    val name: String,
    val direction: String,
    val score: Double,
    val weight: Double,
    val explanation: String
)

data class WolfConsensus(
    val direction: String,
    val score: Double,
    val agreement: Int,
    val strengthIndex: Int,
    val votes: List<WolfAgentVote>
)

/**
 * Ten explainable technical-analysis specialists.
 * Their agreement is a rule-based signal-strength measure, NOT a calibrated win probability.
 */
object WolfConsensusEngine {
    fun analyze(timeframes: List<TechnicalSnapshot>, price: Double): WolfConsensus {
        require(timeframes.size >= 3 && price > 0.0)
        val t = timeframes.take(5)
        fun trend(value: Double) = when {
            value > 0.0005 -> 1.0
            value < -0.0005 -> -1.0
            else -> 0.0
        }
        fun direction(value: Double, threshold: Double = 0.18) =
            if (value >= threshold) "LONG" else if (value <= -threshold) "SHORT" else "NEUTRAL"
        fun clamp(value: Double) = value.coerceIn(-1.0, 1.0)

        val ema = t.map { trend((it.ema20 - it.ema50) / max(abs(it.ema50), 1e-12)) }
        val trendStack = t.map {
            when {
                it.ema20 > it.ema50 && it.ema50 > it.ema100 && it.ema100 > it.ema200 -> 1.0
                it.ema20 < it.ema50 && it.ema50 < it.ema100 && it.ema100 < it.ema200 -> -1.0
                else -> 0.0
            }
        }
        val structure = t.map {
            when {
                it.marketStructure.contains("HH/HL") -> 1.0
                it.marketStructure.contains("LH/LL") -> -1.0
                else -> 0.0
            }
        }
        val candle = t.map {
            when {
                it.candlePattern.contains("صعودی") || it.candlePattern.contains("چکش") -> 1.0
                it.candlePattern.contains("نزولی") || it.candlePattern.contains("ثاقب") -> -1.0
                else -> 0.0
            }
        }
        val rsiMomentum = t.map { clamp((it.stochasticRsi - 50.0) / 35.0) }.average()
        val adxMomentum = t.map { trend((it.ema20 - it.ema50) / max(abs(it.ema50), 1e-12)) * (it.adx / 35.0).coerceIn(0.0, 1.0) }.average()
        val vwapMomentum = t.map {
            val distance = (price - it.vwap) / max(abs(it.vwap), 1e-12)
            trend(distance * 100.0)
        }.average()
        val bandMomentum = t.map {
            // Avoid treating a near-band extreme as an automatic continuation signal.
            val p = it.bollingerPosition
            when {
                p in 0.42..0.58 -> 0.0
                p > 0.92 -> 0.35
                p < 0.08 -> -0.35
                else -> clamp((p - 0.5) * 2.0)
            }
        }.average()
        val volumeMomentum = t.map {
            val relative = ((it.relativeVolume - 1.0) / 1.5).coerceIn(0.0, 1.0)
            val side = trend((price - it.vwap) / max(abs(it.vwap), 1e-12) * 100.0)
            side * relative
        }.average()

        val votes = listOf(
            WolfAgentVote("روند EMA", direction(ema.average()), ema.average(), 1.2, "فاصله نسبی EMA20 و EMA50 در سه تایم‌فریم"),
            WolfAgentVote("چیدمان بلندمدت", direction(trendStack.average()), trendStack.average(), 1.1, "ترتیب EMA20/50/100/200"),
            WolfAgentVote("مومنتوم RSI", direction(rsiMomentum), rsiMomentum, 0.9, "Stochastic RSI در سه تایم‌فریم"),
            WolfAgentVote("قدرت ADX", direction(adxMomentum), clamp(adxMomentum), 1.0, "جهت روند با وزن قدرت ADX؛ روند ضعیف رأی قوی نمی‌گیرد"),
            WolfAgentVote("تأیید VWAP", direction(vwapMomentum), vwapMomentum, 0.8, "موقعیت قیمت نسبت به VWAP"),
            WolfAgentVote("باند بولینگر", direction(bandMomentum), clamp(bandMomentum), 0.7, "مومنتوم باند با کاهش وزن در نواحی افراطی"),
            WolfAgentVote("حجم نسبی", direction(volumeMomentum), clamp(volumeMomentum), 0.8, "حجم بالاتر از میانگین همراه با جهت قیمت"),
            WolfAgentVote("ساختار بازار", direction(structure.average()), structure.average(), 1.1, "سقف و کف‌های بالاتر یا پایین‌تر"),
            WolfAgentVote("الگوی کندلی", direction(candle.average()), candle.average(), 0.6, "الگوهای کندلی سه بازه؛ به‌تنهایی سیگنال‌ساز نیست"),
            WolfAgentVote(
                "هم‌گرایی تایم‌فریم",
                if ((if (t.size >= 5) ema.drop(1).all { it > 0.0 } else ema.all { it > 0.0 }) &&
                    trendStack.count { it > 0.0 } >= if (t.size >= 5) 3 else 2) "LONG"
                else if ((if (t.size >= 5) ema.drop(1).all { it < 0.0 } else ema.all { it < 0.0 }) &&
                    trendStack.count { it < 0.0 } >= if (t.size >= 5) 3 else 2) "SHORT"
                else "NEUTRAL",
                if ((if (t.size >= 5) ema.drop(1).all { it > 0.0 } else ema.all { it > 0.0 }) &&
                    trendStack.count { it > 0.0 } >= if (t.size >= 5) 3 else 2) 1.0
                else if ((if (t.size >= 5) ema.drop(1).all { it < 0.0 } else ema.all { it < 0.0 }) &&
                    trendStack.count { it < 0.0 } >= if (t.size >= 5) 3 else 2) -1.0
                else 0.0,
                1.3,
                if (t.size >= 5) "توافق روند در ۱۵ دقیقه، ۱ ساعت، ۴ ساعت و ۱ روز؛ ۵ دقیقه برای زمان‌بندی" else "توافق روند در ۱۵ دقیقه، ۱ ساعت و ۴ ساعت"
            )
        )

        val totalWeight = votes.sumOf { it.weight }
        val weightedScore = votes.sumOf { it.score * it.weight } / totalWeight
        val longWeight = votes.filter { it.direction == "LONG" }.sumOf { it.weight }
        val shortWeight = votes.filter { it.direction == "SHORT" }.sumOf { it.weight }
        val directionalWeight = longWeight + shortWeight
        val agreement = if (directionalWeight <= 0.0) 0 else
            (max(longWeight, shortWeight) / directionalWeight * 100.0).toInt().coerceIn(0, 100)

        val fourHourTrend = ema[if (t.size >= 5) 3 else 2]
        val dailyTrend = if (t.size >= 5) ema[4] else 0.0
        val alignedLong = ema.count { it > 0.0 } >= if (t.size >= 5) 3 else 2 &&
            fourHourTrend >= 0.0 && (t.size < 5 || dailyTrend >= 0.0)
        val alignedShort = ema.count { it < 0.0 } >= if (t.size >= 5) 3 else 2 &&
            fourHourTrend <= 0.0 && (t.size < 5 || dailyTrend <= 0.0)
        val averageAdx = t.map { it.adx }.average()
        val direction = when {
            weightedScore >= 0.30 && longWeight / totalWeight >= 0.52 && agreement >= 58 &&
                alignedLong && averageAdx >= 14.0 -> "LONG_BIAS"
            weightedScore <= -0.30 && shortWeight / totalWeight >= 0.52 && agreement >= 58 &&
                alignedShort && averageAdx >= 14.0 -> "SHORT_BIAS"
            else -> "NEUTRAL"
        }
        val strength = (abs(weightedScore) * 70.0 + (agreement * 0.18) +
            if (averageAdx >= 25.0) 5.0 else 0.0).toInt().coerceIn(0, 95)
        return WolfConsensus(direction, weightedScore, agreement, strength, votes)
    }
}
