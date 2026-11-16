package com.cryptoaipro.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XtAutomaticTradingPolicyTest {
    private fun ready() = XtAutomaticTradingPolicy.mayRun(
        liveArmed = true,
        automaticEnabled = true,
        credentialsPresent = true,
        modelValidated = true,
        emergencyStop = false
    )

    @Test fun allAutomaticGatesMustPass() {
        assertTrue(ready())
        assertFalse(XtAutomaticTradingPolicy.mayRun(true, false, true, true, false))
        assertFalse(XtAutomaticTradingPolicy.mayRun(false, true, true, true, false))
        assertFalse(XtAutomaticTradingPolicy.mayRun(true, true, false, true, false))
        assertFalse(XtAutomaticTradingPolicy.mayRun(true, true, true, false, false))
        assertFalse(XtAutomaticTradingPolicy.mayRun(true, true, true, true, true))
    }

    @Test fun automaticModeIsNeverEnabledByDefault() {
        assertFalse(XtAutomaticTradingPolicy.mayRun(
            liveArmed = false,
            automaticEnabled = false,
            credentialsPresent = false,
            modelValidated = false,
            emergencyStop = false
        ))
    }
}
