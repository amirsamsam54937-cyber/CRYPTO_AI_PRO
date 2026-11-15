package com.cryptoaipro.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XtExecutionPolicyTest {
    @Test fun shadowModeNeverSendsOrders() {
        val state = XtExecutionPolicy.State(
            mode = XtExecutionPolicy.Mode.SHADOW_PAPER,
            liveArmed = true,
            credentialsVerified = true,
            accountCanTrade = true,
            accountCanOpenPosition = true
        )
        assertFalse(XtExecutionPolicy.maySendOrder(state))
    }

    @Test fun liveRequiresEveryExplicitPrecondition() {
        val ready = XtExecutionPolicy.State(
            mode = XtExecutionPolicy.Mode.LIVE_TRADING,
            liveArmed = true,
            credentialsVerified = true,
            accountCanTrade = true,
            accountCanOpenPosition = true
        )
        assertTrue(XtExecutionPolicy.maySendOrder(ready))
        assertFalse(XtExecutionPolicy.maySendOrder(ready.copy(liveArmed = false)))
        assertFalse(XtExecutionPolicy.maySendOrder(ready.copy(credentialsVerified = false)))
        assertFalse(XtExecutionPolicy.maySendOrder(ready.copy(accountCanTrade = false)))
        assertFalse(XtExecutionPolicy.maySendOrder(ready.copy(accountCanOpenPosition = false)))
        assertFalse(XtExecutionPolicy.maySendOrder(ready.copy(emergencyStop = true)))
    }

    @Test fun liveModeIsNotArmedByDefault() {
        assertFalse(XtExecutionPolicy.maySendOrder(XtExecutionPolicy.State(
            mode = XtExecutionPolicy.Mode.LIVE_TRADING
        )))
    }
}
