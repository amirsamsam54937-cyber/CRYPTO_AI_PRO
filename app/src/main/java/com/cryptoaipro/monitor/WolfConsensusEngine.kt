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
        // Input order: 5m, 15m, 1h, 4h, 1d. Higher timeframes carry
        // more strategic weight; 5m is execution timing, never the market boss.
        fun weightedMean(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val weights = when (values.size) {
                5 -> listOf(0.45, 0.85, 1.10, 1.30, 1.45)
                4 -> listOf(0.65, 1.00, 1.25, 1.40)
                3 -> listOf(0.90, 1.10, 1.30)
                else -> List(values.size) { 1.0 }
            }
            return values.indices.sumOf { values[it] * weights[it] } / weights.sum()
        }

        val ema = t.map { trend((it.ema20 - it.ema50) / max(abs(it.ema50), 1e-12)) }
        val trendStack = t.map {
            when {
                it.ema20 > it.ema50 && it.ema50 > it.ema100 && it.ema100 > it.ema200 -> 1.0
                it.ema20 < it.ema50 && it.ema50 < it.ema100 && it.ema100 < it.ema200 -> -1.0
                else -> 0.0
            }
        }
        val structure = t.map {
            val base = when {
                it.marketStructure.contains("HH/HL") || it.marketStructure.contains("BOS صعودی") || it.marketStructure.contains("CHOCH صعودی") -> 1.0
                it.marketStructure.contains("LH/LL") || it.marketStructure.contains("BOS نزولی") || it.marketStructure.contains("CHOCH نزولی") -> -1.0
                else -> 0.0
            }
            val sweep = when {
                it.liquiditySweep.contains("برگشت صعودی") -> 0.30
                it.liquiditySweep.contains("برگشت نزولی") -> -0.30
                else -> 0.0
            }
            clamp(base * 0.80 + sweep)
        }
        val candle = t.map {
            val pattern = when {
                it.candlePattern.contains("صعودی") || it.candlePattern.contains("چکش") -> 1.0
                it.candlePattern.contains("نزولی") || it.candlePattern.contains("ثاقب") -> -1.0
                else -> 0.0
            }
            val divergence = when {
                it.rsiDivergence.contains("مثبت") -> 0.25
                it.rsiDivergence.contains("منفی") -> -0.25
                else -> 0.0
            }
            val imbalance = when {
                it.fairValueGap.contains("صعودی") -> 0.15
                it.fairValueGap.contains("نزولی") -> -0.15
                else -> 0.0
            }
            clamp(pattern * 0.70 + divergence + imbalance)
        }
        val rsiMomentum = t.map {
            clamp((it.stochasticRsi - 50.0) / 35.0 * 0.65 +
                (it.cci / 200.0).coerceIn(-1.0, 1.0) * 0.20 +
                (it.rocPercent / 2.0).coerceIn(-1.0, 1.0) * 0.15)
        }.let(::weightedMean)
        val adxMomentum = t.map {
            val emaDirection = trend((it.ema20 - it.ema50) / max(abs(it.ema50), 1e-12))
            val supertrend = when (it.supertrendDirection) { "صعودی" -> 1.0; "نزولی" -> -1.0; else -> 0.0 }
            clamp(emaDirection * 0.75 + supertrend * 0.25) * (it.adx / 35.0).coerceIn(0.0, 1.0)
        }.let(::weightedMean)
        val vwapMomentum = t.map {
            val distance = (price - it.vwap) / max(abs(it.vwap), 1e-12)
            trend(distance * 100.0)
        }.let(::weightedMean)
        val bandMomentum = t.map {
            val p = it.bollingerPosition
            // Mean reversion is considered only in a weak/ranging regime; trend breakouts follow direction.
            when {
                it.adx < 18.0 && p > 0.96 -> -0.55
                it.adx < 18.0 && p < 0.04 -> 0.55
                p in 0.42..0.58 -> 0.0
                p > 0.92 -> 0.35
                p < 0.08 -> -0.35
                else -> clamp((p - 0.5) * 2.0)
            }
        }.let(::weightedMean)
        val volumeMomentum = t.map {
            val relative = ((it.relativeVolume - 1.0) / 1.5).coerceIn(0.0, 1.0)
            val side = trend((price - it.vwap) / max(abs(it.vwap), 1e-12) * 100.0)
            val moneyFlow = ((it.mfi - 50.0) / 30.0).coerceIn(-1.0, 1.0)
            clamp((side * 0.40 + moneyFlow * 0.30 + it.obvTrend * 0.30) * relative)
        }.let(::weightedMean)

        val votes = listOf(
            WolfAgentVote("روند EMA", direction(weightedMean(ema)), weightedMean(ema), 1.2, "فاصله نسبی EMA20 و EMA50 در تایم‌فریم‌های تحلیل‌شده"),
            WolfAgentVote("چیدمان بلندمدت", direction(weightedMean(trendStack)), weightedMean(trendStack), 1.1, "ترتیب EMA20/50/100/200"),
            WolfAgentVote("مومنتوم RSI", direction(rsiMomentum), rsiMomentum, 0.9, "Stochastic RSI در تایم‌فریم‌های تحلیل‌شده"),
            WolfAgentVote("قدرت ADX", direction(adxMomentum), clamp(adxMomentum), 1.0, "جهت روند با وزن قدرت ADX؛ روند ضعیف رأی قوی نمی‌گیرد"),
            WolfAgentVote("تأیید VWAP", direction(vwapMomentum), vwapMomentum, 0.8, "موقعیت قیمت نسبت به VWAP"),
            WolfAgentVote("باند بولینگر", direction(bandMomentum), clamp(bandMomentum), 0.7, "مومنتوم باند با کاهش وزن در نواحی افراطی"),
            WolfAgentVote("حجم نسبی", direction(volumeMomentum), clamp(volumeMomentum), 0.8, "حجم بالاتر از میانگین همراه با جهت قیمت"),
            WolfAgentVote("ساختار بازار", direction(weightedMean(structure)), weightedMean(structure), 1.1, "سقف و کف‌های بالاتر یا پایین‌تر"),
            WolfAgentVote("الگوی کندلی", direction(weightedMean(candle)), weightedMean(candle), 0.6, "الگوهای کندلی در بازه‌های تحلیل؛ به‌تنهایی سیگنال‌ساز نیست"),
            WolfAgentVote(
                "هم‌گرایی تایم‌فریم",
                if ((if (t.size >= 5) ema.drop(1).all { it > 0.0 } else ema.all { it > 0.0 }) &&
                    trendStack.count { it > 0.0 } >= (if (t.size >= 5) 3 else 2)) "LONG"
                else if ((if (t.size >= 5) ema.drop(1).all { it < 0.0 } else ema.all { it < 0.0 }) &&
                    trendStack.count { it < 0.0 } >= (if (t.size >= 5) 3 else 2)) "SHORT"
                else "NEUTRAL",
                if ((if (t.size >= 5) ema.drop(1).all { it > 0.0 } else ema.all { it > 0.0 }) &&
                    trendStack.count { it > 0.0 } >= (if (t.size >= 5) 3 else 2)) 1.0
                else if ((if (t.size >= 5) ema.drop(1).all { it < 0.0 } else ema.all { it < 0.0 }) &&
                    trendStack.count { it < 0.0 } >= (if (t.size >= 5) 3 else 2)) -1.0
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
        val alignedLong = ema.count { it > 0.0 } >= (if (t.size >= 5) 3 else 2) &&
            fourHourTrend >= 0.0 && (t.size < 5 || dailyTrend >= 0.0)
        val alignedShort = ema.count { it < 0.0 } >= (if (t.size >= 5) 3 else 2) &&
            fourHourTrend <= 0.0 && (t.size < 5 || dailyTrend <= 0.0)
        val averageAdx = t.map { it.adx }.let(::weightedMean)
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
