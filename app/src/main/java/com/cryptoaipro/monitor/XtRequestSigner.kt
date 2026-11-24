package com.cryptoaipro.monitor

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * XT API signature canonicalization.
 * X = sorted authentication headers; Y = #METHOD#PATH#QUERY#BODY.
 * The exact query/body bytes used here must also be sent on the wire.
 */
internal object XtRequestSigner {
    fun canonicalQuery(values: Map<String, String>): String =
        values.toSortedMap().entries.joinToString("&") { (key, value) ->
            java.net.URLEncoder.encode(key, "UTF-8") + "=" +
                java.net.URLEncoder.encode(value, "UTF-8")
        }

    fun signingPayload(
        method: String,
        appKey: String,
        timestampMs: String,
        recvWindow: String,
        endpoint: String,
        queryString: String = "",
        exactBody: String = ""
    ): String {
        require(method in setOf("GET", "POST", "PUT", "DELETE"))
        require(appKey.isNotBlank() && timestampMs.matches(Regex("[0-9]{13}")))
        require(recvWindow.toLongOrNull() in 1L..60000L)
        require(endpoint.startsWith("/future/") && !endpoint.contains(".."))
        val headers = sortedMapOf(
            "validate-algorithms" to "HmacSHA256",
            "validate-appkey" to appKey,
            "validate-recvwindow" to recvWindow,
            "validate-timestamp" to timestampMs
        )
        val headerPart = headers.entries.joinToString("&") { "${it.key}=${it.value}" }
        val requestPart = buildString {
            append("#").append(method).append("#").append(endpoint)
            if (queryString.isNotEmpty()) append("#").append(queryString)
            if (exactBody.isNotEmpty()) append("#").append(exactBody)
        }
        return headerPart + requestPart
    }

    fun sign(secret: String, payload: String): String {
        require(secret.isNotBlank())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
