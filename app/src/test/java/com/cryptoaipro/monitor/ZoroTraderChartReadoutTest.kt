package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoroTraderChartReadoutTest {
    private fun report(bias: String = "تمایل صعودی") =
        ZoroAdvancedTechnicalEngine.Report(
            available = true,
            bias = bias,
            evidenceScore = if (bias.contains("صعودی")) 42 else -38,
            trend = if (bias.contains("صعودی")) "صعودی چندمیانگینی" else "نزولی چندمیانگینی",
            regime = "رونددار",
            structure = "سقف و کف بالاتر",
            momentum = "مومنتوم مثبت",
            volumeState = "حجم بالا و همسو با خریداران",
            smartMoney = "BOS صعودی",
            candlePatterns = listOf("چکش صعودی"),
            chartPatterns = listOf("پرچم صعودی"),
            indicators = listOf(
                ZoroAdvancedTechnicalEngine.Metric("RSI 14", "61", "ناحیه میانی"),
                ZoroAdvancedTechnicalEngine.Metric("MACD 12/26/9", "0.4", "هیستوگرام مثبت")
            ),
            levels = listOf(ZoroAdvancedTechnicalEngine.Level("حمایت", 95.0)),
            higherTimeframe = "۱ساعته: تمایل صعودی (35/100)",
            warnings = listOf("مقاومت نزدیک است"),
            education = emptyList()
        )

    @Test fun buildsConditionalBullishAndBearishScenariosAndExplainsSignalAlignment() {
        val result = ZoroTraderChartReadout.analyze(
            report(), price = 100.0, support = 96.0, resistance = 104.0,
            relativeVolume = 1.7, signalDirection = "LONG_BIAS"
        )
        assertTrue(result.available)
        assertTrue(result.bullishScenario.contains("104"))
        assertTrue(result.bearishScenario.contains("96"))
        assertTrue(result.activeSignalAssessment.contains("هم‌جهت"))
        assertTrue(result.confluences.any { it.contains("مومنتوم") })
        assertTrue(result.patterns.contains("چکش صعودی"))
    }

    @Test fun warnsWhenChartBiasConflictsWithCurrentSignal() {
        val result = ZoroTraderChartReadout.analyze(
            report("تمایل نزولی"), price = 100.0, support = 96.0, resistance = 104.0,
            relativeVolume = 0.8, signalDirection = "LONG_BIAS"
        )
        assertTrue(result.activeSignalAssessment.contains("هم‌جهت نیست"))
        assertTrue(result.cautions.any { it.contains("حجم") })
    }

    @Test fun neutralSignalIsPresentedAsWaitNotForcedTrade() {
        val result = ZoroTraderChartReadout.analyze(
            report("خنثی/متناقض"), price = 100.0, support = 96.0, resistance = 104.0,
            relativeVolume = 1.0, signalDirection = "NEUTRAL"
        )
        assertEquals("خروجی فعلی خنثی است؛ تا شکل‌گیری شکست معتبر یا هم‌جهتی شواهد، ورود نکردن سناریوی پایه است.", result.activeSignalAssessment)
    }
}
