package com.cryptoaipro.monitor

/** Fail-closed operational gates. Market intelligence is arbitrated by the independent trading brain. */
internal object XtAutomaticTradingPolicy {
    fun mayRun(
        liveArmed: Boolean,
        automaticEnabled: Boolean,
        credentialsPresent: Boolean,
        emergencyStop: Boolean
    ): Boolean =
        liveArmed && automaticEnabled && credentialsPresent && !emergencyStop
}
