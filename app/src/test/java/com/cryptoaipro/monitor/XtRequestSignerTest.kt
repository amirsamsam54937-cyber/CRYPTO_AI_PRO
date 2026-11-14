package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class XtRequestSignerTest {
    @Test fun emptyGetQueryRetainsDocumentedThirdCanonicalSegment() {
        assertEquals(
            "validate-appkey=test-key&validate-timestamp=1700000000000#/future/user/v1/account/info#",
            XtRequestSigner.signingPayload("test-key", "1700000000000", "/future/user/v1/account/info")
        )
    }

    @Test fun queryKeysAreSortedBeforeSigning() {
        assertEquals(
            "a=first&z=last",
            XtRequestSigner.canonicalQuery(mapOf("z" to "last", "a" to "first"))
        )
    }

    @Test fun hmacSha256MatchesKnownDeterministicVector() {
        val payload = XtRequestSigner.signingPayload(
            "test-key", "1700000000000", "/future/user/v1/account/info"
        )
        assertEquals(
            "323aee7a4e0a1c5ba926b445a0fb24301573bc2110f2023fca43750dd1986486",
            XtRequestSigner.sign("test-secret", payload)
        )
    }

    @Test fun exactJsonBodyIsSignedWithoutReSerialization() {
        val body = """{"symbol":"btc_usdt","leverage":2}"""
        assertEquals(
            "validate-appkey=k&validate-timestamp=1700000000000#/future/user/v1/position/adjust-leverage#$body",
            XtRequestSigner.signingPayload("k", "1700000000000",
                "/future/user/v1/position/adjust-leverage", exactJsonBody = body)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun queryAndBodyCannotBothBeSigned() {
        XtRequestSigner.signingPayload("k", "1700000000000",
            "/future/user/v1/account/info", queryString = "symbol=btc_usdt", exactJsonBody = "{}")
    }
}
