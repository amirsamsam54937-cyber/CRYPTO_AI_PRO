package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfMarketFingerprintEngineTest {
    @Test fun insufficientHistoryFailsClosed() {
        val result = WolfMarketFingerprintEngine.analyze(emptyList())
        assertFalse(result.available)
        assertTrue(result.blockLong)
        assertTrue(result.blockShort)
        assertTrue(result.decision.contains("عدم معامله"))
    }

    @Test fun validClosedCandlesProduceAnEvidenceBoundFingerprint() {
        val candles = (0 until 120).map { i ->
            val base = 100.0 + i * 0.08
            val open = base - 0.025
            val close = base + 0.025
            doubleArrayOf(open, close + 0.04, open - 0.04, close, 1000.0 + (i % 7) * 80.0, (i + 1) * 60_000.0)
        }
        val result = WolfMarketFingerprintEngine.analyze(candles)
        assertTrue(result.available)
        assertTrue(result.identity.isNotBlank())
        assertTrue(result.decision.isNotBlank())
        assertTrue(result.warnings.any { it.contains("اثبات") })
        assertFalse(result.regime == WolfMarketFingerprintEngine.Regime.UNCERTAIN &&
            result.deception == WolfMarketFingerprintEngine.Deception.INSUFFICIENT_EVIDENCE)
    }

    @Test fun trapEvidenceNeverClaimsConfirmedManipulation() {
        val candles = (0 until 100).map { i ->
            val base = 100.0 + (i % 12) * 0.015
            val open = base
            val high = base + 0.08
            val low = base - 0.08
            val close = base + if (i % 2 == 0) 0.012 else -0.012
            doubleArrayOf(open, high, low, close, 900.0, (i + 1) * 60_000.0)
        }
        val result = WolfMarketFingerprintEngine.analyze(candles)
        assertTrue(result.available)
        assertTrue(result.warnings.any { it.contains("قصد دستکاری") })
        assertTrue(result.deception != WolfMarketFingerprintEngine.Deception.INSUFFICIENT_EVIDENCE)
    }
}
