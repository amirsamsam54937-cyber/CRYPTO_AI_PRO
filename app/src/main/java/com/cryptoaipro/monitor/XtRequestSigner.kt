package com.cryptoaipro.monitor

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** XT Futures request canonicalization from the official SignatureGeneration guide. */
internal object XtRequestSigner {
    fun canonicalQuery(values: Map<String, String>): String =
        values.toSortedMap().entries.joinToString("&") { (key, value) ->
            java.net.URLEncoder.encode(key, "UTF-8") + "=" +
                java.net.URLEncoder.encode(value, "UTF-8")
        }

    fun signingPayload(
        appKey: String,
        timestampMs: String,
        endpoint: String,
        queryString: String = "",
        exactJsonBody: String? = null
    ): String {
        require(appKey.isNotBlank() && timestampMs.matches(Regex("[0-9]{13}")))
        require(endpoint.startsWith("/future/") && !endpoint.contains(".."))
        require(exactJsonBody == null || queryString.isEmpty()) {
            "XT signs either query parameters or the exact request body, never both."
        }
        val fixed = "validate-appkey=$appKey&validate-timestamp=$timestampMs"
        val signedPart = exactJsonBody ?: queryString
        return "$fixed#$endpoint#$signedPart"
    }

    fun sign(secret: String, payload: String): String {
        require(secret.isNotBlank())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
