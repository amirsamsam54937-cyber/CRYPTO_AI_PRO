package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Manual, user-confirmed XT USDT-M execution.
 * Scanning never submits orders. A user must arm LIVE mode, select a current signal,
 * review the exchange-sized risk preview, and confirm the specific order.
 * All gates fail closed. Ambiguous order POSTs are never retried.
 */
internal object XtLiveTradingEngine {
    private const val PREFS = "zoro_live_trade_state"
    private const val KEY_ENABLED = "live_execution_enabled"
    private const val KEY_EMERGENCY = "emergency_stop"
    private const val KEY_STATUS = "last_status"
    private const val KEY_MANAGED = "managed_symbols"
    private const val MAX_DAILY_ENTRIES = 3
    private const val MAX_DAILY_LOSS = 0.02
    private const val MAX_PEAK_DRAWDOWN = 0.08
    private const val PREVIEW_TTL_MS = 45_000L

    data class ManualPreview(
        val symbol: String,
        val side: String,
        val referencePrice: Double,
        val stop: Double,
        val target: Double,
        val contracts: Long,
        val leverage: Int,
        val riskBudgetUsdt: Double,
        val estimatedStopLossUsdt: Double,
        val marginRequiredUsdt: Double,
        val estimatedNetTargetProfitUsdt: Double,
        val netRewardRisk: Double,
        val expiresAt: Long,
        val clientOrderId: String
    )

    data class ExecutionResult(val success: Boolean, val message: String, val orderId: String = "")

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun isEmergencyStopped(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EMERGENCY, false)

    fun lastStatus(context: Context): String =
        prefs(context).getString(KEY_STATUS, "معامله واقعی هنوز فعال نشده است.") ?: "معامله واقعی هنوز فعال نشده است."

    fun positionCount(context: Context): Int = prefs(context).getInt("last_position_count", 0)

    fun hasManagedSymbols(context: Context): Boolean =
        prefs(context).getStringSet(KEY_MANAGED, emptySet())?.isNotEmpty() == true

    suspend fun canClearCredentials(context: Context): Boolean {
        val client = XtAuthenticatedFuturesClient(context)
        if (client.activePositions().isNotEmpty() || client.activeOpenOrders().isNotEmpty()) return false
        prefs(context).edit().remove(KEY_MANAGED).putInt("last_position_count", 0).apply()
        return true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_STATUS, if (enabled)
                "حالت LIVE مسلح شد؛ هیچ سفارش خودکاری ارسال نمی‌شود. برای هر سیگنال تأیید جداگانه لازم است."
            else "حالت LIVE غیرفعال شد؛ سفارش جدید ارسال نمی‌شود.")
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun setStatus(context: Context, value: String) {
        prefs(context).edit().putString(KEY_STATUS, value).apply()
    }

    private fun tripEmergencyStop(context: Context, message: String) {
        prefs(context).edit()
            .putBoolean(KEY_EMERGENCY, true)
            .putBoolean(KEY_ENABLED, false)
            .putString(KEY_STATUS, "توقف اضطراری: $message")
            .apply()
    }

    suspend fun resetEmergencyStopIfFlat(context: Context): Boolean {
        val client = XtAuthenticatedFuturesClient(context)
        if (client.activePositions().isNotEmpty() || client.activeOpenOrders().isNotEmpty()) {
            setStatus(context, "توقف اضطراری قابل بازنشانی نیست؛ ابتدا تمام پوزیشن‌ها و سفارش‌های باز XT را تعیین تکلیف کن.")
            return false
        }
        prefs(context).edit()
            .putBoolean(KEY_EMERGENCY, false)
            .putBoolean(KEY_ENABLED, false)
            .putString(KEY_STATUS, "توقف اضطراری بازنشانی شد؛ حالت LIVE همچنان خاموش است.")
            .remove(KEY_MANAGED)
            .apply()
        return true
    }

    private fun signalSide(signal: MarketSignal): String = when (signal.direction) {
        "LONG_BIAS" -> "LONG"
        "SHORT_BIAS" -> "SHORT"
        else -> ""
    }

