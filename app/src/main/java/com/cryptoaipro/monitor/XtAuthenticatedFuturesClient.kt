package com.cryptoaipro.monitor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
    /** XT positionSize is already expressed in contract lots. */
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

    private fun requireLiveArmed() {
        check(XtLiveTradingEngine.isEnabled(context) && !XtLiveTradingEngine.isEmergencyStopped(context)) {
            "ارسال سفارش یا تغییر اهرم فقط در حالت LIVE مسلح و بدون توقف اضطراری مجاز است."
        }
    }

    private fun canonicalQuery(values: Map<String, String>): String =
        XtRequestSigner.canonicalQuery(values)

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        formBody: Map<String, String>? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        require(body == null || formBody == null) { "XT request cannot contain both JSON and form bodies." }
        require(method == "POST" || formBody == null) { "Form body is only valid for POST." }
        val (appKey, secret) = XtCredentialVault.load(context)
            ?: throw IllegalStateException("اتصال امن XT تنظیم نشده است.")
        require(path.startsWith("/future/") && !path.contains("..")) { "XT endpoint نامعتبر است." }
        val timestamp = System.currentTimeMillis().toString()
        val queryString = canonicalQuery(query)
        val bodyText = formBody?.let { XtRequestSigner.canonicalQuery(it) } ?: body?.toString().orEmpty()
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
            connection.setRequestProperty(
                "Content-Type",
                if (method == "GET" || formBody != null) "application/x-www-form-urlencoded" else "application/json"
            )
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
            is JSONObject -> result.optJSONArray("items")
                ?: result.optJSONArray("list")
                ?: result.optJSONArray("positions")
                ?: result.optJSONArray("data")
                ?: throw IllegalStateException("ساختار پاسخ پوزیشن‌های XT ناشناخته است؛ برای جلوگیری از ورود تکراری، سفارش جدید مسدود شد.")
            else -> throw IllegalStateException("پاسخ پوزیشن‌های XT آرایه یا شیء معتبر نیست؛ سفارش جدید مسدود شد.")
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
        // XT's order sizing reference uses walletBalance minus open-order margin freeze.
        val available = (wallet - value("openOrderMarginFrozen")).coerceAtLeast(0.0)
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
        fun optionalNumber(vararg keys: String): Double? {
            for (key in keys) {
                val parsed = row.optString(key).toDoubleOrNull()
                if (parsed != null && parsed.isFinite()) return parsed
                if (row.has(key) && row.opt(key) is Number) {
                    val number = row.optDouble(key)
                    if (number.isFinite()) return number
                }
            }
            return null
        }
        val wallet = requiredNumber("walletBalance")
        val used = (optionalNumber("isolatedMargin") ?: 0.0) +
            (optionalNumber("crossedMargin") ?: 0.0)
        val orderFrozen = requiredNumber("openOrderMarginFrozen")
        // Prefer XT's explicit available balance. Its documented order-sizing fallback is
        // walletBalance minus openOrderMarginFrozen; position-margin fields are not subtracted twice.
        val available = optionalNumber("availableBalance", "availableAmount", "available")
            ?: (wallet - orderFrozen).coerceAtLeast(0.0)
        val unrealized = row.optString("notProfit").toDoubleOrNull()
        val equity = unrealized?.let { wallet + it }
            ?: requiredNumber("totalAmount", "totalEquity", "equity")
        return XtFuturesCapitalManager.Wallet("USDT", wallet, equity, available, used, orderFrozen)
            .also { it.validate() }
    }

    suspend fun activeTakeProfitStopOrders(symbol: String): List<JSONObject> {
        val normalized = normalizeSymbol(symbol)
        val byId = linkedMapOf<String, JSONObject>()
        // XT stores attached position TP/SL orders in profit-list, separately from
        // generic trigger plans. Only NOT_TRIGGERED/TRIGGERING are active protections.
        for (state in listOf("NOT_TRIGGERED", "TRIGGERING")) {
            val response = request(
                "GET", "/future/trade/v1/entrust/profit-list",
                mapOf("symbol" to normalized, "state" to state, "page" to "1", "size" to "100")
            )
            val result = response.optJSONObject("result")
                ?: throw IllegalStateException("ساختار پاسخ TP/SL حساب XT نامعتبر است.")
            val items = result.optJSONArray("items")
                ?: throw IllegalStateException("فهرست TP/SL در XT فاقد items است.")
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.opt("profitId")?.toString().orEmpty()
                val key = if (id.isNotBlank()) id else item.optString("symbol") + "|" +
                    item.optString("positionSide") + "|" + item.optString("triggerStopPrice") + "|" +
                    item.optString("triggerProfitPrice")
                byId[key] = item
            }
        }
        return byId.values.toList()
    }

    suspend fun activeTriggerPlans(symbol: String): List<JSONObject> {
        val normalized = normalizeSymbol(symbol)
        val byId = linkedMapOf<String, JSONObject>()
        // XT distinguishes a newly placed plan (NOT_TRIGGERED) from one currently
        // being triggered (TRIGGERING). Both are active protection states.
        for (state in listOf("NOT_TRIGGERED", "TRIGGERING")) {
            val response = request(
                "GET", "/future/trade/v1/entrust/plan-list",
                mapOf("symbol" to normalized, "state" to state, "page" to "1", "size" to "100")
            )
            val result = response.optJSONObject("result")
            val items = result?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.opt("entrustId")?.toString().orEmpty()
                val key = if (id.isNotBlank()) id else item.optString("clientOrderId") + "|" + item.optString("entrustType")
                byId[key] = item
            }
        }
        return byId.values.toList()
    }

    suspend fun activeOpenOrders(): List<JSONObject> {
        val byId = linkedMapOf<String, JSONObject>()
        for (state in listOf("NEW", "UNFINISHED")) {
            val response = request(
                "GET", "/future/trade/v1/order/list",
                mapOf("state" to state, "page" to "1", "size" to "100")
            )
            val result = response.optJSONObject("result")
                ?: throw IllegalStateException("ساختار فهرست سفارش‌های باز XT نامعتبر است؛ ورود جدید مسدود شد.")
            val items = result.optJSONArray("items")
                ?: throw IllegalStateException("فهرست سفارش‌های XT فاقد items است؛ ورود جدید مسدود شد.")
            for (i in 0 until items.length()) {
                val order = items.optJSONObject(i) ?: continue
                val id = order.opt("orderId")?.toString().orEmpty()
                val key = if (id.isNotBlank()) id else order.optString("clientOrderId")
                if (key.isNotBlank()) byId[key] = order
            }
        }
        return byId.values.toList()
    }

    private suspend fun findOrderByClientOrderId(symbol: String, clientOrderId: String): JSONObject? {
        val normalized = normalizeSymbol(symbol)
        // Read-only reconciliation only: never resubmit an order after an ambiguous POST.
        for (state in listOf("HISTORY", "UNFINISHED")) {
            repeat(3) { attempt ->
                val response = request(
                    "GET", "/future/trade/v1/order/list",
                    mapOf(
                        "symbol" to normalized,
                        "clientOrderId" to clientOrderId,
                        "state" to state,
                        "page" to "1",
                        "size" to "10"
                    )
                )
                val items = response.optJSONObject("result")?.optJSONArray("items") ?: JSONArray()
                val match = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .firstOrNull { it.optString("clientOrderId") == clientOrderId }
                if (match != null) return match
                if (attempt < 2) delay(250L)
            }
        }
        return null
    }

    /**
     * Change leverage only after the caller has passed the in-app LIVE gate.
     * XT documents LONG/SHORT as required position sides.
     */
    suspend fun adjustLeverage(symbol: String, positionSide: String, leverage: Int): JSONObject {
        requireLiveArmed()
        require(positionSide in setOf("LONG", "SHORT"))
        require(leverage in 1..3) { "ZORO safety cap: leverage must be between 1x and 3x." }
        val body = JSONObject()
            .put("symbol", normalizeSymbol(symbol))
            .put("positionSide", positionSide)
            .put("leverage", leverage)
        return request("POST", "/future/user/v1/position/adjust-leverage", body = body)
    }

    data class MarketOrderReceipt(
        val orderId: String,
        val clientOrderId: String,
        val raw: JSONObject
    )

    /** Market entry only; exchange TP/SL is attached immediately after fill with MARKET delegates. */
    suspend fun placeMarketEntry(
        symbol: String,
        side: String,
        contracts: Long,
        clientOrderId: String
    ): MarketOrderReceipt {
        requireLiveArmed()
        require(side in setOf("LONG", "SHORT"))
        require(contracts > 0L)
        require(clientOrderId.matches(Regex("[A-Za-z0-9_-]{8,32}")))
        val normalized = normalizeSymbol(symbol)
        val body = JSONObject()
            .put("clientOrderId", clientOrderId)
            .put("symbol", normalized)
            .put("orderSide", if (side == "LONG") "BUY" else "SELL")
            .put("orderType", "MARKET")
            .put("timeInForce", "IOC")
            .put("origQty", contracts)
            .put("positionSide", side)
        request("POST", "/future/trade/v1/order/create", body = body)
        val order = findOrderByClientOrderId(symbol, clientOrderId)
            ?: throw IllegalStateException(
                "XT درخواست ورود را پذیرفت اما سفارش با clientOrderId قابل تطبیق نیست؛ ممکن است ثبت شده باشد. از ارسال مجدد خودداری کن و تاریخچه XT را بررسی کن."
            )
        val id = order.opt("orderId")?.toString().orEmpty()
        require(id.isNotBlank() && order.optString("clientOrderId") == clientOrderId &&
            normalizeSymbol(order.optString("symbol")) == normalized &&
            order.optString("orderSide").equals(if (side == "LONG") "BUY" else "SELL", true) &&
            order.optString("positionSide").equals(side, true) &&
            (order.optString("origQty").toDoubleOrNull() ?: Double.NaN) == contracts.toDouble()) {
            "سفارش پیدا شد اما نماد، جهت، حجم یا clientOrderId آن قابل تأیید نیست؛ ارسال مجدد ممنوع است."
        }
        return MarketOrderReceipt(id, clientOrderId, order)
    }

    data class TakeProfitStopReceipt(
        val profitId: String,
        val symbol: String,
        val positionSide: String,
        val contracts: Long,
        val raw: JSONObject
    )

    /** Attach exchange-native MARKET TP and SL delegates to an already-filled position. */
    suspend fun createTakeProfitStopMarket(
        symbol: String,
        positionSide: String,
        contracts: Long,
        triggerStopPrice: Double,
        triggerProfitPrice: Double
    ): TakeProfitStopReceipt {
        requireLiveArmed()
        require(positionSide in setOf("LONG", "SHORT") && contracts > 0L)
        require(triggerStopPrice.isFinite() && triggerStopPrice > 0.0 &&
            triggerProfitPrice.isFinite() && triggerProfitPrice > 0.0)
        val normalized = normalizeSymbol(symbol)
        val form = mapOf(
            "symbol" to normalized,
            "origQty" to contracts.toString(),
            "triggerProfitPrice" to BigDecimal.valueOf(triggerProfitPrice).stripTrailingZeros().toPlainString(),
            "triggerStopPrice" to BigDecimal.valueOf(triggerStopPrice).stripTrailingZeros().toPlainString(),
            "expireTime" to (System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L).toString(),
            "positionSide" to positionSide,
            "profitDelegateOrderType" to "MARKET",
            "profitDelegateTimeInForce" to "IOC",
            "stopDelegateOrderType" to "MARKET",
            "stopDelegateTimeInForce" to "IOC"
        )
        request("POST", "/future/trade/v1/entrust/create-profit", formBody = form)
        var verified: JSONObject? = null
        repeat(3) { attempt ->
            if (verified == null) {
                verified = activeTakeProfitStopOrders(symbol).firstOrNull { item ->
                    normalizeSymbol(item.optString("symbol")) == normalized &&
                        item.optString("positionSide").equals(positionSide, true) &&
                        (item.optString("origQty").toDoubleOrNull() ?: Double.NaN) == contracts.toDouble() &&
                        kotlin.math.abs((item.optString("triggerStopPrice").toDoubleOrNull() ?: 0.0) - triggerStopPrice) <=
                            maxOf(1e-8, triggerStopPrice * 1e-8) &&
                        kotlin.math.abs((item.optString("triggerProfitPrice").toDoubleOrNull() ?: 0.0) - triggerProfitPrice) <=
                            maxOf(1e-8, triggerProfitPrice * 1e-8) &&
                        item.optString("profitDelegateOrderType").equals("MARKET", true) &&
                        item.optString("stopDelegateOrderType").equals("MARKET", true)
                }
                if (verified == null && attempt < 2) delay(250L)
            }
        }
        val plan = verified ?: throw IllegalStateException(
            "درخواست ثبت TP/SL بازار ارسال شد اما طرح با نوع MARKET و حجم دقیق در XT تأیید نشد؛ پوزیشن را فوراً دستی بررسی کن."
        )
        val id = plan.opt("profitId")?.toString().orEmpty()
        require(id.isNotBlank()) { "طرح TP/SL پیدا شد اما profitId آن قابل تأیید نیست." }
        return TakeProfitStopReceipt(id, normalized, positionSide, contracts, plan)
    }

    data class TriggerPlanReceipt(
        val planId: String,
        val clientOrderId: String,
        val kind: String,
        val raw: JSONObject
    )

    /**
     * Places an exchange-side protective STOP_MARKET or TAKE_PROFIT_MARKET plan.
     * A successful HTTP response is not enough; the caller must verify the plan
     * appears in activeTriggerPlans before declaring the position protected.
     */
    suspend fun placeProtectivePlan(
        symbol: String,
        positionSide: String,
        kind: String,
        contracts: Long,
        triggerPrice: Double,
        clientOrderId: String
    ): TriggerPlanReceipt {
        requireLiveArmed()
        require(positionSide in setOf("LONG", "SHORT"))
        require(kind in setOf("STOP_MARKET", "TAKE_PROFIT_MARKET"))
        require(contracts > 0L && triggerPrice.isFinite() && triggerPrice > 0.0)
        require(clientOrderId.matches(Regex("[A-Za-z0-9_-]{8,32}")))
        val closeSide = if (positionSide == "LONG") "SELL" else "BUY"
        val body = JSONObject()
            .put("clientOrderId", clientOrderId)
            .put("symbol", normalizeSymbol(symbol))
            .put("orderSide", closeSide)
            .put("entrustType", kind)
            .put("origQty", contracts)
            .put("stopPrice", BigDecimal.valueOf(triggerPrice))
            .put("timeInForce", "IOC")
            .put("triggerPriceType", "MARK_PRICE")
            .put("positionSide", positionSide)
        val response = request("POST", "/future/trade/v1/entrust/create-plan", body = body)
        val result = response.optJSONObject("result") ?: JSONObject()
        val immediateId = result.opt("entrustId")?.toString()
            ?: result.opt("planId")?.toString()
            ?: result.opt("id")?.toString().orEmpty()
        val verified = if (immediateId.isNotBlank()) {
            runCatching {
                activeTriggerPlans(symbol).firstOrNull { it.opt("entrustId")?.toString() == immediateId }
            }.getOrNull()
        } else null
        val plan = verified ?: run {
            var found: JSONObject? = null
            repeat(3) { attempt ->
                if (found == null) {
                    found = activeTriggerPlans(symbol).firstOrNull {
                        it.optString("clientOrderId") == clientOrderId &&
                            it.optString("entrustType") == kind &&
                            it.optString("positionSide") == positionSide &&
                            kotlin.math.abs((it.optString("stopPrice").toDoubleOrNull() ?: 0.0) - triggerPrice) <=
                                maxOf(1e-8, triggerPrice * 1e-8) &&
                            (it.optString("origQty").toDoubleOrNull() ?: Double.NaN) == contracts.toDouble()
                    }
                    if (found == null && attempt < 2) delay(250L)
                }
            }
            found
        } ?: throw IllegalStateException(
            "XT پاسخ ثبت طرح محافظتی را داد، اما طرح با clientOrderId قابل تأیید نیست؛ از ایجاد طرح تکراری خودداری کن و وضعیت پوزیشن را در XT بررسی کن."
        )
        val id = plan.opt("entrustId")?.toString().orEmpty()
        require(id.isNotBlank()) { "طرح محافظتی پیدا شد اما entrustId آن قابل تأیید نیست." }
        return TriggerPlanReceipt(id, clientOrderId, kind, plan)
    }

    companion object {
        /** Floors quantity to XT's integer contract unit; never rounds risk upward. */
        fun floorContracts(rawContracts: Double): Long {
            require(rawContracts.isFinite() && rawContracts > 0.0) { "حجم قرارداد محاسبه‌شده نامعتبر است." }
            return BigDecimal.valueOf(rawContracts).setScale(0, RoundingMode.DOWN).longValueExact()
        }
    }
}
