package com.cryptoaipro.monitor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

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
internal data class XtLivePosition(
    val symbol: String,
    val side: String,
    /** XT positionSize is base-asset quantity; convert by contractSize for order lots. */
    val quantity: Double,
    val availableCloseQuantity: Double,
    val entryPrice: Double,
    val markPrice: Double,
    val leverage: Int,
    val contractSize: Double
)

internal class XtAuthenticatedFuturesClient(
    private val context: Context
) {
    private val baseUrl = "https://fapi.xt.com"

    private fun normalizeSymbol(value: String): String {
        val compact = value.lowercase().filter(Char::isLetterOrDigit)
        require(compact.endsWith("usdt") && compact.length > 4) { "نماد قرارداد USDT نامعتبر است." }
        return compact.dropLast(4) + "_usdt"
    }

    private fun canonicalQuery(values: Map<String, String>): String =
        XtRequestSigner.canonicalQuery(values)

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
        val signingText = XtRequestSigner.signingPayload(
            appKey = appKey,
            timestampMs = timestamp,
            endpoint = path,
            queryString = if (method == "GET") queryString else "",
            exactJsonBody = if (method == "GET") null else bodyText
        )
        val sig = XtRequestSigner.sign(secret, signingText)
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
            connection.setRequestProperty("validate-recvwindow", "5000")
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
            require(response.has("returnCode")) { "پاسخ XT فاقد returnCode است؛ نتیجه درخواست قابل تأیید نیست." }
            val returnCode = response.optInt("returnCode", Int.MIN_VALUE)
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
        val available = (wallet - value("openOrderMarginFrozen") - value("isolatedMargin") - value("crossedMargin"))
            .coerceAtLeast(0.0)
        return AccountSnapshot(
            account.optBoolean("allowTrade", false),
            account.optBoolean("allowOpenPosition", false),
            wallet,
            value("totalAmount").takeIf { it > 0.0 } ?: value("amount").takeIf { it > 0.0 } ?: wallet,
            available
        )
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
        takerFeeRate: Double = 0.0006,
        slippageRate: Double = 0.0005,
        riskFraction: Double = 0.005,
        marginFraction: Double = 0.10
    ): ContractSizing {
        require(listOf(equityUsdt, availableUsdt, entry, stop, contractSize).all { it.isFinite() && it > 0.0 })
        require(entry != stop && leverage in 1..3)
        require(riskFraction in 0.001..0.01 && marginFraction in 0.02..0.15 &&
            takerFeeRate in 0.0..0.02 && slippageRate in 0.0..0.02)
        val riskBudget = equityUsdt * riskFraction
        val lossPerContract = kotlin.math.abs(entry - stop) * contractSize +
            (entry + stop) * contractSize * takerFeeRate +
            (entry + stop) * contractSize * slippageRate
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

    /**
     * Strict Futures wallet snapshot. We never infer free margin by subtracting
     * guessed fields: if XT does not return an explicit available balance, the
     * caller must stop and request schema verification rather than oversize.
     */
    suspend fun futuresWallet(): XtFuturesCapitalManager.Wallet {
        val response = request("GET", "/future/user/v1/compat/balance/list")
        val rows = response.optJSONArray("result")
            ?: throw IllegalStateException("پاسخ موجودی فیوچرز XT آرایه نیست.")
        val row = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .firstOrNull { it.optString("coin").equals("USDT", true) }
            ?: throw IllegalStateException("دارایی USDT در کیف پول Futures پیدا نشد.")
        fun requiredNumber(vararg keys: String): Double {
            for (key in keys) {
                val raw = row.optString(key)
                val parsed = raw.toDoubleOrNull()
                if (parsed != null && parsed.isFinite()) return parsed
                if (row.has(key) && row.opt(key) is Number) {
                    val n = row.optDouble(key)
                    if (n.isFinite()) return n
                }
            }
            throw IllegalStateException("فیلد ضروری موجودی XT یافت نشد: " + keys.joinToString("/"))
        }
        val wallet = requiredNumber("walletBalance")
        val isolated = requiredNumber("isolatedMargin")
        val crossed = requiredNumber("crossedMargin")
        val used = isolated + crossed
        val orderFrozen = requiredNumber("openOrderMarginFrozen")
        // XT defines available = wallet balance - position margin - order freeze.
        // Prefer the explicit available field when the account API returns it.
        val available = runCatching { requiredNumber("availableBalance", "availableAmount", "available") }
            .getOrElse { (wallet - used - orderFrozen).coerceAtLeast(0.0) }
        val unrealized = row.optString("notProfit").toDoubleOrNull()
        val equity = unrealized?.let { wallet + it }
            ?: requiredNumber("totalAmount", "totalEquity", "equity")
        return XtFuturesCapitalManager.Wallet("USDT", wallet, equity, available, used, orderFrozen)
            .also { it.validate() }
    }

    suspend fun activeTriggerPlans(symbol: String): List<JSONObject> {
        val normalized = normalizeSymbol(symbol)
        val response = request(
            "GET", "/future/trade/v1/entrust/plan-list",
            mapOf("symbol" to normalized, "state" to "TRIGGERING", "page" to "1", "size" to "100")
        )
        val result = response.optJSONObject("result")
        val items = result?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    companion object {
        /** Floors quantity to XT's integer contract unit; never rounds risk upward. */
        fun floorContracts(rawContracts: Double): Long {
            require(rawContracts.isFinite() && rawContracts > 0.0) { "حجم قرارداد محاسبه‌شده نامعتبر است." }
            return BigDecimal.valueOf(rawContracts).setScale(0, RoundingMode.DOWN).longValueExact()
        }
    }
}