    private fun normalized(value: String): String =
        value.lowercase(Locale.US).filter(Char::isLetterOrDigit)

    private fun portfolioState(context: Context, equity: Double): XtFuturesCapitalManager.PortfolioState {
        val p = prefs(context)
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val savedDay = p.getString("risk_day", "")
        if (savedDay != day) p.edit().putInt("daily_entries", 0).apply()
        val dayStart = if (savedDay == day)
            (p.getString("day_start_equity", null)?.toDoubleOrNull() ?: equity).takeIf { it.isFinite() && it > 0.0 } ?: equity
        else equity
        val peakStored = p.getString("peak_equity", null)?.toDoubleOrNull() ?: equity
        val peak = max(peakStored.takeIf { it.isFinite() && it > 0.0 } ?: equity, equity)
        p.edit().putString("risk_day", day)
            .putString("day_start_equity", dayStart.toString())
            .putString("peak_equity", peak.toString()).apply()
        return XtFuturesCapitalManager.PortfolioState(
            currentEquity = equity,
            peakEquity = peak,
            dayStartEquity = dayStart,
            consecutiveLosses = p.getInt("consecutive_losses", 0).coerceAtLeast(0)
        )
    }

    suspend fun prepareManual(
        context: Context,
        signal: MarketSignal,
        clientOrderId: String = "zoro_" + UUID.randomUUID().toString().replace("-", "").take(20)
    ): ManualPreview {
        require(isEnabled(context)) { "ابتدا حالت LIVE را در پنل معامله واقعی فعال کن." }
        require(!isEmergencyStopped(context)) { "توقف اضطراری فعال است؛ ارسال سفارش ممنوع است." }
        require(XtCredentialVault.hasCredentials(context)) { "کلید API در حافظه امن گوشی ذخیره نشده است." }
        require(clientOrderId.matches(Regex("[A-Za-z0-9_-]{8,32}"))) { "شناسه داخلی سفارش نامعتبر است." }

        val side = signalSide(signal)
        require(side.isNotBlank()) { "این سیگنال جهت قطعی LONG یا SHORT ندارد." }
        require(signal.confidence >= 70 && signal.signalTier != "بدون رتبه") {
            "قدرت یا رتبه سیگنال برای بررسی سفارش واقعی کافی نیست."
        }
        require(WolfPretrainedEnsemble.status().validatedForScoring) {
            "مدل عصبی هنوز اعتبارسنجی زمانی مستقل را نگذرانده است؛ سفارش واقعی مسدود شد."
        }
        require(signal.neuralMemberProbabilities.size == 10 &&
            signal.neuralMemberProbabilities.all { it.isFinite() && it in 0.0..1.0 }) {
            "رأی معتبر هر ۱۰ متخصص عصبی در این سیگنال موجود نیست."
        }
        require(signal.price.isFinite() && signal.price > 0.0 &&
            signal.entry.isFinite() && signal.entry > 0.0 &&
            signal.stop.isFinite() && signal.stop > 0.0 &&
            signal.tp1.isFinite() && signal.tp1 > 0.0) { "قیمت‌های ورود، حدضرر یا هدف نامعتبرند." }
        val long = side == "LONG"
        require(if (long) signal.stop < signal.entry && signal.tp1 > signal.entry
            else signal.stop > signal.entry && signal.tp1 < signal.entry) {
            "جهت حدضرر و هدف با جهت سیگنال سازگار نیست."
        }
        require(abs(signal.price - signal.entry) / signal.entry <= 0.0075) {
            "قیمت بازار بیش از ۰٫۷۵٪ از نقطه ورود سیگنال فاصله گرفته است؛ سیگنال منقضی تلقی شد."
        }
        val candleTime = signal.candles.lastOrNull()?.getOrNull(5)?.toLong() ?: 0L
        require(candleTime > 0L && System.currentTimeMillis() - candleTime in 0L..30L * 60L * 1000L) {
            "داده کندل سیگنال تازه نیست؛ اسکن جدید لازم است."
        }

        val client = XtAuthenticatedFuturesClient(context)
        val account = client.accountSnapshot()
        val policyState = XtExecutionPolicy.State(
            mode = XtExecutionPolicy.Mode.LIVE_TRADING,
            liveArmed = isEnabled(context),
            credentialsVerified = XtCredentialVault.hasCredentials(context),
            accountCanTrade = account.canTrade,
            accountCanOpenPosition = account.canOpenPosition,
            emergencyStop = isEmergencyStopped(context)
        )
        require(XtExecutionPolicy.maySendOrder(policyState)) {
            "مجوز معامله و بازکردن پوزیشن در XT یا وضعیت فعال‌سازی LIVE تأیید نشد."
        }
        require(client.activePositions().isEmpty()) {
            "برای جلوگیری از تداخل با پوزیشن‌های دستی یا قبلی، تا وقتی حساب XT پوزیشن باز دارد سفارش جدید مسدود است."
        }
        require(client.activeOpenOrders().isEmpty()) {
            "در حساب XT سفارش باز یا نیمه‌تمام وجود دارد؛ تا تعیین تکلیف آن سفارش‌ها، ورود جدید مسدود است."
        }

        val wallet = client.futuresWallet()
        val contract = client.contractSpec(signal.symbol)
        val portfolio = portfolioState(context, wallet.equity)
        val dailyEntries = prefs(context).getInt("daily_entries", 0)
        require(dailyEntries < MAX_DAILY_ENTRIES) { "سقف سه ورود واقعی در روز تکمیل شده است." }
        val dailyLoss = ((portfolio.dayStartEquity - portfolio.currentEquity) /
            portfolio.dayStartEquity).coerceAtLeast(0.0)
        val drawdown = ((portfolio.peakEquity - portfolio.currentEquity) /
            portfolio.peakEquity).coerceAtLeast(0.0)
        require(dailyLoss < MAX_DAILY_LOSS) { "ترمز زیان روزانه ۲٪ فعال است." }
        require(drawdown < MAX_PEAK_DRAWDOWN) { "ترمز افت سرمایه از اوج ۸٪ فعال است." }

        val leverage = signal.leverage.coerceIn(1, 3)
        val decision = XtFuturesCapitalManager.planAdaptive(
            wallet = wallet,
            fees = XtFuturesCapitalManager.Fees(
                makerRate = contract.makerFeeRate,
                takerRate = contract.takerFeeRate,
                expectedEntryAsTaker = true,
                expectedExitAsTaker = true
            ),
            rules = XtFuturesCapitalManager.ContractRules(
                contractSize = contract.contractSize,
                quantityStep = 1.0,
                minQuantity = max(1.0, contract.minQty),
                minNotionalUsdt = contract.minNotional
            ),
            request = XtFuturesCapitalManager.Request(
                entry = signal.price,
                stop = signal.stop,
                takeProfit = signal.tp1,
                leverage = leverage,
                riskFraction = 0.0025,
                maxMarginFraction = 0.05,
                expectedFundingRatePer8h = 0.0001,
                expectedHoldingHours = 6.0,
                slippageRatePerSide = 0.001,
                extraSafetyBuffer = 0.20
            ),
            portfolio = portfolio
        )
        require(decision.allowed) { decision.reason }
        val plan = decision.plan ?: throw IllegalStateException(decision.reason)
        val contracts = XtAuthenticatedFuturesClient.floorContracts(plan.contracts)
        require(contracts > 0L) { "حجم مجاز به حداقل یک قرارداد نمی‌رسد." }
        return ManualPreview(
            symbol = signal.symbol,
            side = side,
            referencePrice = signal.price,
            stop = signal.stop,
            target = signal.tp1,
            contracts = contracts,
            leverage = leverage,
            riskBudgetUsdt = plan.riskBudgetUsdt,
            estimatedStopLossUsdt = plan.worstCaseStopCostUsdt,
            marginRequiredUsdt = plan.marginRequiredUsdt,
            estimatedNetTargetProfitUsdt = plan.estimatedNetTargetProfitUsdt,
            netRewardRisk = plan.netRewardRisk,
            expiresAt = System.currentTimeMillis() + PREVIEW_TTL_MS,
            clientOrderId = clientOrderId
        )
    }

