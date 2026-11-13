package com.cryptoaipro.monitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated XT USDT-M futures transport.
 *
 * Credentials are encrypted with an Android Keystore AES-GCM key and are never logged.
 * This class intentionally exposes narrowly-scoped trading operations; it does not
 * contain withdrawal, transfer, or API-key-management methods.
 */
/**
 * XT Futures v1 private API client.
 * Signature canonicalization follows XT Futures "Obtain Signature" documentation:
 * validate-appkey=...&validate-timestamp=...#/endpoint#sorted-query-or-exact-json-body
 */
internal class XtAuthenticatedFuturesClient(
    private val context: Context
) {
    private val baseUrl = "https://fapi.xt.com"
    private val entryMutex = Mutex()\n\n    private fun normalizeSymbol(value: String): String {\n        val compact = value.lowercase().filter(Char::isLetterOrDigit)\n        require(compact.endsWith("usdt") && compact.length > 4) { "نماد قرارداد USDT نامعتبر است." }\n        return compact.dropLast(4) + "_usdt"\n    }

    private fun signature(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonicalQuery(values: Map<String, String>): String =
        values.toSortedMap().entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val (appKey, secret) = XtCredentialVault.load(context)
            ?: throw IllegalStateException("اتصال امن XT تنظیم نشده است.")
        require(path.startsWith("/future/") && !path.contains("..")) { "XT endpoint نامعتبر است." }
        val timestamp = System.currentTimeMillis().toString()
        val queryString = canonicalQuery(query)
        val bodyText = body?.toString().orEmpty()
        val signedPart = if (method == "GET") queryString else bodyText
        val fixedHeader = "validate-appkey=$appKey&validate-timestamp=$timestamp"
        val signingText = "$fixedHeader#$path" + if (signedPart.isNotEmpty()) "#$signedPart" else ""
        val sig = signature(secret, signingText)
        val urlText = baseUrl + path + if (method == "GET" && queryString.isNotEmpty()) "?$queryString" else ""
        val connection = (URL(urlText).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 7000
            connection.readTimeout = 9000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", if (method == "GET") "application/x-www-form-urlencoded" else "application/json")
            connection.setRequestProperty("validate-appkey", appKey)
            connection.setRequestProperty("validate-timestamp", timestamp)
            connection.setRequestProperty("validate-signature", sig)
            if (method != "GET") {
                connection.doOutput = true
                connection.outputStream.use { it.write(bodyText.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(raw).optString("msgInfo") }.getOrDefault("")
                throw IllegalStateException("XT HTTP $code" + if (message.isNotBlank()) ": ${message.take(120)}" else "")
            }
            val response = JSONObject(raw)
            val returnCode = response.optInt("returnCode", 0)
            if (returnCode != 0) {
                val error = response.optJSONObject("error")
                throw IllegalStateException("XT خطای API: " + (error?.optString("msg")?.take(140) ?: response.optString("msgInfo", "نامشخص")))
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    suspend fun activePositions(): List<XtLivePosition> {
        val response = request("GET", "/future/trade/v1/position/list/active")
        val result = response.opt("result")
        val rows = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("items") ?: result.optJSONArray("list") ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            fun number(key: String) = row.optString(key).toDoubleOrNull()
                ?: row.optDouble(key).takeIf { it.isFinite() } ?: 0.0
            val symbol = row.optString("symbol").lowercase()
            val side = row.optString("positionSide").uppercase()
            val size = number("positionSize")
            if (symbol.isBlank() || side !in setOf("LONG", "SHORT") || size <= 0.0) null
            else XtLivePosition(
                symbol, side, size, number("availableCloseSize"),
                number("entryPrice"), number("markPrice"),
                row.optInt("leverage", 1).coerceAtLeast(1), number("contractSize")
            )
        }
    }

    data class ContractSpec(
        val symbol: String,
        val contractSize: Double,
        val makerFeeRate: Double,
        val takerFeeRate: Double,
        val minQty: Double,
        val minNotional: Double,
        val pricePrecision: Int,
        val quantityPrecision: Int,
        val openApiEnabled: Boolean,
        val tradingEnabled: Boolean
    )

    suspend fun contractSpec(symbol: String): ContractSpec = withContext(Dispatchers.IO) {
        val normalized = normalizeSymbol(symbol)
        val url = URL("$baseUrl/future/market/v1/public/symbol/detail?symbol=" +
            java.net.URLEncoder.encode(normalized, "UTF-8"))
        val connection = (url.openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "دریافت مشخصات قرارداد XT ناموفق بود: HTTP $code" }
            val response = JSONObject(raw)
            require(response.optInt("returnCode", 0) == 0) { "XT مشخصات این قرارداد را تأیید نکرد." }
            val result = response.optJSONObject("result")
                ?: throw IllegalStateException("مشخصات قرارداد XT ناقص است.")
            fun number(key: String) = result.optString(key).toDoubleOrNull()
                ?: result.optDouble(key).takeIf { it.isFinite() } ?: 0.0
            ContractSpec(
                normalized, number("contractSize"), number("makerFee"), number("takerFee"),
                number("minQty"), number("minNotional"),
                result.optInt("pricePrecision"), result.optInt("quantityPrecision"),
                result.optBoolean("isOpenApi"), result.optBoolean("tradeSwitch")
            ).also {
                require(it.contractSize > 0.0 && it.takerFeeRate in 0.0..0.02 &&
                    it.minQty > 0.0 && it.minNotional >= 0.0 && it.openApiEnabled && it.tradingEnabled) {
                    "قرارداد در XT برای API یا معامله فعال نیست؛ ورود مسدود شد."
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    data class AccountSnapshot(
        val canTrade: Boolean,
        val canOpenPosition: Boolean,
        val walletBalanceUsdt: Double,
        val equityUsdt: Double,
        val availableBalanceUsdt: Double
    )

    suspend fun accountSnapshot(): AccountSnapshot {
        val account = request("GET", "/future/user/v1/account/info").optJSONObject("result")
            ?: throw IllegalStateException("پاسخ اطلاعات حساب XT ناقص است.")
        val balances = request("GET", "/future/user/v1/compat/balance/list").optJSONArray("result")
            ?: throw IllegalStateException("پاسخ موجودی فیوچرز XT ناقص است.")
        val usdt = (0 until balances.length()).mapNotNull { balances.optJSONObject(it) }
            .firstOrNull { it.optString("coin").equals("usdt", true) }
            ?: throw IllegalStateException("موجودی USDT در حساب فیوچرز XT پیدا نشد.")
        fun value(key: String) = usdt.optString(key).toDoubleOrNull()
            ?: usdt.optDouble(key).takeIf { it.isFinite() } ?: 0.0
        val wallet = value("walletBalance")
        val available = (wallet - value("openOrderMarginFrozen") - value("isolatedMargin"))
            .coerceAtLeast(0.0)
        return AccountSnapshot(
            account.optBoolean("allowTrade", false),
            account.optBoolean("allowOpenPosition", false),
            wallet,
            value("totalAmount").takeIf { it > 0.0 } ?: value("amount").takeIf { it > 0.0 } ?: wallet,
            available
        )
    }

    suspend fun setLeverage(symbol: String, side: String, leverage: Int) {
        val normalizedSymbol = symbol.trim().lowercase()
        require(normalizedSymbol.matches(Regex("[a-z0-9]+_usdt"))) { "نماد قرارداد USDT نامعتبر است." }
        require(side == "LONG" || side == "SHORT") { "جهت پوزیشن نامعتبر است." }
        require(leverage in 1..5) { "اهرم خارج از محدوده محافظه‌کارانه ZORO است." }
        val body = JSONObject()
            .put("symbol", normalizedSymbol)
            .put("positionSide", side)
            .put("leverage", leverage)
        request("POST", "/future/user/v1/position/adjust-leverage", body = body)
    }

    data class ContractSizing(
        val contracts: Long,
        val riskBudgetUsdt: Double,
        val estimatedStopLossUsdt: Double,
        val marginRequiredUsdt: Double
    )

    /**
     * Risk-first quantity sizing. A leverage cap limits notional; it never increases
     * the stop-risk budget. Uses XT's integer contract quantity convention.
     */
    fun sizeForRisk(
        equityUsdt: Double,
        availableUsdt: Double,
        entry: Double,
        stop: Double,
        contractSize: Double,
        leverage: Int,
        riskFraction: Double = 0.005,
        marginFraction: Double = 0.10
    ): ContractSizing {
        require(listOf(equityUsdt, availableUsdt, entry, stop, contractSize).all { it.isFinite() && it > 0.0 })
        require(entry != stop && leverage in 1..5)
        require(riskFraction in 0.001..0.01 && marginFraction in 0.02..0.15 &&\n            takerFeeRate in 0.0..0.02 && slippageRate in 0.0..0.02)
        val riskBudget = equityUsdt * riskFraction
        val lossPerContract = kotlin.math.abs(entry - stop) * contractSize +\n            (entry + stop) * contractSize * takerFeeRate +\n            (entry + stop) * contractSize * slippageRate
        val contractsByRisk = riskBudget / lossPerContract
        val maxNotional = minOf(availableUsdt * leverage * marginFraction, equityUsdt * leverage * marginFraction)
        val contractsByMargin = maxNotional / (entry * contractSize)
        val contracts = floorContracts(minOf(contractsByRisk, contractsByMargin))
        require(contracts >= 1) { "با ریسک مجاز و حدضرر فعلی، حجم به حداقل یک قرارداد XT نمی‌رسد." }
        val estimatedLoss = contracts * lossPerContract
        val margin = contracts * entry * contractSize / leverage
        require(estimatedLoss <= riskBudget * 1.001) { "حجم محاسبه‌شده از سقف ریسک عبور می‌کند." }
        require(margin <= availableUsdt * marginFraction * 1.001) { "مارجین موردنیاز از سقف تخصیص عبور می‌کند." }
        return ContractSizing(contracts, riskBudget, estimatedLoss, margin)
    }

    suspend fun activeTriggerPlans(symbol: String): List<JSONObject> {
        val normalized = symbol.trim().lowercase()
        val response = request(
            "GET", "/future/trade/v1/entrust/plan-list",
            mapOf("symbol" to normalized, "state" to "TRIGGERING", "page" to "1", "size" to "100")
        )
        val result = response.optJSONObject("result")
        val items = result?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    suspend fun createProtectionPlan(
        position: XtLivePosition,
        entrustType: String,
        triggerPrice: Double
    ): JSONObject {
        require(entrustType == "STOP_MARKET" || entrustType == "TAKE_PROFIT_MARKET")
        require(triggerPrice.isFinite() && triggerPrice > 0.0)
        require(position.quantity > 0.0)
        val closeSide = if (position.side == "LONG") "SELL" else "BUY"
        val body = JSONObject()
            .put("clientOrderId", "ZORO-GUARD-" + UUID.randomUUID().toString().replace("-", "").take(20))
            .put("symbol", normalizeSymbol(position.symbol))
            .put("orderSide", closeSide)
            .put("entrustType", entrustType)
            .put("origQty", floorContracts(position.quantity))
            .put("stopPrice", BigDecimal.valueOf(triggerPrice).stripTrailingZeros().toPlainString())
            .put("timeInForce", "IOC")
            .put("triggerPriceType", "MARK_PRICE")
            .put("positionSide", position.side)
        return request("POST", "/future/trade/v1/entrust/create-plan", body = body)
    }

    /**
     * Emergency market close for a known position only. Uses XT's reported available
     * close quantity and explicit positionSide; never uses close-all.
     */
    suspend fun emergencyClose(position: XtLivePosition): JSONObject {
        val qty = floorContracts(position.availableCloseQuantity)
        require(qty > 0L) { "هیچ حجم آزادی برای بستن اضطراری پوزیشن وجود ندارد." }
        val body = JSONObject()
            .put("clientOrderId", "ZORO-EXIT-" + UUID.randomUUID().toString().replace("-", "").take(20))
            .put("symbol", position.symbol.lowercase().replace("-", "_"))
            .put("orderSide", if (position.side == "LONG") "SELL" else "BUY")
            .put("orderType", "MARKET")
            .put("origQty", qty)
            .put("positionSide", position.side)
        return request("POST", "/future/trade/v1/order/create", body = body)
    }

    /**
     * Submit a market entry with exchange-native TP/SL trigger prices attached to
     * the opening order request. Quantity is contract count and must be integral.
     * This method refuses entries without both protective prices.
     */
    suspend fun openMarketWithProtection(
        symbol: String,
        side: String,
        contracts: Long,
        leverage: Int,
        entryPrice: Double,
        takeProfit: Double,
        stopLoss: Double
    ): JSONObject = entryMutex.withLock {
        val normalizedSymbol = symbol.trim().lowercase()
        require(normalizedSymbol.matches(Regex("[a-z0-9]+_usdt"))) { "نماد قرارداد USDT نامعتبر است." }
        require(side == "LONG" || side == "SHORT") { "جهت پوزیشن نامعتبر است." }
        require(contracts > 0 && leverage in 1..5) { "حجم یا اهرم خارج از محدوده مجاز است." }
        require(entryPrice.isFinite() && entryPrice > 0.0) { "قیمت ورود نامعتبر است." }
        require(takeProfit.isFinite() && stopLoss.isFinite() && takeProfit > 0.0 && stopLoss > 0.0) {
            "حدسود و حدضرر معتبر و اجباری هستند."
        }
        require(if (side == "LONG") stopLoss < entryPrice && takeProfit > entryPrice else takeProfit < entryPrice && stopLoss > entryPrice) {
            "حدضرر و حدسود باید در سمت صحیح قیمت ورود قرار بگیرند."
        }
        val account = accountSnapshot()
        require(account.canTrade && account.canOpenPosition) {
            "حساب XT مجوز معامله یا بازکردن پوزیشن ندارد؛ سفارش ارسال نشد."
        }
        require(account.availableBalanceUsdt > 0.0) { "موجودی آزاد USDT کافی نیست." }
        val current = activePositions()
        require(current.none { it.symbol.filter(Char::isLetterOrDigit).lowercase() == normalizedSymbol.filter(Char::isLetterOrDigit) && it.side == side }) {
            "برای این نماد و جهت، پوزیشن باز وجود دارد؛ ورود تکراری مسدود شد."
        }
        setLeverage(normalizedSymbol, side, leverage)
        val body = JSONObject()
            .put("clientOrderId", "ZORO-" + UUID.randomUUID().toString().replace("-", "").take(24))
            .put("symbol", normalizedSymbol)
            .put("orderSide", if (side == "LONG") "BUY" else "SELL")
            .put("orderType", "MARKET")
            .put("origQty", contracts)
            .put("positionSide", side)
            .put("triggerProfitPrice", BigDecimal.valueOf(takeProfit).stripTrailingZeros().toPlainString())
            .put("triggerStopPrice", BigDecimal.valueOf(stopLoss).stripTrailingZeros().toPlainString())
        request("POST", "/future/trade/v1/order/create", body = body)
    }

    companion object {
        /** Floors quantity to XT's integer contract unit; never rounds risk upward. */
        fun floorContracts(rawContracts: Double): Long {
            require(rawContracts.isFinite() && rawContracts > 0.0) { "حجم قرارداد محاسبه‌شده نامعتبر است." }
            return BigDecimal.valueOf(rawContracts).setScale(0, RoundingMode.DOWN).longValueExact()
        }
    }
}
