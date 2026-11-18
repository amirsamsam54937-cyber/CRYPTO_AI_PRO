package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * XT USDT-M execution with separate manual-confirmed and explicitly armed automatic paths.
 * Automatic scans may submit at most one qualified technical candidate per scan only when LIVE,
 * auto mode, credentials, account checks and every risk gate pass. Neural validation is advisory.
 * Manual execution still requires a reviewed preview and explicit confirmation.
 * All gates fail closed. Ambiguous order POSTs are reconciled, never blindly retried.
 */
internal object XtLiveTradingEngine {
    private const val PREFS = "zoro_live_trade_state"
    private const val KEY_ENABLED = "live_execution_enabled"
    private const val KEY_AUTO_ENABLED = "automatic_execution_enabled"
    private val autoScanMutex = Mutex()
    private val executionMutex = Mutex()
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
        val contractSize: Double,
        val takerFeeRate: Double,
        val expiresAt: Long,
        val clientOrderId: String
    )

    data class ExecutionResult(val success: Boolean, val message: String, val orderId: String = "")

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun isAutoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ENABLED, false) &&
            isEnabled(context) && !isEmergencyStopped(context) &&
            XtCredentialVault.hasCredentials(context) &&
            XtCredentialVault.isConnectionVerified(context)

    fun canArmAutomatic(context: Context): Boolean =
        isEnabled(context) && !isEmergencyStopped(context) &&
            XtCredentialVault.hasCredentials(context) &&
            XtCredentialVault.isConnectionVerified(context)

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        val allowed = enabled && canArmAutomatic(context)
        prefs(context).edit()
            .putBoolean(KEY_AUTO_ENABLED, allowed)
            .putString(
                KEY_STATUS,
                if (allowed) "معامله‌گر خودکار مسلح شد؛ رأی عصبی در صورت اعتبارسنجی‌نشدن صرفاً نمایشی است و ورود با کنترل‌های مستقل تکنیکال، حساب و ریسک انجام می‌شود."
                else if (enabled) "فعال‌سازی خودکار رد شد؛ LIVE، کلید API و وضعیت توقف اضطراری را بررسی کن."
                else "معامله‌گر خودکار خاموش شد؛ پایش و تحلیل ادامه دارد."
            )
            .apply()
    }

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
        val managed = prefs(context).getStringSet(KEY_MANAGED, emptySet())?.toSet().orEmpty()
        if (managed.any { client.activeTakeProfitStopOrders(it).isNotEmpty() }) return false
        prefs(context).edit().remove(KEY_MANAGED).putInt("last_position_count", 0).apply()
        return true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val safeEnabled = enabled && XtCredentialVault.hasCredentials(context) &&
            XtCredentialVault.isConnectionVerified(context) && !isEmergencyStopped(context)
        val editor = prefs(context).edit().putBoolean(KEY_ENABLED, safeEnabled)
        if (!enabled) editor.putBoolean(KEY_AUTO_ENABLED, false)
        editor.putString(KEY_STATUS, if (safeEnabled)
            "حالت LIVE مسلح شد؛ اتصال خصوصی و مجوزهای Futures قبلاً تأیید شده‌اند. کنترل‌های ریسک اجباری‌اند."
        else if (enabled)
            "فعال‌سازی LIVE رد شد؛ ابتدا اتصال XT و مجوزهای Futures را با موفقیت آزمایش کن."
        else "حالت LIVE غیرفعال شد؛ معامله‌گر خودکار و سفارش‌های جدید متوقف شدند.")
        editor.apply()
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
            .putBoolean(KEY_AUTO_ENABLED, false)
            .putString(KEY_STATUS, "توقف اضطراری: $message")
            .apply()
    }

    suspend fun resetEmergencyStopIfFlat(context: Context): Boolean {
        val client = XtAuthenticatedFuturesClient(context)
        if (client.activePositions().isNotEmpty() || client.activeOpenOrders().isNotEmpty()) {
            setStatus(context, "توقف اضطراری قابل بازنشانی نیست؛ ابتدا تمام پوزیشن‌ها و سفارش‌های باز XT را تعیین تکلیف کن.")
            return false
        }
        val managed = prefs(context).getStringSet(KEY_MANAGED, emptySet())?.toSet().orEmpty()
        if (managed.any { client.activeTakeProfitStopOrders(it).isNotEmpty() }) {
            setStatus(context, "هنوز طرح TP/SL فعال یا یتیم در XT وجود دارد؛ ابتدا آن را در صرافی تعیین تکلیف کن.")
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

    internal fun directionSide(value: String): String? = when (value.trim().uppercase(Locale.US)) {
        "LONG", "LONG_BIAS" -> "LONG"
        "SHORT", "SHORT_BIAS" -> "SHORT"
        else -> null
    }

    private fun signalSide(signal: MarketSignal): String = directionSide(signal.direction).orEmpty()

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
        require(signal.confidence >= 70 && signal.signalTier.isNotBlank() && signal.signalTier != "بدون رتبه") {
            "قدرت یا رتبه سیگنال برای بررسی سفارش واقعی کافی نیست."
        }
        val qualityReview = ZoroSignalIntelligence.assess(signal)
        require(qualityReview.quality >= 65) {
            "امتیاز کیفیت شواهد برای معامله واقعی کافی نیست (" + qualityReview.quality + "/100): " +
                qualityReview.warnings.take(3).joinToString("، ")
        }
        require(directionSide(signal.technicalDirection) == side) {
            "جهت سیگنال باید با تحلیل تکنیکال مستقل همسو باشد؛ رأی مدل عصبی به‌تنهایی مجوز ورود نیست."
        }
        require(signal.technical.adx >= 16.0 && signal.technical.relativeVolume >= 0.65 &&
            (signal.consensus?.agreement ?: 0) >= 58) {
            "قدرت روند، حجم نسبی یا اجماع تکنیکال برای ورود واقعی کافی نیست."
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
        require(client.activeTakeProfitStopOrders(signal.symbol).isEmpty()) {
            "برای این نماد طرح TP/SL فعال یا باقی‌مانده در XT وجود دارد؛ ابتدا آن را تعیین تکلیف کن."
        }
        val priceScale = contract.pricePrecision.coerceIn(0, 12)
        val stopPrice = BigDecimal.valueOf(signal.stop).setScale(priceScale, RoundingMode.HALF_UP).toDouble()
        val targetPrice = BigDecimal.valueOf(signal.tp1).setScale(priceScale, RoundingMode.HALF_UP).toDouble()
        require(if (side == "LONG") stopPrice < signal.price && targetPrice > signal.price
            else stopPrice > signal.price && targetPrice < signal.price) {
            "پس از گردکردن به دقت مجاز قرارداد، حدضرر یا هدف در سمت معتبر قیمت بازار قرار ندارد."
        }
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
                stop = stopPrice,
                takeProfit = targetPrice,
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
            stop = stopPrice,
            target = targetPrice,
            contracts = contracts,
            leverage = leverage,
            riskBudgetUsdt = plan.riskBudgetUsdt,
            estimatedStopLossUsdt = plan.worstCaseStopCostUsdt,
            marginRequiredUsdt = plan.marginRequiredUsdt,
            estimatedNetTargetProfitUsdt = plan.estimatedNetTargetProfitUsdt,
            netRewardRisk = plan.netRewardRisk,
            contractSize = contract.contractSize,
            takerFeeRate = contract.takerFeeRate,
            expiresAt = System.currentTimeMillis() + PREVIEW_TTL_MS,
            clientOrderId = clientOrderId
        )
    }

    suspend fun executeManual(
        context: Context,
        signal: MarketSignal,
        preview: ManualPreview
    ): ExecutionResult = executionMutex.withLock {
        executeManualLocked(context, signal, preview)
    }

    private suspend fun executeManualLocked(
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
            prefs(context).edit()
                .putStringSet(KEY_MANAGED, managedBeforeSubmit)
                // Count every submission attempt, including partial or ambiguous fills.
                .putInt("daily_entries", prefs(context).getInt("daily_entries", 0) + 1)
                .apply()
            orderSubmissionAttempted = true
            val receipt = client.placeMarketEntry(
                symbol = fresh.symbol,
                side = fresh.side,
                contracts = fresh.contracts,
                clientOrderId = fresh.clientOrderId
            )
            val order = receipt.raw
            val state = order.optString("state").uppercase(Locale.US)
            val executed = order.optString("executedQty").toDoubleOrNull() ?: 0.0
            val averageFillPrice = order.optString("avgPrice").toDoubleOrNull() ?: 0.0

            if (state != "FILLED" || executed != fresh.contracts.toDouble()) {
                var partialProtected = false
                if (executed.isFinite() && executed > 0.0 && executed <= fresh.contracts.toDouble() &&
                    executed == executed.toLong().toDouble()) {
                    partialProtected = runCatching {
                        client.createTakeProfitStopMarket(
                            fresh.symbol, fresh.side, executed.toLong(), fresh.stop, fresh.target
                        )
                    }.isSuccess
                }
                tripEmergencyStop(context,
                    "سفارش ${receipt.orderId} کامل پر نشد (state=$state, fill=$executed). طرح بازار TP/SL برای Fill جزئی ${if (partialProtected) "ثبت و تأیید شد" else "تأیید نشد"}؛ بررسی دستی XT لازم است.")
                return ExecutionResult(false,
                    if (partialProtected)
                        "سفارش نیمه‌پر شد؛ طرح‌های MARKET برای حجم پرشده ثبت شد اما توقف اضطراری فعال است. وضعیت پوزیشن را در XT بررسی کن."
                    else
                        "سفارش نیمه‌پر یا مبهم است و حفاظت کامل تأیید نشد؛ توقف اضطراری فعال شد. فوراً XT را بررسی کن.",
                    receipt.orderId)
            }

            // Attach exchange-native MARKET delegates immediately after the confirmed fill.
            client.createTakeProfitStopMarket(
                fresh.symbol, fresh.side, executed.toLong(), fresh.stop, fresh.target
            )

            val fillGeometryValid = averageFillPrice.isFinite() && averageFillPrice > 0.0 &&
                abs(averageFillPrice - fresh.referencePrice) / fresh.referencePrice <= 0.0075 &&
                (if (fresh.side == "LONG") fresh.stop < averageFillPrice && fresh.target > averageFillPrice
                else fresh.stop > averageFillPrice && fresh.target < averageFillPrice)
            val entryNotional = averageFillPrice * executed * fresh.contractSize
            val stopNotional = fresh.stop * executed * fresh.contractSize
            val realizedStopCostEstimate = abs(averageFillPrice - fresh.stop) * executed * fresh.contractSize +
                (entryNotional + stopNotional) * fresh.takerFeeRate + stopNotional * 0.001
            if (!fillGeometryValid || realizedStopCostEstimate > fresh.riskBudgetUsdt * 1.20) {
                tripEmergencyStop(context,
                    "قیمت Fill از محدوده ریسک پیش‌بینی‌شده خارج شد؛ قیمت میانگین=$averageFillPrice و زیان تخمینی تا SL=$realizedStopCostEstimate USDT. پوزیشن را فوراً بررسی کن.")
                return ExecutionResult(false,
                    "Fill با ریسک پیش‌بینی‌شده سازگار نیست؛ توقف اضطراری فعال شد. پوزیشن را در XT بررسی کن.", receipt.orderId)
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
                    (protection.optString("origQty").toDoubleOrNull() ?: Double.NaN) == executed &&
                    protection.optString("profitDelegateOrderType").equals("MARKET", true) &&
                    protection.optString("stopDelegateOrderType").equals("MARKET", true)
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
        if (managed.isEmpty()) return
        if (!XtCredentialVault.hasCredentials(context)) {
            tripEmergencyStop(context, "کلید API برای پایش پوزیشن‌های ثبت‌شده در دسترس نیست؛ وضعیت صرافی را دستی بررسی کن.")
            return
        }
        try {
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
                    if (client.activeTakeProfitStopOrders(symbol).isNotEmpty()) {
                        tripEmergencyStop(context, "برای $symbol پوزیشن فعالی دیده نمی‌شود اما طرح TP/SL هنوز در XT فعال است؛ وضعیت آن را دستی بررسی کن.")
                        return
                    }
                    remainingManaged.remove(symbol)
                    continue
                }
                val protections = client.activeTakeProfitStopOrders(position.symbol)
                val hasProtection = protections.any {
                    normalized(it.optString("symbol")) == symbol &&
                        it.optString("positionSide").equals(position.side, true) &&
                        (it.optString("triggerStopPrice").toDoubleOrNull() ?: 0.0) > 0.0 &&
                        (it.optString("triggerProfitPrice").toDoubleOrNull() ?: 0.0) > 0.0 &&
                        it.optString("profitDelegateOrderType").equals("MARKET", true) &&
                        it.optString("stopDelegateOrderType").equals("MARKET", true) &&
                        (it.optString("origQty").toDoubleOrNull() ?: 0.0) + 1e-8 >= position.quantity
                }
                if (!hasProtection) {
                    tripEmergencyStop(context,
                        "حفاظت TP/SL صرافی برای پوزیشن ${position.symbol} ناقص یا نامشخص است؛ ورود جدید متوقف شد. پوزیشن را فوراً در XT بررسی کن.")
                    return
                }
            }
            p.edit().putStringSet(KEY_MANAGED, remainingManaged).apply()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            tripEmergencyStop(
                context,
                "نظارت بر پوزیشن واقعی به خطا خورد: ${error.message ?: error.javaClass.simpleName}. ورود جدید متوقف شد تا وضعیت XT دوباره تأیید شود."
            )
            throw error
        }
    }

    /**
     * Background auto mode evaluates one ranked candidate per scan. It reuses the
     * same live preflight, fee-aware sizing, account checks, TP/SL verification,
     * and emergency-stop path as manual execution. All gates fail closed.
     */
    suspend fun processScan(context: Context, signals: List<MarketSignal>): ExecutionResult? =
        autoScanMutex.withLock {
            val modelValidated = WolfPretrainedEnsemble.status().validatedForScoring
            if (!XtAutomaticTradingPolicy.mayRun(
                    liveArmed = isEnabled(context),
                    automaticEnabled = prefs(context).getBoolean(KEY_AUTO_ENABLED, false),
                    credentialsPresent = XtCredentialVault.hasCredentials(context),
                    modelValidated = modelValidated,
                    emergencyStop = isEmergencyStopped(context)
                )
            ) return@withLock null

            val candidate = signals.asSequence()
                .filter { signal ->
                    val side = directionSide(signal.direction)
                    side != null &&
                        directionSide(signal.technicalDirection) == side &&
                        signal.confidence >= 70 &&
                        signal.signalTier.isNotBlank() && signal.signalTier != "بدون رتبه" &&
                        signal.technical.adx >= 16.0 && signal.technical.relativeVolume >= 0.65 &&
                        (signal.consensus?.agreement ?: 0) >= 58 &&
                        signal.price.isFinite() && signal.price > 0.0
                }
                .sortedWith(
                    compareByDescending<MarketSignal> { it.confidence }
                        .thenByDescending { it.score }
                        .thenByDescending { it.volume }
                )
                .firstOrNull()
                ?: return@withLock null

            val candleTime = candidate.candles.lastOrNull()?.getOrNull(5)?.toLong() ?: 0L
            if (candleTime <= 0L) return@withLock null
            val signalKey = listOf(
                normalized(candidate.symbol),
                candidate.direction,
                candidate.tradeTimeframe,
                candleTime.toString()
            ).joinToString("|")
            val p = prefs(context)
            if (p.getString("last_auto_signal_key", "") == signalKey) return@withLock null

            val preview = try {
                prepareManual(context, candidate)
            } catch (error: Exception) {
                setStatus(context, "معامله‌گر خودکار: این فرصت از پیش‌بررسی عبور نکرد؛ " + (error.message ?: error.javaClass.simpleName))
                return@withLock null
            }

            // Persist before order submission so restarts or ambiguous responses
            // cannot submit the same candle twice.
            p.edit().putString("last_auto_signal_key", signalKey).apply()
            val result = executeManual(context, candidate, preview)
            setStatus(
                context,
                if (result.success)
                    "اجرای خودکار موفق: " + candidate.symbol + " " + preview.side + "؛ سفارش و حفاظت صرافی تأیید شد."
                else
                    "اجرای خودکار متوقف شد: " + result.message
            )
            result
        }
}