    suspend fun executeManual(
        context: Context,
        signal: MarketSignal,
        preview: ManualPreview
    ): ExecutionResult {
        var orderSubmissionAttempted = false
        try {
            require(System.currentTimeMillis() <= preview.expiresAt) {
                "پیش‌نمایش سفارش منقضی شده است؛ دوباره محاسبه کن."
            }
            val fresh = prepareManual(context, signal, preview.clientOrderId)
            require(fresh.symbol == preview.symbol && fresh.side == preview.side &&
                fresh.contracts == preview.contracts && fresh.leverage == preview.leverage &&
                abs(fresh.referencePrice - preview.referencePrice) / preview.referencePrice <= 0.002) {
                "قیمت، حجم یا شرایط حساب تغییر کرده است؛ پیش‌نمایش قبلی باطل شد. دوباره بررسی کن."
            }
            val client = XtAuthenticatedFuturesClient(context)
            client.adjustLeverage(fresh.symbol, fresh.side, fresh.leverage)
            val managedBeforeSubmit = prefs(context).getStringSet(KEY_MANAGED, emptySet())?.toMutableSet() ?: mutableSetOf()
            managedBeforeSubmit += normalized(fresh.symbol)
            prefs(context).edit().putStringSet(KEY_MANAGED, managedBeforeSubmit).apply()
            orderSubmissionAttempted = true
            val receipt = client.placeProtectedMarketEntry(
                symbol = fresh.symbol,
                side = fresh.side,
                contracts = fresh.contracts,
                referencePrice = fresh.referencePrice,
                stopLossPrice = fresh.stop,
                takeProfitPrice = fresh.target,
                clientOrderId = fresh.clientOrderId
            )
            val order = receipt.raw
            val state = order.optString("state").uppercase(Locale.US)
            val executed = order.optString("executedQty").toDoubleOrNull() ?: 0.0
            if (state != "FILLED" || executed != fresh.contracts.toDouble()) {
                tripEmergencyStop(context,
                    "سفارش ${receipt.orderId} به‌طور کامل پر نشد (state=$state, fill=$executed). وضعیت پوزیشن و سفارش‌های محافظتی را فوراً در XT بررسی کن.")
                return ExecutionResult(false,
                    "سفارش کامل پر نشد؛ توقف اضطراری فعال شد. برای جلوگیری از ایجاد پوزیشن ناخواسته، معامله دیگری ارسال نکن و وضعیت XT را بررسی کن.",
                    receipt.orderId)
            }

            val positions = client.activePositions()
            val position = positions.firstOrNull {
                normalized(it.symbol) == normalized(fresh.symbol) && it.side == fresh.side
            }
            if (position == null || abs(position.quantity - executed) >= 1e-8) {
                tripEmergencyStop(context,
                    "ورود ${receipt.orderId} انجام شد اما اندازه پوزیشن با مقدار Fill تطبیق ندارد؛ بررسی دستی XT لازم است.")
                return ExecutionResult(false,
                    "Fill انجام شد اما پوزیشن با سفارش تطبیق ندارد؛ توقف اضطراری فعال شد.", receipt.orderId)
            }

            val protections = client.activeTakeProfitStopOrders(fresh.symbol)
            val protectionVerified = protections.any { protection ->
                normalized(protection.optString("symbol")) == normalized(fresh.symbol) &&
                    protection.optString("positionSide").equals(fresh.side, true) &&
                    abs((protection.optString("triggerStopPrice").toDoubleOrNull() ?: 0.0) - fresh.stop) <=
                        maxOf(1e-8, fresh.stop * 1e-8) &&
                    abs((protection.optString("triggerProfitPrice").toDoubleOrNull() ?: 0.0) - fresh.target) <=
                        maxOf(1e-8, fresh.target * 1e-8) &&
                    (protection.optString("origQty").toDoubleOrNull() ?: Double.NaN) == executed
            }
            if (!protectionVerified) {
                tripEmergencyStop(context,
                    "پوزیشن ${fresh.symbol} باز شد اما طرح ترکیبی TP/SL در فهرست فعال profit-list صرافی تأیید نشد؛ فوراً پوزیشن را در XT بررسی کن.")
                return ExecutionResult(false,
                    "پوزیشن باز شده اما حفاظت TP/SL در XT تأیید نشد؛ توقف اضطراری فعال شد. فوراً در XT حدضرر/حدسود را بررسی کن.",
                    receipt.orderId)
            }

            val p = prefs(context)
            val managed = p.getStringSet(KEY_MANAGED, emptySet())?.toMutableSet() ?: mutableSetOf()
            managed += normalized(fresh.symbol)
            p.edit()
                .putStringSet(KEY_MANAGED, managed)
                .putInt("daily_entries", p.getInt("daily_entries", 0) + 1)
                .putInt("last_position_count", positions.size)
                .putString(KEY_STATUS,
                    "سفارش واقعی تأیید شد • ${fresh.symbol} ${fresh.side} • ${fresh.contracts} قرارداد • SL و TP در XT فعال تأیید شدند.")
                .apply()
            return ExecutionResult(true,
                "معامله واقعی ثبت و Fill تأیید شد؛ هر دو طرح حدضرر و حدسود نیز در XT فعال مشاهده شدند.",
                receipt.orderId)
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            if (orderSubmissionAttempted) {
                tripEmergencyStop(context, "پس از ارسال درخواست، وضعیت سفارش به‌طور کامل تأیید نشد: $message. ارسال مجدد ممنوع؛ تاریخچه و پوزیشن XT را فوراً بررسی کن.")
                return ExecutionResult(false, "وضعیت سفارش پس از ارسال مبهم است؛ توقف اضطراری فعال شد. سفارش را دوباره نفرست و فوراً XT را بررسی کن.")
            }
            setStatus(context, "سفارش ارسال نشد: $message")
            return ExecutionResult(false, message)
        }
    }

