package com.cryptoaipro.monitor

import java.util.Locale

/**
 * Converts measured OHLCV evidence into a trader-style chart briefing.
 * It explains conditional scenarios; it does not forecast certainty or authorize orders.
 */
object ZoroTraderChartReadout {
    data class Readout(
        val available: Boolean,
        val marketSummary: String,
        val structureStory: String,
        val bullishScenario: String,
        val bearishScenario: String,
        val activeSignalAssessment: String,
        val confluences: List<String>,
        val cautions: List<String>,
        val patterns: List<String>
    )

    fun analyze(
        report: ZoroAdvancedTechnicalEngine.Report,
        price: Double,
        support: Double,
        resistance: Double,
        relativeVolume: Double,
        signalDirection: String
    ): Readout {
        if (!report.available || !price.isFinite() || price <= 0.0) {
            return Readout(
                false, "تحلیل کامل در دسترس نیست",
                "تعداد یا کیفیت کندل‌های معتبر برای تحلیل ساختار بازار کافی نیست.",
                "سناریوی صعودی تا دریافت داده معتبر تعریف نمی‌شود.",
                "سناریوی نزولی تا دریافت داده معتبر تعریف نمی‌شود.",
                "بدون تحلیل معتبر، سیگنال چارت قابل اتکا نیست.",
                emptyList(), report.warnings, emptyList()
            )
        }

        fun priceText(value: Double): String =
            if (value.isFinite() && value > 0.0) String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
            else "نامشخص"

        val bullish = report.bias.contains("صعودی")
        val bearish = report.bias.contains("نزولی")
        val marketSummary = when {
            bullish -> "تمایل کلی بازار صعودی است؛ این تمایل فقط با حفظ ساختار و تأیید قیمت معتبر می‌ماند."
            bearish -> "تمایل کلی بازار نزولی است؛ ادامه آن به حفظ سقف‌ها/کف‌های نزولی و تأیید قیمت وابسته است."
            else -> "بازار فعلاً خنثی یا متناقض است؛ معامله در میانه محدوده، نسبت ریسک به بازده ضعیفی دارد."
        } + " رژیم فعلی: ${report.regime}."

        val structureStory = buildString {
            append(report.structure)
            if (report.smartMoney.isNotBlank()) append("؛ شواهد ساختار/نقدینگی: ").append(report.smartMoney)
            if (report.higherTimeframe.isNotBlank()) append("؛ زمینه تایم‌فریم بالاتر: ").append(report.higherTimeframe)
        }

        val bullishScenario = when {
            resistance.isFinite() && resistance > price ->
                "سناریوی صعودی: ابتدا بسته‌شدن کندل بالای مقاومت ${priceText(resistance)}؛ سپس حفظ سطح در پولبک. حجم نسبی فعلی ${String.format(Locale.US, "%.2f", relativeVolume)}x است؛ شکست کم‌حجم را تأییدشده تلقی نکن."
            else ->
                "سناریوی صعودی: شکست سقف نوسانی اخیر و تثبیت بالای آن همراه با افزایش حجم؛ سطح مقاومت معتبر در داده فعلی مشخص نیست."
        }

        val bearishScenario = when {
            support.isFinite() && support > 0.0 && support < price ->
                "سناریوی نزولی: بسته‌شدن کندل زیر حمایت ${priceText(support)} و ناموفق‌بودن بازپس‌گیری سطح؛ شکست بدون افزایش حجم تأیید ضعیف‌تری دارد."
            else ->
                "سناریوی نزولی: از دست‌رفتن کف نوسانی اخیر و تثبیت زیر آن؛ سطح حمایت معتبر در داده فعلی مشخص نیست."
        }

        val activeSignalAssessment = when (signalDirection) {
            "LONG_BIAS" -> if (bullish) "سیگنال LONG با سوگیری کلی چارت هم‌جهت است؛ برای ورود، واکنش قیمت به مقاومت/حمایت و بسته‌شدن کندل را بررسی کن."
                else "سیگنال LONG با سوگیری کلی چارت هم‌جهت نیست؛ این تعارض ریسک شکست سناریو را بالا می‌برد."
            "SHORT_BIAS" -> if (bearish) "سیگنال SHORT با سوگیری کلی چارت هم‌جهت است؛ شکست حمایت و پولبک ناموفق را بررسی کن."
                else "سیگنال SHORT با سوگیری کلی چارت هم‌جهت نیست؛ این تعارض ریسک شکست سناریو را بالا می‌برد."
            else -> "خروجی فعلی خنثی است؛ تا شکل‌گیری شکست معتبر یا هم‌جهتی شواهد، ورود نکردن سناریوی پایه است."
        }

        val confluences = listOf(
            "روند: ${report.trend}",
            "مومنتوم: ${report.momentum}",
            "حجم: ${report.volumeState}",
            "ساختار: ${report.structure}",
            "سوگیری تکنیکال: ${report.bias} • شاخص شواهد ${report.evidenceScore}/100"
        ) + report.indicators.filter { it.name in setOf("RSI 14", "MACD 12/26/9", "ADX 14", "Relative Volume") }
            .map { "${it.name}: ${it.value} — ${it.interpretation}" }

        val cautions = (report.warnings + listOfNotNull(
            if (relativeVolume.isFinite() && relativeVolume < 1.0) "حجم فعلی زیر میانگین است؛ شکست‌ها نیازمند تأیید بیشترند." else null,
            if (report.candlePatterns.isEmpty()) "الگوی کندلی تأییدشده‌ای در پنجره اخیر دیده نشد." else null,
            if (report.higherTimeframe.contains("متناقض") || report.higherTimeframe.contains("نزولی") && bullish || report.higherTimeframe.contains("صعودی") && bearish)
                "تعارض تایم‌فریم بالاتر با سوگیری فعلی را جدی بگیر." else null
        )).distinct()

        val patterns = (report.candlePatterns + report.chartPatterns).distinct().ifEmpty {
            listOf("الگوی کلاسیک پرقدرت در پنجره بررسی‌شده تأیید نشد")
        }

        return Readout(
            true, marketSummary, structureStory, bullishScenario, bearishScenario,
            activeSignalAssessment, confluences, cautions, patterns
        )
    }
}
