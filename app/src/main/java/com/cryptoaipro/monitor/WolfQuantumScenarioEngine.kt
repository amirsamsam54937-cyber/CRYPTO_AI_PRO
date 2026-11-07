package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Quantum-inspired scenario engine.
 *
 * This is classical deterministic probability modelling on Android, NOT quantum hardware
 * and NOT a trained transformer. It combines independent market hypotheses and perturbs
 * their evidence to estimate how stable a directional thesis is under noisy conditions.
 * It is deliberately a bounded secondary vote: it can veto weak conviction, never promise
 * a profitable trade.
 */
internal object WolfQuantumScenarioEngine {
    data class Assessment(
        val longProbability: Double,
        val shortProbability: Double,
        val neutralProbability: Double,
        val robustness: Double,
        val directionalEdge: Double,
        val regime: String,
        val weaknesses: List<String>,
        val explanation: String
    )

    private data class Expert(val name: String, val score: Double, val reliability: Double)

    fun analyze(signal: MarketSignal): Assessment {
        val t = signal.technical
        val isLong = signal.direction == "LONG_BIAS"
        val candles = signal.candles.takeLast(80)
        val returns = candles.mapNotNull { c ->
            if (c.size >= 4 && c[0] > 0.0 && c[3] > 0.0) ln(c[3] / c[0]) else null
        }
        val returnVol = if (returns.size > 2) {
            val mean = returns.average()
            sqrt(returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1))
        } else 0.0
        val normalizedVol = (returnVol * 100.0 / max(t.atrPercent, 0.15)).coerceIn(0.0, 2.0)
        val trend = when {
            t.ema20 > t.ema50 && t.ema50 > t.ema100 -> 0.85
            t.ema20 < t.ema50 && t.ema50 < t.ema100 -> -0.85
            t.ema20 > t.ema50 -> 0.35
            t.ema20 < t.ema50 -> -0.35
            else -> 0.0
        }
        val directionSign = if (isLong) 1.0 else -1.0
        val rsiMomentum = ((signal.rsi - 50.0) / 35.0).coerceIn(-1.0, 1.0)
        val volumeImpulse = ((t.relativeVolume - 1.0) / 2.0).coerceIn(-1.0, 1.0)
        val structure = when {
            t.marketStructure.contains("صعود") || t.marketStructure.contains("HH") || t.marketStructure.contains("BOS+") -> 0.75
            t.marketStructure.contains("نزول") || t.marketStructure.contains("LL") || t.marketStructure.contains("BOS-") -> -0.75
            else -> 0.0
        }
        val supertrend = when {
            t.supertrendDirection.contains("صعود") || t.supertrendDirection.contains("مثبت") -> 0.7
            t.supertrendDirection.contains("نزول") || t.supertrendDirection.contains("منفی") -> -0.7
            else -> 0.0
        }
        val macd = if (t.macdLine >= t.macdSignalLine) 0.45 else -0.45
        val location = when {
            t.resistance > 0 && signal.price >= t.resistance * 0.997 -> -0.55
            t.support > 0 && signal.price <= t.support * 1.003 -> 0.55
            else -> 0.0
        }
        val experts = listOf(
            Expert("trend", trend, 1.0),
            Expert("momentum", rsiMomentum, 0.82),
            Expert("volume", volumeImpulse * if (signal.change >= 0) 1 else -1, 0.72),
            Expert("structure", structure, 0.95),
            Expert("supertrend", supertrend, 0.76),
            Expert("macd", macd, 0.70),
            Expert("levels", location, 0.66),
            Expert("base_signal", (signal.score / 100.0).coerceIn(-1.0, 1.0), 0.80)
        )
        val weighted = experts.sumOf { it.score * it.reliability } /
            experts.sumOf { it.reliability }.coerceAtLeast(1e-9)
        val aligned = experts.count { abs(it.score) > 0.20 && it.score * weighted > 0 }
        val disagreement = 1.0 - aligned.toDouble() / experts.size
        val volatilityPenalty = (normalizedVol - 1.0).coerceAtLeast(0.0) * 0.22
        val squeezeBoost = if (t.bollingerWidthPct in 0.0..1.8 && t.relativeVolume > 1.25) 0.10 else 0.0

