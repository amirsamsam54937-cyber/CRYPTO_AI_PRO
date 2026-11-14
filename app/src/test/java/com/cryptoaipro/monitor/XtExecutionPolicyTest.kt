package com.cryptoaipro.monitor

import org.junit.Assert.*
import org.junit.Test

class XtExecutionPolicyTest {
    @Test fun defaultModeNeverSendsOrders() {
        assertFalse(XtExecutionPolicy.maySendOrder(XtExecutionPolicy.State()))
    }

    @Test fun evenAllApprovalFlagsCannotBypassMissingOfficialTestnet() {
        val state = XtExecutionPolicy.State(
            mode = XtExecutionPolicy.Mode.LIVE_LOCKED,
            explicitLiveApproval = true,
            testnetEvidenceComplete = true,
            readOnlyConnectionVerified = true
        )
        assertFalse(XtExecutionPolicy.maySendOrder(state))
        assertTrue(XtExecutionPolicy.label(state).contains("سفارش واقعی قفل"))
    }
}
