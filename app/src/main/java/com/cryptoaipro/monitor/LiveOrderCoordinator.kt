package com.cryptoaipro.monitor

/**
 * Exchange-neutral execution state machine. A concrete exchange adapter must implement
 * reduce-only protective orders and exchange-side client-order-id idempotency.
 * Unknown submit outcomes are NEVER blindly retried.
 */
internal class LiveOrderCoordinator(
    private val gateway: LiveExecutionGateway,
    private val journal: LiveOrderJournal
) {
    enum class Status { FILLED, PARTIALLY_FILLED, REJECTED, UNKNOWN }
    enum class Phase { ENTRY_SUBMITTED, PROTECTED, REJECTED, UNKNOWN, EMERGENCY_CLOSE_REQUESTED, HALTED }

    data class ExchangeResult(
        val status: Status,
        val exchangeOrderId: String? = null,
        val filledQuantity: Double = 0.0,
        val averagePrice: Double = 0.0,
        val detail: String = ""
    )

    data class Outcome(val phase: Phase, val detail: String)

    interface LiveExecutionGateway {
        /** Must use a deterministic clientOrderId and query before any retry. */
        suspend fun submitEntry(request: LiveTradingSafetyGate.Request): ExchangeResult
        /** Must place exchange-native STOP_MARKET and TAKE_PROFIT_MARKET, both reduce-only. */
        suspend fun placeProtection(symbol: String, side: String, quantity: Double,
                                    stopPrice: Double, takeProfitPrice: Double,
                                    clientOrderId: String): Boolean
        /** Must submit reduce-only close for the actual remaining position quantity. */
        suspend fun emergencyClose(symbol: String, side: String, quantity: Double,
                                   clientOrderId: String): Boolean
    }

    interface LiveOrderJournal {
        /** Atomic compare-and-set: false means this clientOrderId was already claimed. */
        fun claim(clientOrderId: String): Boolean
        fun record(clientOrderId: String, phase: Phase, detail: String)
        fun engageKillSwitch(reason: String)
    }

    suspend fun execute(
        config: LiveTradingSafetyGate.Config,
        context: LiveTradingSafetyGate.Context,
        request: LiveTradingSafetyGate.Request
    ): Outcome {
        val decision = LiveTradingSafetyGate.evaluate(config, context, request)
        if (!decision.allowed) return Outcome(Phase.REJECTED, decision.reasons.joinToString("; "))
        if (!journal.claim(request.clientOrderId))
            return Outcome(Phase.REJECTED, "Duplicate client order id; no order submitted")
        val entry = try {
            gateway.submitEntry(request)
        } catch (error: Exception) {
            journal.record(request.clientOrderId, Phase.UNKNOWN, "Entry outcome unknown: ${error.message}")
            journal.engageKillSwitch("Unknown entry outcome; reconcile with exchange before any new order")
            return Outcome(Phase.UNKNOWN, "Entry outcome unknown. Trading halted; reconcile exchange state manually.")
        }
        if (entry.status == Status.UNKNOWN) {
            journal.record(request.clientOrderId, Phase.UNKNOWN, entry.detail)
            journal.engageKillSwitch("Exchange returned unknown entry status")
            return Outcome(Phase.UNKNOWN, "Exchange status unknown. No retry; trading halted for reconciliation.")
        }
        if (entry.status == Status.REJECTED || entry.filledQuantity <= 0.0) {
            journal.record(request.clientOrderId, Phase.REJECTED, entry.detail)
            return Outcome(Phase.REJECTED, entry.detail.ifBlank { "Entry rejected or not filled" })
        }
        if (!entry.filledQuantity.isFinite() || entry.filledQuantity > request.quantity * 1.000001 ||
            !entry.averagePrice.isFinite() || entry.averagePrice <= 0.0) {
            journal.engageKillSwitch("Invalid fill report; manual reconciliation required")
            journal.record(request.clientOrderId, Phase.HALTED, "Invalid fill report")
            return Outcome(Phase.HALTED, "Invalid fill report; trading halted")
        }
        val protected = try {
            gateway.placeProtection(request.symbol, request.side, entry.filledQuantity,
                request.stopPrice, request.takeProfitPrice, request.clientOrderId + "-protect")
        } catch (_: Exception) { false }
        if (protected) {
            journal.record(request.clientOrderId, Phase.PROTECTED,
                "Entry filled ${entry.filledQuantity}; exchange-native reduce-only stop and target confirmed")
            return Outcome(Phase.PROTECTED, "Entry filled and protective orders confirmed")
        }
        journal.record(request.clientOrderId, Phase.EMERGENCY_CLOSE_REQUESTED,
            "Protection failed; emergency reduce-only close requested")
        val closeConfirmed = try {
            gateway.emergencyClose(request.symbol,
                if (request.side == "BUY") "SELL" else "BUY",
                entry.filledQuantity, request.clientOrderId + "-emergency-close")
        } catch (_: Exception) { false }
        journal.engageKillSwitch("Protective order placement failed")
        val phase = if (closeConfirmed) Phase.HALTED else Phase.HALTED
        journal.record(request.clientOrderId, phase,
            if (closeConfirmed) "Emergency close accepted; verify flat position on exchange"
            else "Emergency close not confirmed; immediate manual exchange intervention required")
        return Outcome(phase, if (closeConfirmed)
            "Protection failed; emergency close requested. Verify position is flat."
        else "CRITICAL: protection and emergency close unconfirmed. Immediately inspect exchange position.")
    }
}
