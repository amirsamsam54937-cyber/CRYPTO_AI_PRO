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

/** Ten explainable specialist rule agents. These are not ten trained neural networks. */
object WolfConsensusEngine {
    fun analyze(timeframes: List<TechnicalSnapshot>, price: Double): WolfConsensus {
        require(timeframes.size >= 3 && price > 0.0)
        val t = timeframes.take(3)
        fun signed(condition: Boolean) = if (condition) 1.0 else -1.0
        fun clamp(value: Double) = value.coerceIn(-1.0, 1.0)
        val ema = t.map { signed(it.ema20 > it.ema50) }
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
        val votes = listOf(
            WolfAgentVote("روند EMA", if (ema.average() > 0.2) "LONG" else if (ema.average() < -0.2) "SHORT" else "NEUTRAL", ema.average(), 1.2, "هم‌جهتی EMA20/50 در سه تایم‌فریم"),
            WolfAgentVote("چیدمان بلندمدت", if (trendStack.average() > 0.2) "LONG" else if (trendStack.average() < -0.2) "SHORT" else "NEUTRAL", trendStack.average(), 1.1, "ترتیب EMA20/50/100/200"),
            WolfAgentVote("مومنتوم RSI", if (t.map { (it.stochasticRsi - 50.0) / 40.0 }.average() > 0.2) "LONG" else if (t.map { (it.stochasticRsi - 50.0) / 40.0 }.average() < -0.2) "SHORT" else "NEUTRAL", clamp(t.map { (it.stochasticRsi - 50.0) / 40.0 }.average()), 0.9, "مومنتوم Stoch RSI سه بازه"),
            WolfAgentVote("قدرت ADX", if (t.map { signed(it.ema20 > it.ema50) * min(it.adx / 35.0, 1.0) }.average() > 0.2) "LONG" else if (t.map { signed(it.ema20 > it.ema50) * min(it.adx / 35.0, 1.0) }.average() < -0.2) "SHORT" else "NEUTRAL", clamp(t.map { signed(it.ema20 > it.ema50) * min(it.adx / 35.0, 1.0) }.average()), 0.9, "جهت روند با وزن قدرت ADX"),
            WolfAgentVote("تأیید VWAP", if (t.map { signed(price > it.vwap) }.average() > 0.2) "LONG" else if (t.map { signed(price > it.vwap) }.average() < -0.2) "SHORT" else "NEUTRAL", t.map { signed(price > it.vwap) }.average(), 0.8, "جایگاه قیمت نسبت به VWAP"),
            WolfAgentVote("باند بولینگر", if (t.map { (it.bollingerPosition - 0.5) * 2.0 }.average() > 0.2) "LONG" else if (t.map { (it.bollingerPosition - 0.5) * 2.0 }.average() < -0.2) "SHORT" else "NEUTRAL", clamp(t.map { (it.bollingerPosition - 0.5) * 2.0 }.average()), 0.7, "موقعیت قیمت در باند بولینگر"),
            WolfAgentVote("حجم نسبی", if (t.map { signed(price > it.vwap) * ((it.relativeVolume - 0.8) / 1.2).coerceIn(0.0, 1.0) }.average() > 0.2) "LONG" else if (t.map { signed(price < it.vwap) * ((it.relativeVolume - 0.8) / 1.2).coerceIn(0.0, 1.0) }.average() > 0.2) "SHORT" else "NEUTRAL", clamp(t.map { signed(price > it.vwap) * ((it.relativeVolume - 0.8) / 1.2).coerceIn(0.0, 1.0) }.average()), 0.7, "قدرت حجم نسبت به میانگین"),
            WolfAgentVote("ساختار بازار", if (structure.average() > 0.2) "LONG" else if (structure.average() < -0.2) "SHORT" else "NEUTRAL", structure.average(), 1.0, "سقف و کف‌های بالاتر یا پایین‌تر"),
            WolfAgentVote("الگوی کندلی", if (candle.average() > 0.2) "LONG" else if (candle.average() < -0.2) "SHORT" else "NEUTRAL", candle.average(), 0.6, "الگوهای کندلی تأییدشده در سه بازه"),
            WolfAgentVote("هم‌گرایی تایم‌فریم", if (ema.all { it > 0 } && trendStack.count { it > 0 } >= 2) "LONG" else if (ema.all { it < 0 } && trendStack.count { it < 0 } >= 2) "SHORT" else "NEUTRAL", if (ema.all { it > 0 }) 1.0 else if (ema.all { it < 0 }) -1.0 else 0.0, 1.2, "توافق ۱۵ دقیقه، ۱ ساعت و ۴ ساعت")
        )
        val weight = votes.sumOf { it.weight }
        val consensus = votes.sumOf { it.score * it.weight } / weight
        val longWeight = votes.filter { it.direction == "LONG" }.sumOf { it.weight }
        val shortWeight = votes.filter { it.direction == "SHORT" }.sumOf { it.weight }
        val agreement = (max(longWeight, shortWeight) / weight * 100).toInt()
        val direction = when {
            consensus >= 0.22 && longWeight / weight >= 0.48 -> "LONG_BIAS"
            consensus <= -0.22 && shortWeight / weight >= 0.48 -> "SHORT_BIAS"
            else -> "NEUTRAL"
        }
        val strength = (abs(consensus) * 75.0 + agreement * 0.2).toInt().coerceIn(0, 95)
        return WolfConsensus(direction, consensus, agreement, strength, votes)
    }
}
