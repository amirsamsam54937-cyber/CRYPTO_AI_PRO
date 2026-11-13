package com.cryptoaipro.monitor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Live XT execution coordinator. It only trades 5m setups that have a Commander-approved
 * direction, fresh order-flow confirmation and a confirmed liquidity sweep/reclaim.
 * Every entry includes exchange-native TP/SL trigger prices in the opening request.
 */
internal object XtLiveTradingEngine {
    private const val PREFS = "zoro_live_trade_state"
    private const val MAX_POSITIONS = 3
    private const val MAX_DAILY_TRADES = 10
    private const val DAILY_LOSS_LIMIT_PCT = 2.0
    private const val MIN_CONFIDENCE = 66

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean("live_execution_enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("live_execution_enabled", enabled).apply()
        ZoroDiagnostics.record(context, "LIVE_EXECUTION", "WARNING",
            if (enabled) "اجرای واقعی فعال شد؛ شروع سفارش فقط با سرویس پس‌زمینه و کلید معتبر XT" else "اجرای واقعی متوقف شد؛ پایش پوزیشن‌های باز ادامه می‌یابد")
    }

    private fun utcDay(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(now))

    private fun ensureDailyBaseline(context: Context, equity: Double) {
        val p = prefs(context)
        val today = utcDay(System.currentTimeMillis())
        if (p.getString("day", null) != today) {
            p.edit().putString("day", today)
                .putFloat("day_start_equity", equity.toFloat())
                .putInt("daily_trades", 0).apply()
        }
    }

    private fun savePositions(context: Context, positions: List<XtLivePosition>) {
        val array = JSONArray()
        positions.forEach { p ->
            array.put(JSONObject()
                .put("symbol", p.symbol).put("side", p.side)
                .put("quantity", p.quantity).put("availableCloseQuantity", p.availableCloseQuantity)
                .put("entryPrice", p.entryPrice).put("markPrice", p.markPrice)
                .put("leverage", p.leverage).put("contractSize", p.contractSize))
        }
        prefs(context).edit().putString("open_positions", array.toString())
            .putLong("positions_synced_at", System.currentTimeMillis()).apply()
    }