    /** Monitor only ZORO-managed positions; never submits or modifies an order. */
    suspend fun monitorOpenPositions(context: Context) {
        val p = prefs(context)
        val managed = p.getStringSet(KEY_MANAGED, emptySet())?.toSet().orEmpty()
        if (managed.isEmpty() || !XtCredentialVault.hasCredentials(context)) return
        val client = XtAuthenticatedFuturesClient(context)
        val positions = client.activePositions()
        val openOrders = client.activeOpenOrders()
        p.edit().putInt("last_position_count", positions.size).apply()
        val remainingManaged = managed.toMutableSet()
        for (symbol in managed) {
            val position = positions.firstOrNull { normalized(it.symbol) == symbol }
            if (position == null) {
                val pendingOrder = openOrders.any {
                    normalized(it.optString("symbol")) == symbol &&
                        it.optString("state").uppercase(Locale.US) in setOf("NEW", "PARTIALLY_FILLED")
                }
                if (pendingOrder) {
                    tripEmergencyStop(context, "برای $symbol هنوز سفارش باز یا نیمه‌پر وجود دارد؛ تا تعیین تکلیف آن، توقف اضطراری حفظ می‌شود.")
                    return
                }
                remainingManaged.remove(symbol)
                continue
            }
            val protections = client.activeTakeProfitStopOrders(position.symbol)
            val hasProtection = protections.any {
                it.optString("positionSide").equals(position.side, true) &&
                    (it.optString("triggerStopPrice").toDoubleOrNull() ?: 0.0) > 0.0 &&
                    (it.optString("triggerProfitPrice").toDoubleOrNull() ?: 0.0) > 0.0 &&
                    (it.optString("origQty").toDoubleOrNull() ?: 0.0) + 1e-8 >= position.quantity
            }
            if (!hasProtection) {
                tripEmergencyStop(context,
                    "حفاظت TP/SL صرافی برای پوزیشن ${position.symbol} ناقص یا نامشخص است؛ ورود جدید متوقف شد. پوزیشن را فوراً در XT بررسی کن.")
                return
            }
        }
        p.edit().putStringSet(KEY_MANAGED, remainingManaged).apply()
    }

    /** Scans never open live positions automatically. */
    suspend fun processScan(context: Context, signals: List<MarketSignal>) = Unit
}
