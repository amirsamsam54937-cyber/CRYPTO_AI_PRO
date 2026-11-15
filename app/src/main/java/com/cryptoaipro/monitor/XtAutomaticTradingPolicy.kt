package com.cryptoaipro.monitor

/** Fail-closed gates for background automatic execution. */
internal object XtAutomaticTradingPolicy {
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
            modelValidated &&
            !emergencyStop
}