    private fun metadata(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString("position_protection", "{}")) }
            .getOrDefault(JSONObject())

    private fun storeProtection(context: Context, symbol: String, side: String, stop: Double, takeProfit: Double) {
        val p = prefs(context)
        val all = metadata(context)
        val key = symbol.filter(Char::isLetterOrDigit).lowercase() + "_" + side
        all.put(key, JSONObject().put("stop", stop).put("takeProfit", takeProfit)
            .put("createdAt", System.currentTimeMillis()))
        p.edit().putString("position_protection", all.toString()).apply()
    }

    private fun protectionFor(context: Context, position: XtLivePosition): JSONObject? {
        val key = position.symbol.filter(Char::isLetterOrDigit).lowercase() + "_" + position.side
        return metadata(context).optJSONObject(key)
    }

    suspend fun processScan(context: Context, signals: List<MarketSignal>) = withContext(Dispatchers.IO) {
        if (!XtCredentialVault.hasCredentials(context)) return@withContext
        val client = XtAuthenticatedFuturesClient(context)
        try {
            val account = client.accountSnapshot()
            val positions = client.activePositions()
            savePositions(context, positions)
            ensureDailyBaseline(context, account.equityUsdt)
            if (!isEnabled(context)) return@withContext
            if (!account.canTrade || !account.canOpenPosition) {
                ZoroDiagnostics.record(context, "XT_LIVE", "ERROR", "حساب XT مجوز معامله یا بازکردن پوزیشن ندارد؛ ورود جدید متوقف شد")
                return@withContext
            }
            val p = prefs(context)
            val startEquity = p.getFloat("day_start_equity", account.equityUsdt.toFloat()).toDouble()
            val dailyLossPct = if (startEquity > 0.0)
                ((startEquity - account.equityUsdt) / startEquity * 100.0).coerceAtLeast(0.0) else 100.0
            val tradesToday = p.getInt("daily_trades", 0)
            if (dailyLossPct >= DAILY_LOSS_LIMIT_PCT || tradesToday >= MAX_DAILY_TRADES) {
                ZoroDiagnostics.record(context, "XT_LIVE", "WARNING",
                    "توقف ورود جدید؛ زیان روزانه ${"%.2f".format(Locale.US, dailyLossPct)}٪؛ تعداد معاملات امروز $tradesToday")
                return@withContext
            }
            if (positions.size >= MAX_POSITIONS || account.availableBalanceUsdt <= 0.0) return@withContext

            val candidates = signals.asSequence()
                .filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
                .filter { it.signalTier != "بدون رتبه" && it.confidence >= MIN_CONFIDENCE }
                .filter { it.tradeTimeframe.startsWith("5m") }
                .filter { it.technical.adx >= 16.0 && it.technical.relativeVolume >= 0.65 }
                .filter { it.hiddenFlowFresh && if (it.direction == "LONG_BIAS") it.hiddenFlowScore >= 4 else it.hiddenFlowScore <= -4 }
                .filter {
                    val sweep = it.technical.liquiditySweep
                    if (it.direction == "LONG_BIAS") sweep.contains("زیر کف")
                    else sweep.contains("بالای سقف")
                }
                .sortedByDescending { it.confidence }
                .toList()

            for (signal in candidates) {
                if (positions.size >= MAX_POSITIONS || p.getInt("daily_trades", 0) >= MAX_DAILY_TRADES) break
                val side = if (signal.direction == "LONG_BIAS") "LONG" else "SHORT"
                val symbol = signal.symbol.lowercase().replace("-", "_").replace("/", "_").removeSuffix(":usdt")
                    .let { if (it.endsWith("_usdt")) it else "${it}_usdt" }
                val normalizedKey = symbol.filter(Char::isLetterOrDigit)
                if (positions.any { it.symbol.filter(Char::isLetterOrDigit).lowercase() == normalizedKey }) continue
                val lastEntry = p.getLong("last_entry_$normalizedKey", 0L)
                if (System.currentTimeMillis() - lastEntry < 15 * 60_000L) continue

                val entry = signal.entry
                val stop = signal.stop
                val target = signal.tp1
                val geometry = if (side == "LONG") stop < entry && target > entry else target < entry && stop > entry
                if (!geometry || entry <= 0.0) continue

                val spec = try {
                    client.contractSpec(symbol)
                } catch (error: Exception) {
                    ZoroDiagnostics.record(context, "XT_LIVE", "WARNING", "مشخصات قرارداد $symbol دریافت نشد: ${error.message}")
                    continue
                }
                val feeAndSlip = spec.takerFeeRate + 0.0005
                val riskDistance = abs(entry - stop) + (entry + stop) * feeAndSlip
                val rewardDistance = abs(target - entry) - (entry + target) * feeAndSlip
                val netRr = if (riskDistance > 0.0) rewardDistance / riskDistance else 0.0
                if (!netRr.isFinite() || netRr < 1.8) continue

                val leverage = signal.leverage.coerceIn(1, 3)
                val sizing = try {
                    client.sizeForRisk(
                        equityUsdt = account.equityUsdt,
                        availableUsdt = account.availableBalanceUsdt,
                        entry = entry,
                        stop = stop,
                        contractSize = spec.contractSize,
                        leverage = leverage,
                        takerFeeRate = spec.takerFeeRate,
                        slippageRate = 0.0005
                    )
                } catch (error: Exception) {
                    ZoroDiagnostics.record(context, "XT_LIVE", "INFO", "حجم $symbol به حداقل قابل‌معامله نرسید: ${error.message}")
                    continue
                }
                val notional = sizing.contracts * entry * spec.contractSize
                if (sizing.contracts < spec.minQty || notional < spec.minNotional) continue

                val result = runCatching {
                    client.openMarketWithProtection(
                        symbol = symbol, side = side, contracts = sizing.contracts,
                        leverage = leverage, entryPrice = entry,
                        takeProfit = target, stopLoss = stop
                    )
                }.getOrElse {
                    ZoroDiagnostics.record(context, "XT_LIVE", "ERROR",
                        "سفارش $symbol به‌دلیل رد درخواست یا خطای حفاظتی ارسال/تأیید نشد: ${it.message}")
                    continue
                }
                storeProtection(context, symbol, side, stop, target)
                p.edit().putLong("last_entry_$normalizedKey", System.currentTimeMillis())
                    .putInt("daily_trades", p.getInt("daily_trades", 0) + 1)
                    .putString("last_order_result", result.toString()).apply()
                ZoroDiagnostics.record(context, "XT_LIVE", "WARNING",
                    "درخواست ورود واقعی $side $symbol ثبت شد؛ قرارداد: ${sizing.contracts}؛ اهرم: ${leverage}x؛ حدضرر و حدسود در درخواست سفارش ضمیمه شدند؛ R خالص تخمینی: ${"%.2f".format(Locale.US, netRr)}")
                break
            }
        } catch (error: Exception) {
            ZoroDiagnostics.record(context, "XT_LIVE", "ERROR", "چرخه معامله واقعی: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * Reconcile positions every few seconds while the foreground service is alive.
     * Existing positions are restored from XT on every cycle, even after app relaunch.
     */
    suspend fun monitorOpenPositions(context: Context) = withContext(Dispatchers.IO) {
        if (!XtCredentialVault.hasCredentials(context)) return@withContext
        val client = XtAuthenticatedFuturesClient(context)
        try {
            val positions = client.activePositions()
            savePositions(context, positions)
            for (position in positions) {
                val local = protectionFor(context, position)
                if (local == null) {
                    ZoroDiagnostics.record(context, "XT_POSITION", "INFO",
                        "پوزیشن موجود در XT شناسایی شد: ${position.symbol} ${position.side}؛ چون حدضرر/حدسود آن در دفتر ZORO ثبت نشده، بدون تغییر باقی ماند")
                    continue
                }
                val stop = local.optDouble("stop")
                val target = local.optDouble("takeProfit")
                if (stop <= 0.0 || target <= 0.0) continue
                val plans = try {
                    client.activeTriggerPlans(position.symbol)
                } catch (error: Exception) {
                    ZoroDiagnostics.record(context, "XT_POSITION", "ERROR", "بررسی سفارش‌های حفاظتی ${position.symbol} ناموفق بود: ${error.message}")
                    continue
                }
                val hasStop = plans.any { it.optString("entrustType") in setOf("STOP_MARKET", "STOP") && it.optString("positionSide").equals(position.side, true) }
                val hasTakeProfit = plans.any { it.optString("entrustType") in setOf("TAKE_PROFIT_MARKET", "TAKE_PROFIT") && it.optString("positionSide").equals(position.side, true) }
                if (!hasStop) {
                    val stopAlreadyCrossed = if (position.side == "LONG") position.markPrice <= stop else position.markPrice >= stop
                    val repaired = runCatching { client.createProtectionPlan(position, "STOP_MARKET", stop) }
                    if (repaired.isFailure && stopAlreadyCrossed) {
                        runCatching { client.emergencyClose(position) }
                            .onSuccess { ZoroDiagnostics.record(context, "XT_POSITION", "ERROR", "حدضرر صرافی موجود نبود و قیمت از حد عبور کرد؛ فرمان بستن اضطراری ${position.symbol} ارسال شد") }
                            .onFailure { ZoroDiagnostics.record(context, "XT_POSITION", "ERROR", "حدضرر خراب و بستن اضطراری ناموفق: ${it.message}") }
                    } else if (repaired.isSuccess) {
                        ZoroDiagnostics.record(context, "XT_POSITION", "WARNING", "حدضرر گمشده برای ${position.symbol} دوباره در XT ثبت شد")
                    } else {
                        ZoroDiagnostics.record(context, "XT_POSITION", "ERROR", "حدضرر ${position.symbol} در XT موجود نیست و بازیابی آن ناموفق بود")
                    }
                }
                if (!hasTakeProfit) {
                    runCatching { client.createProtectionPlan(position, "TAKE_PROFIT_MARKET", target) }
                        .onSuccess { ZoroDiagnostics.record(context, "XT_POSITION", "WARNING", "حدسود گمشده برای ${position.symbol} دوباره در XT ثبت شد") }
                        .onFailure { ZoroDiagnostics.record(context, "XT_POSITION", "ERROR", "بازیابی حدسود ${position.symbol} ناموفق بود: ${it.message}") }
                }
            }
        } catch (error: Exception) {
            ZoroDiagnostics.record(context, "XT_POSITION", "ERROR", "بازیابی پوزیشن‌های XT ناموفق بود: ${error.message}")
        }
    }
}
