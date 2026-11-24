package com.cryptoaipro.monitor

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * XT USDT-M Futures v1 signature format.
 * fixed_header = validate-appkey=...&validate-timestamp=...
 * unencrypted = fixed_header#endpoint#sorted-query OR fixed_header#endpoint#exact-json-body
 *
 * Do not use the separate generic XT v4 signing format for fapi.xt.com Futures v1.
 */
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
        exactBody: String = ""
    ): String {
        require(appKey.isNotBlank() && timestampMs.matches(Regex("[0-9]{13}")))
        require(endpoint.startsWith("/future/") && !endpoint.contains(".."))
        require(queryString.isEmpty() || exactBody.isEmpty()) {
            "XT Futures v1 request must sign query parameters or body, not both."
        }
        val fixedHeader = "validate-appkey=$appKey&validate-timestamp=$timestampMs"
        val requestData = when {
            queryString.isNotEmpty() -> "$endpoint#$queryString"
            exactBody.isNotEmpty() -> "$endpoint#$exactBody"
            else -> endpoint
        }
        return "$fixedHeader#$requestData"
    }

    fun sign(secret: String, payload: String): String {
        require(secret.isNotBlank())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
