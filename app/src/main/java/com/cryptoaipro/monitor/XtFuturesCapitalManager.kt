package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Fee-aware, Futures-only capital allocator for XT USDT-M.
 *
 * This is a deterministic risk engine, not an order sender. It refuses to size a
 * trade when the account's exchange-reported available margin or contract rules
 * are missing. Leverage changes required margin, never the loss budget.
 */
internal object XtFuturesCapitalManager {
    data class Wallet(
        val asset: String,
        val walletBalance: Double,
        val equity: Double,
        val availableMargin: Double,
        val usedMargin: Double,
        val openOrderMargin: Double
    ) {
        fun validate() {
            require(asset.equals("USDT", true))
            require(listOf(walletBalance, equity, availableMargin, usedMargin, openOrderMargin)
                .all { it.isFinite() && it >= 0.0 })
            require(availableMargin <= equity * 1.001) {
                "موجودی آزاد از Equity بیشتر است؛ داده حساب XT ناسازگار است."
            }
        }
    }

    data class PortfolioState(
        val currentEquity: Double,
        val peakEquity: Double,
        val dayStartEquity: Double,
        val consecutiveLosses: Int
    )

    data class RiskPolicy(val allowNewTrades: Boolean, val riskFraction: Double, val reason: String)

    /**
     * Compounding-aware guard: risk is based on current equity, then stepped down
     * as drawdown/loss streak grows. Daily and peak drawdown breakers fail closed.
     */
    fun riskPolicy(state: PortfolioState): RiskPolicy {
        require(listOf(state.currentEquity, state.peakEquity, state.dayStartEquity)
            .all { it.isFinite() && it > 0.0 })
        require(state.consecutiveLosses >= 0)
        val dailyLoss = ((state.dayStartEquity - state.currentEquity) / state.dayStartEquity).coerceAtLeast(0.0)
        val drawdown = ((state.peakEquity - state.currentEquity) / state.peakEquity).coerceAtLeast(0.0)
        if (dailyLoss >= 0.02) return RiskPolicy(false, 0.0, "ترمز زیان روزانه 2% فعال شد؛ معاملات جدید متوقف‌اند.")
        if (drawdown >= 0.08) return RiskPolicy(false, 0.0, "ترمز افت سرمایه 8% فعال شد؛ معاملات جدید متوقف‌اند.")
        if (state.consecutiveLosses >= 5) return RiskPolicy(false, 0.0, "پس از 5 زیان متوالی، سیستم وارد دوره توقف و بازبینی می‌شود.")
        val drawdownRisk = when {
            drawdown >= 0.06 -> 0.001
            drawdown >= 0.04 -> 0.002
            drawdown >= 0.02 -> 0.003
            else -> 0.005
        }
        val streakRisk = when {
            state.consecutiveLosses >= 3 -> 0.002
            state.consecutiveLosses == 2 -> 0.003
            else -> 0.005
        }
        val selected = min(drawdownRisk, streakRisk)
        return RiskPolicy(true, selected, "ریسک پویا بر اساس Equity، افت سرمایه و زنجیره زیان تنظیم شد.")
    }

    data class Fees(
        val makerRate: Double = 0.0004,
        val takerRate: Double = 0.0006,
        val expectedEntryAsTaker: Boolean = true,
        val expectedExitAsTaker: Boolean = true
    ) {
        fun validate() {
            require(makerRate in 0.0..0.02 && takerRate in 0.0..0.02)
        }
        val entryRate get() = if (expectedEntryAsTaker) takerRate else makerRate
        val exitRate get() = if (expectedExitAsTaker) takerRate else makerRate
    }

    data class ContractRules(
        val contractSize: Double,
        val quantityStep: Double,
        val minQuantity: Double,
        val minNotionalUsdt: Double
    ) {
        fun validate() {
            require(contractSize > 0 && quantityStep > 0 && minQuantity > 0 && minNotionalUsdt >= 0)
            require(listOf(contractSize, quantityStep, minQuantity, minNotionalUsdt).all { it.isFinite() })
        }
    }

    data class Request(
        val entry: Double,
        val stop: Double,
        val takeProfit: Double,
        val leverage: Int,
        val riskFraction: Double = 0.005,
        val maxMarginFraction: Double = 0.10,
        val expectedFundingRatePer8h: Double = 0.0,
        val expectedHoldingHours: Double = 8.0,
        val slippageRatePerSide: Double = 0.0005,
        val extraSafetyBuffer: Double = 0.10
    )

    data class Plan(
        val contracts: Double,
        val notionalUsdt: Double,
        val marginRequiredUsdt: Double,
        val riskBudgetUsdt: Double,
        val worstCaseStopCostUsdt: Double,
        val entryFeeUsdt: Double,
        val exitFeeUsdt: Double,
        val estimatedFundingCostUsdt: Double,
        val estimatedRoundTripSlippageUsdt: Double,
        val estimatedNetTargetProfitUsdt: Double,
        val netRewardRisk: Double,
        val capitalUseFraction: Double
    )

    data class Decision(val allowed: Boolean, val reason: String, val plan: Plan? = null)

