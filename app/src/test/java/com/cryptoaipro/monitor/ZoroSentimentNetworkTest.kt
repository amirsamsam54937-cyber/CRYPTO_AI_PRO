package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoroSentimentNetworkTest {
    @Test fun positiveAndNegativeHeadlinesHaveExpectedDirection() {
        assertTrue(ZoroSentimentNetwork.analyzeText("Bitcoin ETF approval drives adoption and inflows") > 0)
        assertTrue(ZoroSentimentNetwork.analyzeText("Crypto exchange suffers hack and bankruptcy") < 0)
    }

    @Test fun persianHeadlinesAreScored() {
        assertTrue(ZoroSentimentNetwork.analyzeText("ورود سرمایه و پذیرش بیت کوین باعث رشد بازار شد") > 0)
        assertTrue(ZoroSentimentNetwork.analyzeText("هک صرافی و ریزش سنگین بازار") < 0)
    }

    @Test fun sentimentNetworkBuildsAssetNodesAndPropagationEdges() {
        val articles = listOf(
            MarketNewsItem("Bitcoin ETF approval boosts adoption", "Source A", "", "", "Institutional inflows rise"),
            MarketNewsItem("Crypto market rally continues", "Source B", "", "", "Digital assets surge")
        )
        val snapshot = ZoroSentimentNetwork.build(
            symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"),
            articles = articles,
            flows = mapOf("BTCUSDT" to (0.4 to 0.0001)),
            fearGreed = 72,
            now = 1_800_000_000_000L
        )
        assertEquals(3, snapshot.nodes.size)
        assertTrue(snapshot.edges.any { it.from == "BTC" && it.to == "ETH" })
        assertTrue(snapshot.edges.any { it.from == "ETH" && it.to == "SOL" })
        assertEquals(72, snapshot.fearGreed)
        assertTrue(snapshot.sourceCount >= 2)
    }

    @Test fun emptyEvidenceDoesNotInventConfidence() {
        val snapshot = ZoroSentimentNetwork.build(
            symbols = listOf("ADAUSDT"),
            articles = emptyList(),
            flows = emptyMap(),
            fearGreed = null
        )
        assertEquals(0, snapshot.marketScore)
        assertEquals(12, snapshot.nodes.single().confidence)
        assertEquals(0, snapshot.nodes.single().evidenceCount)
    }
}
