package com.cryptoaipro.monitor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Multi-scale time-series diagnostics for signal quality control.
 *
 * FFT is an ordinary Cooley-Tukey transform; Haar coefficients are a compact
 * multi-resolution diagnostic; Kalman is a one-dimensional local-level filter.
 * None of these methods is treated as a standalone price oracle.
 */
internal object WolfTimeSeriesEngine {
    data class Diagnostics(
        val samples: Int,
        val dominantCycleBars: Int,
        val spectralEntropy: Double,
        val shortScaleEnergy: Double,
        val longScaleEnergy: Double,
        val kalmanTrendPct: Double,
        val lagOneAutocorrelation: Double,
        val hurstEstimate: Double,
        val bullishRegimeProbability: Double,
        val bearishRegimeProbability: Double,
        val rangeRegimeProbability: Double
    )

    private fun mean(x: List<Double>): Double = if (x.isEmpty()) 0.0 else x.sum() / x.size
    private fun variance(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = mean(x)
        return x.sumOf { (it - m) * (it - m) } / (x.size - 1)
    }

    private fun autocorrelation(x: List<Double>, lag: Int): Double {
        if (lag <= 0 || x.size <= lag + 2) return 0.0
        val m = mean(x)
        var numerator = 0.0
        var denominator = 0.0
        for (i in x.indices) {
            val d = x[i] - m
            denominator += d * d
            if (i >= lag) numerator += d * (x[i - lag] - m)
        }
        return if (denominator <= 1e-18) 0.0 else (numerator / denominator).coerceIn(-1.0, 1.0)
    }