    /**
     * Uses the standard XT futures schedule as a conservative baseline (maker 2bp,
     * taker 6bp). Account/VIP-specific actual fills must replace estimates in the
     * realized ledger after each execution. Funding is costed by absolute rate so
     * a possible funding receipt is never counted as guaranteed profit.
     */
    fun plan(wallet: Wallet, fees: Fees, rules: ContractRules, request: Request): Decision {
        return try {
            wallet.validate()
            fees.validate()
            rules.validate()
            validateRequest(request)
            if (wallet.equity <= 0.0 || wallet.availableMargin <= 0.0)
                return Decision(false, "Equity یا مارجین آزاد حساب Futures صفر است.")
            val stopDistance = abs(request.entry - request.stop) / request.entry
            val isLong = request.stop < request.entry
            if ((isLong && request.takeProfit <= request.entry) ||
                (!isLong && request.takeProfit >= request.entry))
                return Decision(false, "جهت حدضرر و حدسود با جهت معامله سازگار نیست.")
            val riskBudget = wallet.equity * request.riskFraction
            val maxMargin = min(wallet.availableMargin, wallet.equity * request.maxMarginFraction)
            val maxNotional = maxMargin * request.leverage
            val stopMove = abs(request.entry - request.stop) * rules.contractSize
            val targetMove = abs(request.takeProfit - request.entry) * rules.contractSize
            val feePerContract = (request.entry * fees.entryRate +
                request.stop * fees.exitRate) * rules.contractSize
            val slipPerContract = (request.entry + request.stop) *
                request.slippageRatePerSide * rules.contractSize
            val fundingPerContract = request.entry * rules.contractSize *
                abs(request.expectedFundingRatePer8h) *
                (request.expectedHoldingHours / 8.0)
            val lossPerContract = stopMove + feePerContract + slipPerContract + fundingPerContract
            if (lossPerContract <= 0.0) return Decision(false, "هزینه ریسک قابل محاسبه نیست.")
            val rawByRisk = riskBudget / (lossPerContract * (1.0 + request.extraSafetyBuffer))
            val rawByMargin = maxNotional / (request.entry * rules.contractSize)
            val rawContracts = min(rawByRisk, rawByMargin)
            val units = floor(rawContracts / rules.quantityStep) * rules.quantityStep
            if (!units.isFinite() || units < rules.minQuantity)
                return Decision(false, "موجودی برای حداقل حجم مجاز قرارداد با رعایت ریسک کافی نیست.")
            val notional = units * request.entry * rules.contractSize
            if (notional < rules.minNotionalUsdt)
                return Decision(false, "ارزش سفارش از حداقل ارزش مجاز XT کمتر است.")
            val margin = notional / request.leverage
            val entryFee = notional * fees.entryRate
            val exitFee = units * request.stop * rules.contractSize * fees.exitRate
            val funding = units * fundingPerContract
            val slippage = units * slipPerContract
            val stopCost = units * lossPerContract
            if (margin > maxMargin * 1.000001 || stopCost > riskBudget * (1.0 + request.extraSafetyBuffer) * 1.000001)
                return Decision(false, "حجم سفارش از سقف مارجین یا ریسک مجاز عبور می‌کند.")
            val targetGross = units * targetMove
            val targetExitFee = units * request.takeProfit * rules.contractSize * fees.exitRate
            val targetFunding = funding
            val targetSlippage = units * (request.entry + request.takeProfit) *
                request.slippageRatePerSide * rules.contractSize
            val netTarget = targetGross - entryFee - targetExitFee - targetFunding - targetSlippage
            val netRewardRisk = if (stopCost > 0) netTarget / stopCost else 0.0
            if (!netTarget.isFinite() || netTarget <= 0.0)
                return Decision(false, "پس از کسر کارمزد، Funding و لغزش قیمت، سود هدف منفی است.")
            if (netRewardRisk < 1.2)
                return Decision(false, "نسبت سود به زیان خالص پس از هزینه‌ها کمتر از 1.2 است.")
            Decision(true, "حجم با موجودی آزاد، حداقل قرارداد و هزینه‌های XT سازگار است.", Plan(
                units, notional, margin, riskBudget, stopCost, entryFee, exitFee, funding,
                slippage, netTarget, netRewardRisk, margin / wallet.equity
            ))
        } catch (e: IllegalArgumentException) {
            Decision(false, e.message ?: "پارامترهای مدیریت سرمایه نامعتبرند.")
        }
    }

    private fun validateRequest(r: Request) {
        require(listOf(r.entry, r.stop, r.takeProfit, r.riskFraction, r.maxMarginFraction,
            r.expectedFundingRatePer8h, r.expectedHoldingHours, r.slippageRatePerSide,
            r.extraSafetyBuffer).all { it.isFinite() })
        require(r.entry > 0 && r.stop > 0 && r.takeProfit > 0 && r.entry != r.stop)
        require(r.leverage in 1..3) { "اهرم از سقف محافظه‌کارانه 3x بیشتر است." }
        require(r.riskFraction in 0.001..0.01)
        require(r.maxMarginFraction in 0.02..0.15)
        require(abs(r.expectedFundingRatePer8h) <= 0.02)
        require(r.expectedHoldingHours in 0.0..168.0)
        require(r.slippageRatePerSide in 0.0..0.02)
        require(r.extraSafetyBuffer in 0.0..0.5)
    }
}
