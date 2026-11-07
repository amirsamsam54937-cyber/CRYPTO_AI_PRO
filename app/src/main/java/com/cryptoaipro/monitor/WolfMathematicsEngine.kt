package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Numerical research kernel for market-signal validation.
 *
 * Includes descriptive statistics, robust quantiles, log-return risk, drawdown,
 * skew/kurtosis, entropy, linear regression, correlation, covariance, matrix
 * operations, Gaussian elimination, root finding, numerical integration and
 * deterministic Monte Carlo. This is a focused computational toolkit, not a claim
 * that an app contains every branch of mathematics or can predict the future.
 */
internal object WolfMathematicsEngine {
    data class Metrics(
        val observations: Int,
        val meanReturn: Double,
        val volatility: Double,
        val downsideDeviation: Double,
        val skewness: Double,
        val excessKurtosis: Double,
        val var95: Double,
        val cvar95: Double,
        val maxDrawdown: Double,
        val returnEntropy: Double,
        val trendSlope: Double,
        val trendR2: Double,
        val volumeReturnCorrelation: Double,
        val monteCarloLossProbability: Double
    )

    private fun clean(x: Double) = if (x.isFinite()) x else 0.0
    private fun mean(x: List<Double>) = if (x.isEmpty()) 0.0 else x.sum() / x.size
    private fun variance(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = mean(x)
        return x.sumOf { (it - m) * (it - m) } / (x.size - 1)
    }
    private fun quantile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return 0.0
        val p = q.coerceIn(0.0, 1.0) * (s.size - 1)
        val lo = p.toInt()
        val hi = min(lo + 1, s.lastIndex)
        return s[lo] + (s[hi] - s[lo]) * (p - lo)
    }

    fun correlation(a: List<Double>, b: List<Double>): Double {
        val n = min(a.size, b.size)
        if (n < 3) return 0.0
        val x = a.takeLast(n).map(::clean)
        val y = b.takeLast(n).map(::clean)
        val mx = mean(x); val my = mean(y)
        val dx = x.map { it - mx }; val dy = y.map { it - my }
        val denom = sqrt(dx.sumOf { it * it } * dy.sumOf { it * it })
        return if (denom <= 1e-18) 0.0 else (dx.indices.sumOf { dx[it] * dy[it] } / denom).coerceIn(-1.0, 1.0)
    }

    /** Ordinary least-squares slope and coefficient of determination. */
    fun linearRegression(y: List<Double>): Pair<Double, Double> {
        if (y.size < 3) return 0.0 to 0.0
        val n = y.size.toDouble()
        val xMean = (n - 1.0) / 2.0
        val yMean = mean(y)
        val numerator = y.indices.sumOf { (it - xMean) * (y[it] - yMean) }
        val denominator = y.indices.sumOf { (it - xMean).pow(2) }
        if (denominator <= 1e-18) return 0.0 to 0.0
        val slope = numerator / denominator
        val intercept = yMean - slope * xMean
        val total = y.sumOf { (it - yMean).pow(2) }
        val residual = y.indices.sumOf { (y[it] - (intercept + slope * it)).pow(2) }
        return slope to if (total <= 1e-18) 0.0 else (1.0 - residual / total).coerceIn(0.0, 1.0)
    }

    fun covarianceMatrix(columns: List<List<Double>>): Array<DoubleArray> {
        val n = columns.size
        val out = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i until n) {
            val k = min(columns[i].size, columns[j].size)
            val value = if (k < 2) 0.0 else {
                val a = columns[i].takeLast(k); val b = columns[j].takeLast(k)
                val ma = mean(a); val mb = mean(b)
                (0 until k).sumOf { (a[it] - ma) * (b[it] - mb) } / (k - 1)
            }
            out[i][j] = value; out[j][i] = value
        }
        return out
    }

    fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        require(a.isNotEmpty() && b.isNotEmpty() && a[0].size == b.size)
        require(a.all { it.size == a[0].size } && b.all { it.size == b[0].size })
        return Array(a.size) { i -> DoubleArray(b[0].size) { j ->
            var sum = 0.0
            for (k in b.indices) sum += a[i][k] * b[k][j]
            sum
        } }
    }

    /** Solves Ax=b using pivoted Gaussian elimination; null means singular/invalid. */
    fun solveLinearSystem(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
        val n = vector.size
        if (n == 0 || matrix.size != n || matrix.any { it.size != n }) return null
        val a = Array(n) { i -> matrix[i].copyOf() + vector[i] }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-12) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val div = a[col][col]
            for (j in col..n) a[col][j] /= div
            for (r in 0 until n) if (r != col) {
                val factor = a[r][col]
                for (j in col..n) a[r][j] -= factor * a[col][j]
            }
        }
        return DoubleArray(n) { a[it][n] }.takeIf { it.all(Double::isFinite) }
    }

    fun bisectionRoot(function: (Double) -> Double, low: Double, high: Double, iterations: Int = 80): Double? {
        var a = low; var b = high
        var fa = function(a); val fb = function(b)
        if (!fa.isFinite() || !fb.isFinite() || fa * fb > 0.0) return null
        repeat(iterations.coerceIn(1, 200)) {
            val mid = (a + b) / 2.0
            val fm = function(mid)
            if (!fm.isFinite()) return null
            if (abs(fm) < 1e-10) return mid
            if (fa * fm <= 0.0) b = mid else { a = mid; fa = fm }
        }
        return (a + b) / 2.0
    }

    fun integrateSimpson(function: (Double) -> Double, from: Double, to: Double, intervals: Int = 200): Double {
        val n = intervals.coerceIn(2, 10000).let { if (it % 2 == 0) it else it + 1 }
        val h = (to - from) / n
        var sum = clean(function(from)) + clean(function(to))
        for (i in 1 until n) sum += (if (i % 2 == 0) 2.0 else 4.0) * clean(function(from + h * i))
        return sum * h / 3.0
    }

    fun metrics(candles: List<DoubleArray>): Metrics? {
        val valid = candles.filter { it.size >= 5 && it[3].isFinite() && it[3] > 0.0 && it[4].isFinite() && it[4] >= 0.0 }
        if (valid.size < 40) return null
        val closes = valid.map { it[3] }
        val volumes = valid.map { it[4] }
        val returns = closes.zipWithNext { a, b -> ln(b / a) }.filter { it.isFinite() }
        if (returns.size < 30) return null
        val mu = mean(returns)
        val sd = sqrt(variance(returns)).coerceAtLeast(1e-12)
        val downside = returns.filter { it < 0.0 }
        val downsideSd = if (downside.size < 2) 0.0 else sqrt(variance(downside))
        val skew = returns.sumOf { ((it - mu) / sd).pow(3) } / returns.size
        val kurt = returns.sumOf { ((it - mu) / sd).pow(4) } / returns.size - 3.0
        val sorted = returns.sorted()
        val var95 = quantile(sorted, 0.05)
        val tail = sorted.filter { it <= var95 }
        val cvar = if (tail.isEmpty()) var95 else mean(tail)
        var peak = closes.first()
        var maxDd = 0.0
        for (p in closes) { peak = max(peak, p); maxDd = max(maxDd, (peak - p) / peak) }
        val bins = IntArray(10)
        for (r in returns) {
            val index = (((r - (mu - 3 * sd)) / (6 * sd)) * 10).toInt().coerceIn(0, 9)
            bins[index]++
        }
        val entropy = bins.filter { it > 0 }.sumOf { count ->
            val p = count.toDouble() / returns.size
            -p * ln(p)
        } / ln(10.0)
        val (slope, r2) = linearRegression(closes.takeLast(80).map { ln(it) })
        val volumeChanges = volumes.zipWithNext { a, b -> ln((b + 1.0) / (a + 1.0)) }
        val volumeReturnCorr = correlation(returns, volumeChanges)
        // Deterministic Monte Carlo bootstrap: reproducible risk estimate, not a price forecast.
        val rng = Random(returns.size * 31 + closes.last().toBits().toInt())
        var losses = 0
        val horizon = min(24, returns.size)
        repeat(600) {
            var compounded = 1.0
            repeat(horizon) { compounded *= exp(returns[rng.nextInt(returns.size)]) }
            if (compounded < 1.0) losses++
        }
        return Metrics(returns.size, mu, sd, downsideSd, skew, kurt, var95, cvar,
            maxDd, entropy.coerceIn(0.0, 1.0), slope, r2, volumeReturnCorr, losses / 600.0)
    }

    fun apply(signal: MarketSignal): MarketSignal {
        val m = metrics(signal.candles) ?: return signal.copy(
            reason = signal.reason + " | ریاضیات کمی: داده کافی برای برآورد آماری معتبر موجود نیست"
        )
        if (signal.direction != "LONG_BIAS" && signal.direction != "SHORT_BIAS") return signal
        var penalty = 0
        val flags = mutableListOf<String>()
        if (m.volatility > 0.045) { penalty += 8; flags += "نوسان تحقق‌یافته بسیار بالا" }
        if (m.maxDrawdown > 0.12) { penalty += 8; flags += "افت بیشینه قیمت در پنجره زیاد است" }
        if (m.cvar95 < -0.035) { penalty += 8; flags += "دم زیان CVaR سنگین است" }
        if (m.monteCarloLossProbability > 0.62) { penalty += 7; flags += "بوت‌استرپ تاریخی احتمال مسیر زیان را بالا نشان می‌دهد" }
        if (m.returnEntropy > 0.92) { penalty += 4; flags += "آنتروپی بازده بالاست و جهت‌پذیری پایین است" }
        val slopeAgainst = (signal.direction == "LONG_BIAS" && m.trendSlope < 0.0) ||
            (signal.direction == "SHORT_BIAS" && m.trendSlope > 0.0)
        if (slopeAgainst && m.trendR2 > 0.35) { penalty += 7; flags += "رگرسیون لگاریتم قیمت خلاف جهت سیگنال است" }
        val veto = penalty >= 24
        val note = " | موتور ریاضی: n=${m.observations}، σ=${"%.3f".format(m.volatility)}، VaR95=${"%.2f".format(m.var95 * 100)}٪، CVaR95=${"%.2f".format(m.cvar95 * 100)}٪، افت بیشینه=${"%.2f".format(m.maxDrawdown * 100)}٪، آنتروپی=${"%.2f".format(m.returnEntropy)}، احتمال زیان بوت‌استرپ=${"%.1f".format(m.monteCarloLossProbability * 100)}٪"
        val caution = if (flags.isEmpty()) "؛ هشدار آماری شدید شناسایی نشد" else "؛ هشدارها: " + flags.joinToString("، ")
        return signal.copy(
            direction = if (veto) "NEUTRAL" else signal.direction,
            confidence = (signal.confidence - penalty.coerceAtMost(20)).coerceIn(0, 95),
            score = if (veto) 0 else (signal.score - penalty / 2).coerceIn(-100, 100),
            reason = signal.reason + note + caution
        )
    }
}
