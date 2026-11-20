package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoroTraderEvidenceLedgerTest {
    private fun call(
        id: String,
        result: Double?,
        status: ZoroTraderEvidenceLedger.CallStatus = ZoroTraderEvidenceLedger.CallStatus.TARGET
    ) = ZoroTraderEvidenceLedger.Call(
        id = id,
        traderId = "public-trader",
        sourceUrl = "https://example.com/call/$id",
        publishedAtEpochMs = 1_000L,
        capturedAtEpochMs = 2_000L,
        symbol = "BTCUSDT",
        venue = "public-call",
        timeframe = "1h",
        side = ZoroTraderEvidenceLedger.Side.LONG,
        entry = 100.0,
        stop = 98.0,
        target = 104.0,
        status = status,
        netR = result,
        evidence = ZoroTraderEvidenceLedger.EvidenceGrade.C
    )

    @Test fun validTimestampedPublicCallPassesAudit() {
        assertTrue(ZoroTraderEvidenceLedger.audit(call("a", 2.0)).valid)
    }

    @Test fun invalidSourceAndReversedTimestampsAreRejected() {
        val invalid = call("b", 2.0).copy(
            sourceUrl = "not-a-url",
            publishedAtEpochMs = 3_000L,
            capturedAtEpochMs = 2_000L
        )
        val audit = ZoroTraderEvidenceLedger.audit(invalid)
        assertFalse(audit.valid)
        assertEquals(2, audit.errors.size)
    }

    @Test fun closedCallWithoutResultIsFlagged() {
        val audit = ZoroTraderEvidenceLedger.audit(call("c", null))
        assertFalse(audit.valid)
        assertTrue(audit.errors.any { it.contains("نتیجه") })
    }

    @Test fun metricsKeepMissingOutcomesOutOfWinRate() {
        val metrics = ZoroTraderEvidenceLedger.metrics(
            listOf(call("1", 2.0), call("2", -1.0), call("3", null,
                ZoroTraderEvidenceLedger.CallStatus.OPEN))
        )
        assertEquals(2, metrics.resolvedCount)
        assertEquals(1, metrics.wins)
        assertEquals(1, metrics.losses)
        assertEquals(50.0, metrics.winRatePercent!!, 1e-9)
        assertEquals(0.5, metrics.expectancyR!!, 1e-9)
        assertEquals(2.0, metrics.profitFactor!!, 1e-9)
        assertEquals(1, metrics.missingResultCount)
    }

    @Test fun noLossesDoesNotPretendProfitFactorIsFinite() {
        val metrics = ZoroTraderEvidenceLedger.metrics(listOf(call("1", 1.0)))
        assertTrue(metrics.profitFactor!!.isInfinite())
    }

    @Test fun smallSamplesNeverReceiveResearchApproval() {
        val metrics = ZoroTraderEvidenceLedger.metrics(listOf(call("1", 1.0), call("2", 1.0)))
        assertEquals("INSUFFICIENT_SAMPLE", ZoroTraderEvidenceLedger.screeningLabel(metrics))
    }
}
