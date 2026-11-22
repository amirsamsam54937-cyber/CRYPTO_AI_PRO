package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Multi-horizon scenario engine. Its scores are evidence-strength indices, NOT
 * calibrated probabilities or promises of profitability. It never authorizes orders.
 *
 * Primary candles use [open, high, low, close, baseVolume, openTimeMs] and are 15m bars.
 */
object ZoroMultiHorizonEngine {
    data class Horizon(
        val label: String,
        val bars: Int,
        val direction: String,
        val evidenceScore: Int,
        val projectedDriftPct: Double,
        val volatilityBandPct: Double,
        val explanation: String
    )

    data class Outlook(
        val available: Boolean,
        val dataQuality: Int,
        val trendState: String,
        val regime: String,
        val flowState: String,
        val truthStatus: String,
        val warnings: List<String>,
        val horizons: List<Horizon>,
        val neuralSixHourUsed: Boolean
    ) {
        companion object {
            fun unavailable(reason: String) = Outlook(
                false, 0, "نامشخص", "نامشخص", "بدون داده", reason,
                listOf(reason), emptyList(), false
            )
        }
    }

    private const val BAR_MS = 15L * 60L * 1000L
    private data class Regression(val slope: Double, val r2: Double)

    fun analyze(signal: MarketSignal, nowMs: Long = System.currentTimeMillis()): Outlook {
        // The scenario engine is calibrated to 15m bars; signal.candles is the 5m execution chart.
        val scenarioSource = signal.scenarioCandles.ifEmpty { signal.candles }
        val valid = scenarioSource.asSequence()
            .filter { row ->
                row.size >= 6 && row.take(6).all { it.isFinite() } &&
                    row[0] > 0.0 && row[1] >= maxOf(row[0], row[3]) &&
                    row[2] <= minOf(row[0], row[3]) && row[2] > 0.0 &&
                    row[4] >= 0.0 && row[5] > 0.0 &&
                    row[5] == kotlin.math.floor(row[5])
            }
            .sortedBy { it[5] }
            .distinctBy { it[5].toLong() }
            .filter { it[5] + BAR_MS <= nowMs.toDouble() }
            .toList()

        if (valid.size < 32) return Outlook.unavailable(
            "برای پیش‌بینی چندافقی حداقل ۳۲ کندل بسته و معتبر ۱۵دقیقه‌ای لازم است"
        )

        val gaps = valid.zipWithNext().count { (a, b) ->
            b[5] - a[5] != BAR_MS.toDouble()
        }
        val continuity = (1.0 - gaps.toDouble() / (valid.size - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val countQuality = (valid.size / 80.0).coerceIn(0.0, 1.0)
        val dataQuality = ((0.65 * continuity + 0.35 * countQuality) * 100.0).toInt().coerceIn(0, 100)
        val closes = valid.map { it[3] }
        val regression = regression(closes.takeLast(48))
        val atrPct = signal.technical.atrPercent.takeIf { it.isFinite() && it > 0.0 }
            ?: fallbackAtrPct(valid)
        if (!atrPct.isFinite() || atrPct <= 0.0) {
            return Outlook.unavailable("محاسبه نوسان معتبر نیست؛ خروجی پیش‌بینی متوقف شد")
        }

        val technical = signal.technical
        val emaSigns = listOf(
            sign(technical.ema9 - technical.ema20),
            sign(technical.ema20 - technical.ema50),
            sign(technical.ema50 - technical.ema200)
        )
        val emaAlignment = emaSigns.average()
        val trendState = when {
            emaAlignment >= 0.66 -> "روند صعودی هم‌راستا"
            emaAlignment <= -0.66 -> "روند نزولی هم‌راستا"
            abs(emaAlignment) < 0.34 -> "روند خنثی/ترکیبی"
            else -> "روند چندگانه و متناقض"
        }
        val regime = when {
            atrPct >= 5.0 -> "نوسان بسیار بالا"
            atrPct >= 2.5 -> "نوسان بالا"
            technical.adx >= 25.0 -> "رونددار"
            technical.adx < 16.0 -> "کم‌روند/رنج"
            else -> "نوسان متعادل"
        }
        val flowState = when {
            !signal.hiddenFlowFresh -> "جریان سفارش تازه در دسترس نیست"
            signal.hiddenFlowScore >= 18 -> "فشار خرید در داده عمومی XT"
            signal.hiddenFlowScore <= -18 -> "فشار فروش در داده عمومی XT"
            else -> "جریان سفارش متعادل"
        }

        val warnings = mutableListOf<String>()
        if (dataQuality < 80) warnings += "پیوستگی یا تعداد کندل‌ها کامل نیست"
        if (gaps > 0) warnings += "$gaps فاصله زمانی در تاریخچه دیده شد"
        if (signal.neuralUncertainty.highDisagreement) warnings += "اختلاف زیاد میان اعضای شبکه عصبی"
        if (!signal.hiddenFlowFresh) warnings += "جریان سفارش قدیمی/ناموجود است و در امتیاز دخالت ندارد"
        if (atrPct >= 5.0) warnings += "نوسان بالا؛ دامنه خطا و ریسک جهش قیمت بیشتر است"
        if (!WolfPretrainedEnsemble.status().validatedForScoring) {
            warnings += "مدل عصبی هنوز اعتبارسنجی مستقل را نگذرانده؛ رأی آن فقط پژوهشی است"
        }

        val macdBias = sign(technical.macdLine - technical.macdSignalLine).toDouble()
        val rsi = signal.rsi.takeIf { it.isFinite() } ?: 50.0
        val rsiBias = ((rsi - 50.0) / 25.0).coerceIn(-1.0, 1.0)
        val volumeConfirmation = (sign(regression.slope) *
            ((technical.relativeVolume - 1.0) / 1.5).coerceIn(-1.0, 1.0))
        val flow = if (signal.hiddenFlowFresh) (signal.hiddenFlowScore / 100.0).coerceIn(-1.0, 1.0) else 0.0
        val neuralValidated = WolfPretrainedEnsemble.status().validatedForScoring &&
            signal.neuralMemberProbabilities.size == 10 &&
            !signal.neuralUncertainty.highDisagreement
        val neuralUp = signal.neuralScore.coerceIn(1, 99) / 100.0
        val horizons = listOf(
            Triple("۱ ساعت", 4, false),
            Triple("۴ ساعت", 16, false),
            Triple("۶ ساعت", 24, true),
            Triple("۲۴ ساعت", 96, false)
        ).map { (label, bars, useNeural) ->
            val volatilityScale = sqrt(bars.toDouble())
            val normalizedTrend = tanh(
                (regression.slope * volatilityScale / (atrPct / 100.0).coerceAtLeast(1e-6)) / 1.8
            )
            // Longer extrapolations are deliberately damped; this is a scenario tilt, not a price target.
            val horizonDamping = when {
                bars <= 4 -> 1.0
                bars <= 16 -> 0.88
                bars <= 24 -> 0.78
                else -> 0.52
            }
            val momentum = normalizedTrend * horizonDamping * (0.65 + 0.35 * regression.r2)
            val rsiContribution = when {
                rsi >= 78.0 -> rsiBias * 0.35
                rsi <= 22.0 -> rsiBias * 0.35
                else -> rsiBias
            }
            var numerator = 0.38 * momentum + 0.25 * emaAlignment +
                0.10 * macdBias + 0.10 * volumeConfirmation +
                0.07 * rsiContribution + 0.10 * flow
            var denominator = 1.0
            val neuralApplied = useNeural && neuralValidated
            if (neuralApplied) {
                val neuralEdge = ((neuralUp - 0.5) * 2.0).coerceIn(-1.0, 1.0)
                numerator += 0.12 * neuralEdge
                denominator += 0.12
            }
            val raw = (numerator / denominator).coerceIn(-1.0, 1.0)
            val score = (50.0 + 50.0 * tanh(raw * 1.55)).toInt().coerceIn(0, 100)
            val direction = when {
                score >= 58 -> "LONG_BIAS"
                score <= 42 -> "SHORT_BIAS"
                else -> "NEUTRAL"
            }
            val band = (atrPct * volatilityScale * 1.35).coerceIn(0.05, 35.0)
            val rawDrift = regression.slope * bars * 100.0
            val drift = rawDrift.coerceIn(-band * 1.25, band * 1.25)
            val explanation = when {
                direction == "NEUTRAL" -> "شواهد جهت‌دار کافی نیست؛ عدم ورود ارجح است"
                direction == "LONG_BIAS" && emaAlignment > 0.0 -> "مومنتوم و ساختار میانگین‌ها متمایل به صعودند"
                direction == "SHORT_BIAS" && emaAlignment < 0.0 -> "مومنتوم و ساختار میانگین‌ها متمایل به نزولند"
                else -> "مومنتوم با ساختار روند کاملاً هم‌جهت نیست؛ احتیاط"
            }
            Horizon(label, bars, direction, score, drift, band, explanation)
        }

        val status = when {
            dataQuality < 55 -> "کیفیت داده پایین؛ پیش‌بینی صرفاً نمایشی"
            warnings.any { it.contains("اختلاف زیاد") } -> "Truth Engine: اختلاف مدل‌ها؛ احتیاط بالا"
            warnings.any { it.contains("فاصله زمانی") } -> "Truth Engine: تاریخچه دارای گسست است"
            else -> "Truth Engine: شواهد، کیفیت داده و محدودیت مدل بررسی شد"
        }
        return Outlook(
            true, dataQuality, trendState, regime, flowState, status,
            warnings.distinct(), horizons, neuralValidated
        )
    }

    private fun regression(values: List<Double>): Regression {
        if (values.size < 3 || values.any { !it.isFinite() || it <= 0.0 }) return Regression(0.0, 0.0)
        val y = values.map(::ln)
        val meanX = (y.size - 1) / 2.0
        val meanY = y.average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (i in y.indices) {
            val dx = i - meanX
            covariance += dx * (y[i] - meanY)
            varianceX += dx * dx
            varianceY += (y[i] - meanY) * (y[i] - meanY)
        }
        val slope = covariance / varianceX.coerceAtLeast(1e-9)
        val r2 = if (varianceY <= 1e-12) 0.0
            else ((covariance * covariance) / (varianceX * varianceY)).coerceIn(0.0, 1.0)
        return Regression(slope, r2)
    }

    private fun fallbackAtrPct(candles: List<DoubleArray>): Double {
        val sample = candles.takeLast(15)
        if (sample.size < 2) return Double.NaN
        val ranges = sample.mapIndexed { i, row ->
            val previousClose = sample.getOrNull(i - 1)?.get(3) ?: row[3]
            maxOf(row[1] - row[2], abs(row[1] - previousClose), abs(row[2] - previousClose))
        }
        return ranges.average() / sample.last()[3].coerceAtLeast(1e-9) * 100.0
    }

    private fun sign(value: Double): Int = when {
        !value.isFinite() || abs(value) < 1e-12 -> 0
        value > 0.0 -> 1
        else -> -1
    }
}
