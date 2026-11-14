package com.cryptoaipro.monitor

/**
 * Fail-closed authorization gate for real-money entries.
 * Defaults are deliberately non-live. This gate is not an exchange adapter.
 */
internal object LiveTradingSafetyGate {
    data class Config(
        val liveModeEnabled: Boolean = false,
        val ownerConfirmedLiveRisk: Boolean = false,
        val killSwitchEngaged: Boolean = true,
        val exchangeId: String = "",
        val exchangeAllowlist: Set<String> = emptySet(),
        val credentialsAvailableInSecureStore: Boolean = false,
        val maxLeverage: Int = 3,
        val maxDailyLossPercent: Double = 2.0,
        val maxPeakDrawdownPercent: Double = 8.0,
        val maxPortfolioOpenRiskPercent: Double = 2.5,
        val maxMarketDataAgeMs: Long = 5_000L,
        val maxClockSkewMs: Long = 5_000L,
        val maxEntryPriceDeviationPercent: Double = 0.20,
        val minNotionalUsdt: Double = 5.0
    )

    data class Context(
        val nowMs: Long,
        val marketDataAtMs: Long,
        val exchangeServerTimeMs: Long,
        val entryReferencePrice: Double,
        val executablePrice: Double,
        val equityUsdt: Double,
        val availableMarginUsdt: Double,
        val dailyLossPercent: Double,
        val peakDrawdownPercent: Double,
        val portfolioOpenRiskPercent: Double,
        val openPositions: Int,
        val maxPositions: Int,
        val hasExistingPositionForSymbol: Boolean,
        val neuralModelValidated: Boolean,
        val signalTierApproved: Boolean
    )

    data class Request(
        val symbol: String,
        val side: String,
        val quantity: Double,
        val notionalUsdt: Double,
        val leverage: Int,
        val stopPrice: Double,
        val takeProfitPrice: Double,
        val riskAtStopUsdt: Double,
        val clientOrderId: String
    )

    data class Decision(val allowed: Boolean, val reasons: List<String>)

    fun evaluate(config: Config, context: Context, request: Request): Decision {
        val reasons = mutableListOf<String>()
        fun reject(reason: String) { reasons += reason }
        if (!config.liveModeEnabled) reject("LIVE mode is disabled")
        if (!config.ownerConfirmedLiveRisk) reject("Owner has not explicitly confirmed live-trading risk")
        if (config.killSwitchEngaged) reject("Emergency kill switch is engaged")
        if (config.exchangeId.isBlank() || config.exchangeId !in config.exchangeAllowlist)
            reject("Exchange is not explicitly allowlisted")
        if (!config.credentialsAvailableInSecureStore) reject("No credentials in secure storage")
        if (config.maxLeverage !in 1..3 || request.leverage !in 1..config.maxLeverage)
            reject("Leverage exceeds hard safety cap")
        if (config.maxDailyLossPercent !in 0.1..2.0 || !context.dailyLossPercent.isFinite() ||
            context.dailyLossPercent >= config.maxDailyLossPercent) reject("Daily loss circuit breaker")
        if (config.maxPeakDrawdownPercent !in 1.0..8.0 || !context.peakDrawdownPercent.isFinite() ||
            context.peakDrawdownPercent >= config.maxPeakDrawdownPercent) reject("Peak drawdown circuit breaker")
        if (config.maxPortfolioOpenRiskPercent !in 0.1..2.5 || !context.portfolioOpenRiskPercent.isFinite() ||
            context.portfolioOpenRiskPercent >= config.maxPortfolioOpenRiskPercent) reject("Portfolio risk cap reached")
        if (context.openPositions < 0 || context.openPositions >= context.maxPositions || context.maxPositions !in 1..3)
            reject("Open-position limit reached")
        if (context.hasExistingPositionForSymbol) reject("Position already exists for symbol")
        if (!context.neuralModelValidated || !context.signalTierApproved)
            reject("Signal/model has not passed independent authorization criteria")
        val dataAge = context.nowMs - context.marketDataAtMs
        if (dataAge < 0L || dataAge > config.maxMarketDataAgeMs) reject("Market data is stale or timestamp is invalid")
        val clockSkew = kotlin.math.abs(context.exchangeServerTimeMs - context.nowMs)
        if (clockSkew > config.maxClockSkewMs) reject("Exchange clock skew exceeds limit")
        if (request.symbol.isBlank() || request.clientOrderId.isBlank()) reject("Missing symbol or idempotency key")
        if (request.side !in setOf("BUY", "SELL")) reject("Invalid order side")
        if (listOf(request.quantity, request.notionalUsdt, request.stopPrice, request.takeProfitPrice,
                request.riskAtStopUsdt, context.entryReferencePrice, context.executablePrice,
                context.equityUsdt, context.availableMarginUsdt).any { !it.isFinite() || it <= 0.0 })
            reject("Invalid or non-positive order/risk values")
        if (request.notionalUsdt < config.minNotionalUsdt) reject("Order is below configured minimum notional")
        if (request.notionalUsdt > context.availableMarginUsdt * request.leverage * 0.15)
            reject("Order exceeds margin allocation cap")
        if (request.riskAtStopUsdt > context.equityUsdt * 0.005)
            reject("Per-trade stop risk exceeds 0.50% of equity")
        val deviation = kotlin.math.abs(context.executablePrice - context.entryReferencePrice) /
            context.entryReferencePrice * 100.0
        if (!deviation.isFinite() || deviation > config.maxEntryPriceDeviationPercent)
            reject("Entry price moved beyond slippage guard")
        val geometryValid = if (request.side == "BUY")
            request.stopPrice < context.executablePrice && request.takeProfitPrice > context.executablePrice
        else request.stopPrice > context.executablePrice && request.takeProfitPrice < context.executablePrice
        if (!geometryValid) reject("Stop/target geometry does not protect the requested side")
        return Decision(reasons.isEmpty(), reasons)
    }
}
