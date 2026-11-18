package com.cryptoaipro.monitor

/** Fail-closed gates for background execution. An unvalidated neural ensemble is excluded from signal authority, but does not block independently gated technical strategies. */
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
