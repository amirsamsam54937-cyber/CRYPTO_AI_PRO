package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Forward-scenario confirmation from already-observed candles.
 * Heuristic score only; not calibrated odds and never a future-price oracle.
 * It may downgrade a weak signal but never creates a trade direction.
 */
internal object WolfForecastEngine {
    data class Forecast(
        val horizonBars: Int,
        val directionalProbability: Double,
        val expectedMovePct: Double,
        val volatilityPct: Double,
        val trendPersistence: Double,
        val evidenceCount: Int
    )

    private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x.coerceIn(-8.0, 8.0)))
    private fun mean(x: List<Double>) = if (x.isEmpty()) 0.0 else x.average()

    fun forecast(signal: MarketSignal): Forecast? {
        // Expected layout from the exchange adapter: [open, high, low, close, volume, openTimeMs].
        // Do not silently filter broken rows: that could stitch distant candles together and
        // create a false momentum/breakout signal.
        val candles = signal.candles
        if (candles.size < 64 || candles.any { c ->
                c.size < 6 || c.take(6).any { !it.isFinite() } ||
                    c[0] <= 0.0 || c[1] <= 0.0 || c[2] <= 0.0 || c[3] <= 0.0 ||
                    c[4] < 0.0 || c[5] <= 0.0 ||
                    c[1] < maxOf(c[0], c[3]) || c[2] > minOf(c[0], c[3]) || c[1] < c[2]
            }) return null
        if (candles.zipWithNext().any { (a, b) -> b[5] <= a[5] }) return null
        val closes = candles.map { it[3] }
        val returns = closes.zipWithNext { a, b -> ln(b / a) }.filter { it.isFinite() }
        if (returns.size < 48) return null
        val short = returns.takeLast(6)
        val medium = returns.takeLast(18)
        val long = returns.takeLast(48)
        val shortVol = sqrt(mean(short.map { it * it })).coerceAtLeast(1e-8)
        val baseVol = sqrt(mean(long.map { it * it })).coerceAtLeast(1e-8)
        val momentum = (0.50 * mean(short) / baseVol + 0.35 * mean(medium) / baseVol +
            0.15 * mean(long) / baseVol).coerceIn(-2.5, 2.5)
        val path = medium.sumOf { abs(it) }.coerceAtLeast(1e-9)
        val efficiency = (abs(medium.sum()) / path).coerceIn(0.0, 1.0)
        val last = candles.last()
        val prior = candles.takeLast(21).dropLast(1)
        val priorHigh = prior.maxOfOrNull { it[1] } ?: last[1]
        val priorLow = prior.minOfOrNull { it[2] } ?: last[2]
        val breakout = when {
            last[3] > priorHigh -> 0.55
            last[3] < priorLow -> -0.55
            else -> 0.0
        }
        val volumes = candles.takeLast(30).map { it[4] }
        val averageVolume = mean(volumes.dropLast(1)).coerceAtLeast(1e-12)
        val relativeVolume = (volumes.last() / averageVolume).coerceIn(0.0, 3.0)
        val candleRange = (last[1] - last[2]).coerceAtLeast(1e-12)
        val closeLocation = ((last[3] - last[2]) / candleRange * 2.0 - 1.0).coerceIn(-1.0, 1.0)
        if (!signal.rsi.isFinite() || !signal.technical.ema20.isFinite() ||
            !signal.technical.ema50.isFinite() || !signal.technical.atrPercent.isFinite()) return null
        val rsiPressure = ((signal.rsi - 50.0) / 25.0).coerceIn(-2.0, 2.0)
        val direction = if (signal.direction == "LONG_BIAS") 1.0 else -1.0
        val technicalTrend = when {
            signal.technical.ema20 > signal.technical.ema50 -> 1.0
            signal.technical.ema20 < signal.technical.ema50 -> -1.0
            else -> 0.0
        }
        val supertrend = when (signal.technical.supertrendDirection) {
            "صعودی" -> 1.0
            "نزولی" -> -1.0
            else -> 0.0
        }
        val trendPersistence = (0.45 * momentum + 0.30 * technicalTrend + 0.25 * supertrend).coerceIn(-2.0, 2.0)
        val meanReversion = (0.45 * rsiPressure + 0.30 * (-closeLocation) +
            0.25 * (if (efficiency < 0.18) -momentum else 0.0)).coerceIn(-2.0, 2.0)
        val volumeConfirmation = ((relativeVolume - 1.0) / 1.5).coerceIn(-0.5, 1.0) *
            (if (closeLocation >= 0.0) 1.0 else -1.0)
        val alignedEvidence = direction * (0.52 * trendPersistence + 0.22 * meanReversion +
            0.16 * breakout + 0.10 * volumeConfirmation)
        val rawProbability = sigmoid(alignedEvidence * 1.35)
        val noisePenalty = (0.18 * (1.0 - efficiency) +
            0.12 * (shortVol / baseVol - 1.0).coerceAtLeast(0.0)).coerceIn(0.0, 0.25)
        val probability = (0.5 + (rawProbability - 0.5) * (1.0 - noisePenalty)).coerceIn(0.05, 0.95)
        val atrPct = signal.technical.atrPercent.coerceIn(0.0, 20.0)
        val expectedMove = (direction * (0.35 * momentum + 0.20 * trendPersistence) * atrPct)
            .coerceIn(-2.5 * atrPct, 2.5 * atrPct)
        val evidence = listOf(
            abs(momentum) > 0.12, technicalTrend != 0.0, breakout != 0.0,
            relativeVolume >= 1.2, abs(rsiPressure) > 0.5, efficiency >= 0.25
        ).count { it }
        return Forecast(4, probability, expectedMove, shortVol * 100.0, trendPersistence, evidence)
    }

    fun apply(signal: MarketSignal): MarketSignal {
        if (signal.direction != "LONG_BIAS" && signal.direction != "SHORT_BIAS") return signal
        val f = forecast(signal) ?: return signal.copy(
            reason = signal.reason + " | سناریوی آینده‌نگر: داده معتبر کافی نیست؛ پیش‌بینی غیرفعال"
        )
        val probabilityPct = (f.directionalProbability * 100.0).toInt()
        val penalty = when {
            f.directionalProbability < 0.38 -> 12
            f.directionalProbability < 0.44 -> 8
            f.directionalProbability < 0.49 -> 5
            f.directionalProbability < 0.53 -> 2
            else -> 0
        }
        val veto = f.directionalProbability < 0.36 && f.evidenceCount <= 2
        val note = " | افق سناریویی " + f.horizonBars + " کندل: امتیاز هم‌جهتی مدل " +
            probabilityPct + "٪ (غیرکالیبره)، حرکت هم‌جهت سیگنال " +
            String.format(java.util.Locale.US, "%.2f", f.expectedMovePct) + "٪، نوسان کوتاه " +
            String.format(java.util.Locale.US, "%.2f", f.volatilityPct) + "٪، شواهد " + f.evidenceCount + "/6"
        val caution = if (penalty >= 8) "؛ شواهد آینده‌نگر با جهت سیگنال تعارض جدی دارد" else ""
        return signal.copy(
            direction = if (veto) "NEUTRAL" else signal.direction,
            confidence = (signal.confidence - penalty).coerceIn(0, 95),
            score = if (veto) 0 else signal.score,
            reason = signal.reason + note + caution
        )
    }
}
