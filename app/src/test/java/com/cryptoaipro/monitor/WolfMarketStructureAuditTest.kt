package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class WolfMarketStructureAuditTest {
    private fun candles(closes: List<Double>): List<DoubleArray> =
        closes.mapIndexed { i, close ->
            val open = if (i == 0) close else closes[i - 1]
            doubleArrayOf(open, maxOf(open, close) + 0.2, minOf(open, close) - 0.2, close, 100.0, i.toDouble())
        }

    @Test fun insufficientHistoryFailsClosed() {
        val report = WolfMarketStructureAudit.analyze(candles(listOf(10.0, 11.0, 10.0)))
        assertFalse(report.available)
        assertEquals("نامشخص", report.bias)
    }

    @Test fun outputIsDescriptiveAndContainsNoSignalAuthorization() {
        val series = listOf(10.0, 11.0, 12.0, 11.0, 10.0, 11.0, 12.0, 11.0, 10.0, 11.0, 12.0, 13.0)
        val report = WolfMarketStructureAudit.analyze(candles(series), leftBars = 1, rightBars = 1, maxBars = 30)
        assertTrue(report.available)
        assertTrue(report.pivotCount > 0)
        assertTrue(report.explanation.contains("توصیفی"))
    }

    @Test fun malformedCandlesAreIgnored() {
        val input = candles(List(20) { if (it % 2 == 0) 10.0 else 12.0 }).toMutableList()
        input.add(doubleArrayOf(Double.NaN, 1.0, 1.0, 1.0, 1.0, 21.0))
        val report = WolfMarketStructureAudit.analyze(input, leftBars = 1, rightBars = 1, maxBars = 30)
        assertTrue(report.available)
    }
}
