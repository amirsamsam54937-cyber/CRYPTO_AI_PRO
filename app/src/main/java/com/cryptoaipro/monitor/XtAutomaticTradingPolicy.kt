package com.cryptoaipro.monitor

/** Fail-closed gates for background automatic execution. The unvalidated neural ensemble cannot authorize unattended live orders. */
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