    /** In-place radix-2 complex FFT. */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until length / 2) {
                    val even = start + k
                    val odd = even + length / 2
                    val vr = real[odd] * wr - imag[odd] * wi
                    val vi = real[odd] * wi + imag[odd] * wr
                    real[odd] = real[even] - vr
                    imag[odd] = imag[even] - vi
                    real[even] += vr
                    imag[even] += vi
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }
    }

    private fun spectrum(returns: List<Double>): Triple<Int, Double, Double> {
        if (returns.size < 32) return Triple(0, 1.0, 0.0)
        val n = Integer.highestOneBit(min(returns.size, 256))
        if (n < 32) return Triple(0, 1.0, 0.0)
        val data = returns.takeLast(n)
        val avg = mean(data)
        val real = DoubleArray(n) { i ->
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            (data[i] - avg) * window
        }
        val imag = DoubleArray(n)
        fft(real, imag)
        val power = (1..(n / 2)).map { k -> real[k] * real[k] + imag[k] * imag[k] }
        val total = power.sum()
        if (total <= 1e-18) return Triple(0, 1.0, 0.0)
        val probabilities = power.map { it / total }
        val entropy = -probabilities.filter { it > 1e-15 }.sumOf { it * ln(it) } / ln(probabilities.size.toDouble())
        val peakIndex = power.indices.maxByOrNull { power[it] } ?: 0
        val cycle = if (peakIndex == 0) 0 else (n / (peakIndex + 1)).coerceIn(2, n / 2)
        val peakShare = power[peakIndex] / total
        return Triple(cycle, entropy.coerceIn(0.0, 1.0), peakShare)
    }

    /** Haar detail-energy ratios at fine and coarse scales. */
    private fun haarEnergy(returns: List<Double>): Pair<Double, Double> {
        if (returns.size < 16) return 0.0 to 0.0
        var values = returns.takeLast(Integer.highestOneBit(min(returns.size, 256))).toDoubleArray()
        val energies = mutableListOf<Double>()
        while (values.size >= 2) {
            val half = values.size / 2
            val next = DoubleArray(half)
            var detailEnergy = 0.0
            for (i in 0 until half) {
                val a = values[2 * i]
                val b = values[2 * i + 1]
                next[i] = (a + b) / sqrt(2.0)
                val detail = (a - b) / sqrt(2.0)
                detailEnergy += detail * detail
            }
            energies += detailEnergy / half
            values = next
        }
        val total = energies.sum().coerceAtLeast(1e-18)
        return (energies.firstOrNull() ?: 0.0) / total to (energies.lastOrNull() ?: 0.0) / total
    }

    /** One-dimensional Kalman local-level filter; returns smoothed level and slope. */
    private fun kalmanSlope(values: List<Double>): Double {
        if (values.size < 12) return 0.0
        var level = values.first()
        var covariance = 1.0
        val processNoise = 1e-5
        val observationNoise = 2e-3
        val filtered = ArrayList<Double>(values.size)
        for (observation in values) {
            covariance += processNoise
            val gain = covariance / (covariance + observationNoise)
            level += gain * (observation - level)
            covariance *= (1.0 - gain)
            filtered += level
        }
        val start = filtered[max(0, filtered.size - 12)]
        val end = filtered.last()
        return if (start <= 0.0) 0.0 else (end / start - 1.0) * 100.0
    }

    /** Scale-variance Hurst proxy; intentionally labeled an estimate, not a proof of persistence. */
    private fun hurstProxy(returns: List<Double>): Double {
        if (returns.size < 64) return 0.5
        val scales = listOf(2, 4, 8, 16).filter { it * 4 <= returns.size }
        val points = scales.mapNotNull { scale ->
            val blocks = returns.chunked(scale).filter { it.size == scale }
            if (blocks.size < 4) null else {
                val blockVariances = blocks.map(::variance)
                val v = mean(blockVariances)
                if (v <= 1e-18) null else ln(v) to ln(scale.toDouble())
            }
        }
        if (points.size < 2) return 0.5
        val mx = mean(points.map { it.second })
        val my = mean(points.map { it.first })
        val denom = points.sumOf { (it.second - mx) * (it.second - mx) }
        if (denom <= 1e-18) return 0.5
        val slope = points.sumOf { (it.second - mx) * (it.first - my) } / denom
        return (slope / 2.0).coerceIn(0.0, 1.0)
    }

    fun diagnostics(candles: List<DoubleArray>): Diagnostics? {
        val closes = candles.asSequence()
            .filter { it.size >= 4 && it[3].isFinite() && it[3] > 0.0 }
            .map { it[3] }.toList()
        if (closes.size < 80) return null
        val returns = closes.zipWithNext { a, b -> ln(b / a) }.filter { it.isFinite() }
        if (returns.size < 64) return null
        val (cycle, entropy, _) = spectrum(returns)
        val (fineEnergy, coarseEnergy) = haarEnergy(returns)
        val logPrices = closes.takeLast(64).map(::ln)
        val kalmanTrend = kalmanSlope(logPrices)
        val ac1 = autocorrelation(returns, 1)
        val hurst = hurstProxy(returns)
        val recentVol = sqrt(variance(returns.takeLast(16)))
        val baselineVol = sqrt(variance(returns.takeLast(64))).coerceAtLeast(1e-9)
        val trendStrength = (abs(kalmanTrend) / (recentVol * 100.0 + 0.05)).coerceIn(0.0, 4.0)
        val directional = (0.5 + 0.35 * kotlin.math.tanh(trendStrength - 0.8) +
            0.15 * kotlin.math.tanh(ac1 * 2.0)).coerceIn(0.05, 0.95)
        val volatilityRatio = (recentVol / baselineVol).coerceIn(0.0, 4.0)
        val range = (0.15 + 0.55 * entropy + 0.15 * (1.0 - min(volatilityRatio, 1.0))).coerceIn(0.05, 0.90)
        val remaining = (1.0 - range).coerceAtLeast(0.0)
        val bullShare = if (kalmanTrend >= 0.0) directional else 1.0 - directional
        val bull = remaining * bullShare
        val bear = remaining * (1.0 - bullShare)
        val sum = bull + bear + range
        return Diagnostics(returns.size, cycle, entropy, fineEnergy, coarseEnergy,
            kalmanTrend, ac1, hurst, bull / sum, bear / sum, range / sum)
    }

    /** Only reduces fragile directional confidence; never manufactures a new trade signal. */
    fun apply(signal: MarketSignal): MarketSignal {
        val d = diagnostics(signal.candles) ?: return signal.copy(
            reason = signal.reason + " | تحلیل سری زمانی: برای FFT/موجک/کالمن حداقل ۸۰ کندل معتبر لازم است"
        )
        if (signal.direction != "LONG_BIAS" && signal.direction != "SHORT_BIAS") {
            return signal.copy(reason = signal.reason + " | سری زمانی: چرخه غالب=" +
                (if (d.dominantCycleBars > 0) "${d.dominantCycleBars} کندل" else "نامشخص") +
                "، آنتروپی طیفی=" + "%.2f".format(d.spectralEntropy))
        }
        val long = signal.direction == "LONG_BIAS"
        val alignedProbability = if (long) d.bullishRegimeProbability else d.bearishRegimeProbability
        var penalty = 0
        val warnings = mutableListOf<String>()
        if (alignedProbability < 0.22) { penalty += 9; warnings += "احتمال رژیم هم‌جهت پایین است" }
        if (d.rangeRegimeProbability > 0.72) { penalty += 7; warnings += "احتمال رژیم رنج بالاست" }
        if ((long && d.kalmanTrendPct < -0.15) || (!long && d.kalmanTrendPct > 0.15)) {
            penalty += 7; warnings += "شیب فیلتر کالمن خلاف جهت است"
        }
        if (d.spectralEntropy > 0.96 && d.rangeRegimeProbability > 0.55) {
            penalty += 4; warnings += "طیف بازده فاقد چرخه غالب و پرآشوب است"
        }
        if (d.hurstEstimate < 0.38 && abs(d.kalmanTrendPct) < 0.08) {
            penalty += 3; warnings += "حافظه کوتاه‌مدت ضعیف و رفتار بازگشتی محتمل است"
        }
        val veto = penalty >= 20
        val note = " | آزمایشگاه سری‌زمانی: چرخه غالب=${if (d.dominantCycleBars > 0) d.dominantCycleBars else "نامشخص"} کندل، FFT-Entropy=${"%.2f".format(d.spectralEntropy)}، Haar ریز/درشت=${"%.2f".format(d.shortScaleEnergy)}/${"%.2f".format(d.longScaleEnergy)}، Kalman trend=${"%.3f".format(d.kalmanTrendPct)}٪، ACF1=${"%.2f".format(d.lagOneAutocorrelation)}، Hurst≈${"%.2f".format(d.hurstEstimate)}، رژیم صعودی/نزولی/رنج=${"%.0f".format(d.bullishRegimeProbability * 100)}/${"%.0f".format(d.bearishRegimeProbability * 100)}/${"%.0f".format(d.rangeRegimeProbability * 100)}٪"
        val caution = if (warnings.isEmpty()) "؛ تعارض سری‌زمانی شدید دیده نشد" else "؛ هشدارها: " + warnings.joinToString("، ")
        return signal.copy(
            direction = if (veto) "NEUTRAL" else signal.direction,
            confidence = (signal.confidence - penalty).coerceIn(0, 95),
            score = if (veto) 0 else (signal.score - penalty / 2).coerceIn(-100, 100),
            reason = signal.reason + note + caution
        )
    }
}