        // Scenario perturbations: 256 deterministic evidence perturbations avoid random
        // flicker between refreshes while exposing fragile, noise-sensitive signal theses.
        val outcomes = DoubleArray(256)
        for (i in outcomes.indices) {
            var perturbation = 0.0
            experts.forEachIndexed { j, e ->
                val wave = kotlin.math.sin((i + 1) * (j + 2) * 0.61803398875) * 0.5 +
                    kotlin.math.cos((i + 3) * (j + 1) * 0.41421356237) * 0.5
                perturbation += wave * (1.0 - e.reliability * 0.35) * 0.075
            }
            outcomes[i] = (weighted + perturbation).coerceIn(-1.5, 1.5)
        }
        val longCount = outcomes.count { it > 0.18 }
        val shortCount = outcomes.count { it < -0.18 }
        val neutralCount = outcomes.size - longCount - shortCount
        val total = outcomes.size.toDouble()
        val longP = longCount / total
        val shortP = shortCount / total
        val neutralP = neutralCount / total
        val directional = if (isLong) longP - shortP else shortP - longP
        val robustness = (1.0 - disagreement * 0.55 - volatilityPenalty + squeezeBoost)
            .coerceIn(0.0, 1.0)

        val weaknesses = mutableListOf<String>()
        if (t.liquiditySweep != "مشاهده نشد" && t.liquiditySweep.isNotBlank()) weaknesses += "ردپای جاروب نقدینگی: ${t.liquiditySweep}"
        if (t.fairValueGap != "مشاهده نشد" && t.fairValueGap.isNotBlank()) weaknesses += "شکاف ارزش منصفانه: ${t.fairValueGap}"
        if (signal.rsi >= 75) weaknesses += "اشباع خرید؛ ریسک برگشت یا تله لانگ"
        if (signal.rsi <= 25) weaknesses += "اشباع فروش؛ ریسک برگشت یا تله شورت"
        if (t.relativeVolume < 0.65) weaknesses += "حجم ضعیف؛ شکست قیمت ممکن است بی‌پشتوانه باشد"
        if (t.relativeVolume > 2.5 && abs(signal.change) > 4.0) weaknesses += "جهش غیرعادی حجم و قیمت؛ احتمال حرکت هیجانی"
        if (t.bollingerWidthPct < 1.2) weaknesses += "فشردگی نوسان؛ احتمال گسترش دامنه در هر دو جهت"
        if (normalizedVol > 1.45) weaknesses += "نوسان کوتاه‌مدت بالا؛ فاصله حدضرر و اندازه ریسک حساس است"
        if (disagreement > 0.45) weaknesses += "اختلاف زیاد میان متخصص‌ها؛ اجماع شکننده"
        if (weaknesses.isEmpty()) weaknesses += "ضعف ساختاری پرقدرتی در داده‌های موجود شناسایی نشد"

        val regime = when {
            t.adx >= 25 && abs(trend) >= 0.6 -> "رونددار"
            t.bollingerWidthPct < 1.5 -> "فشردگی / آماده گسترش"
            normalizedVol > 1.4 -> "نوسانی و پرریسک"
            else -> "خنثی / انتقالی"
        }
        val explanation = "سناریوی صعود ${(longP * 100).toInt()}٪، نزول ${(shortP * 100).toInt()}٪، خنثی ${(neutralP * 100).toInt()}٪؛ پایداری شواهد ${(robustness * 100).toInt()}٪؛ رژیم $regime"
        return Assessment(longP, shortP, neutralP, robustness, directional, regime, weaknesses, explanation)
    }

    /** Conservative bounded adjustment; the engine never creates a trade by itself. */
    fun apply(signal: MarketSignal): MarketSignal {
        if (signal.direction == "NEUTRAL") return signal
        val a = analyze(signal)
        val agreement = if (signal.direction == "LONG_BIAS") a.longProbability - a.shortProbability
            else a.shortProbability - a.longProbability
        val adjustment = when {
            agreement >= 0.30 && a.robustness >= 0.62 -> 3
            agreement >= 0.15 && a.robustness >= 0.50 -> 1
            agreement <= -0.25 -> -4
            agreement <= -0.12 -> -2
            else -> 0
        }
        val safeConfidence = (signal.confidence + adjustment).coerceIn(0, 95)
        return signal.copy(
            score = (signal.score + adjustment).coerceIn(-100, 100),
            confidence = safeConfidence,
            reason = signal.reason + " | موتور سناریوی چندفرضیه‌ای: " + a.explanation +
                " | ریسک‌های کشف‌شده: " + a.weaknesses.take(3).joinToString("؛ ")
        )
    }
}
