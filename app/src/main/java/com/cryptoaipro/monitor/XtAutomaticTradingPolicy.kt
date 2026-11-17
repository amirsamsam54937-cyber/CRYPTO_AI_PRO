package com.cryptoaipro.monitor

/** Fail-closed gates for background automatic execution. Neural validation is advisory, not an execution lock. */
internal object XtAutomaticTradingPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun mayRun(
        liveArmed: Boolean,
        automaticEnabled: Boolean,
        credentialsPresent: Boolean,
        modelValidated: Boolean,
        emergencyStop: Boolean
    ): Boolean =
        liveArmed &&
            automaticEnabled &&
            credentialsPresent &&
            !emergencyStop
}
