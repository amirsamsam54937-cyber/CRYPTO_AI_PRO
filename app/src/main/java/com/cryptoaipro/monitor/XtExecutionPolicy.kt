package com.cryptoaipro.monitor

/**
 * XT currently has no publicly documented USDT-M futures sandbox endpoint.
 * SHADOW uses XT public market data and the local paper ledger; it never sends
 * authenticated order, leverage, transfer, or withdrawal requests.
 */
internal object XtExecutionPolicy {
    enum class Mode { SHADOW_PAPER, LIVE_LOCKED }

    data class State(
        val mode: Mode = Mode.SHADOW_PAPER,
        val explicitLiveApproval: Boolean = false,
        val testnetEvidenceComplete: Boolean = false,
        val readOnlyConnectionVerified: Boolean = false
    )

    @Suppress("UNUSED_PARAMETER")
    fun maySendOrder(state: State): Boolean = false

    fun label(state: State): String = when {
        maySendOrder(state) -> "LIVE" // Unreachable until the hard lock is deliberately removed after review.
        state.readOnlyConnectionVerified -> "SHADOW • اتصال خواندنی تأیید شد • سفارش واقعی قفل"
        else -> "SHADOW • شبیه‌سازی با داده زنده • سفارش واقعی قفل"
    }
}
