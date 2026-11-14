package com.cryptoaipro.monitor

/**
 * Explicit in-app execution gate. This policy only decides whether the engine may
 * attempt a trade; it does not prove that an exchange order or its protective
 * trigger plans were accepted. The engine must verify those independently.
 */
internal object XtExecutionPolicy {
    enum class Mode { SHADOW_PAPER, LIVE_TRADING }

    data class State(
        val mode: Mode = Mode.SHADOW_PAPER,
        val liveArmed: Boolean = false,
        val credentialsVerified: Boolean = false,
        val accountCanTrade: Boolean = false,
        val accountCanOpenPosition: Boolean = false,
        val emergencyStop: Boolean = false
    )

    fun maySendOrder(state: State): Boolean =
        state.mode == Mode.LIVE_TRADING &&
            state.liveArmed &&
            state.credentialsVerified &&
            state.accountCanTrade &&
            state.accountCanOpenPosition &&
            !state.emergencyStop

    fun label(state: State): String = when {
        maySendOrder(state) -> "LIVE • فعال‌سازی صریح کاربر"
        state.emergencyStop -> "توقف اضطراری • سفارش جدید ممنوع"
        state.mode == Mode.LIVE_TRADING -> "LIVE • آماده نیست • بررسی پیش‌نیازها"
        state.credentialsVerified -> "SHADOW • اتصال خواندنی تأیید شد"
        else -> "SHADOW • شبیه‌سازی با داده زنده"
    }
}
