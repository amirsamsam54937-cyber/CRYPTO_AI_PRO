package com.cryptoaipro.monitor

/** Fail-closed gates for background execution. Only a validated neural ensemble may authorize it. */
internal object XtAutomaticTradingPolicy {
    fun mayRun(
        liveArmed: Boolean,
        automaticEnabled: Boolean,
        credentialsPresent: Boolean,
        modelValidated: Boolean,
        emergencyStop: Boolean
    ): Boolean =
        liveArmed && automaticEnabled && credentialsPresent && modelValidated && !emergencyStop
}
