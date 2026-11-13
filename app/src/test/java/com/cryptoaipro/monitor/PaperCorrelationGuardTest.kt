package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp

class PaperCorrelationGuardTest {
    private fun candles(inverse: Boolean = false): List<DoubleArray> = (0 until 80).map { i ->
        val logReturn = 0.01 * i + 0.002 * kotlin.math.sin(i.toDouble())
        val close = if (inverse) 200.0 * exp(-logReturn) else 100.0 * exp(logReturn)
        doubleArrayOf(close, close * 1.01, close * 0.99, close, 1000.0, i * 900_000.0)
    }

    @Test fun alignedReturnsDetectStrongPositiveCorrelation() {
        val correlation = HopePaperTradingEngine.returnCorrelation(candles(), candles())
        assertNotNull(correlation)
        assertTrue(correlation!! > 0.999)
    }

    @Test fun alignedReturnsDetectStrongNegativeCorrelation() {
        val correlation = HopePaperTradingEngine.returnCorrelation(candles(), candles(inverse = true))
        assertNotNull(correlation)
        assertTrue(correlation!! < -0.999)
    }

    @Test fun mismatchedCandleIntervalsFailClosedInsteadOfComparingMultiBarReturns() {
        val everyOther = candles().filterIndexed { index, _ -> index % 2 == 0 }
        assertNull(HopePaperTradingEngine.returnCorrelation(everyOther, everyOther))
    }

    @Test fun insufficientAlignedHistoryFailsClosed() {
        val short = candles().take(12)
        assertNull(HopePaperTradingEngine.returnCorrelation(short, short))
    }

    @Test fun flatSeriesDoesNotProduceNanCorrelation() {
        val flat = (0 until 40).map { i ->
            doubleArrayOf(100.0, 101.0, 99.0, 100.0, 1000.0, i * 900_000.0)
        }
        assertNull(HopePaperTradingEngine.returnCorrelation(flat, flat))
    }
}
