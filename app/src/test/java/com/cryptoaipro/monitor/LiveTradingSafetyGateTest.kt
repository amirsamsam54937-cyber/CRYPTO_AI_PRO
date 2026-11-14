package com.cryptoaipro.monitor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveTradingSafetyGateTest {
    private fun config() = LiveTradingSafetyGate.Config(
        liveModeEnabled = true, ownerConfirmedLiveRisk = true, killSwitchEngaged = false,
        exchangeId = "xt", exchangeAllowlist = setOf("xt"),
        credentialsAvailableInSecureStore = true
    )
    private fun context() = LiveTradingSafetyGate.Context(
        nowMs = 100_000L, marketDataAtMs = 99_000L, exchangeServerTimeMs = 100_500L,
        entryReferencePrice = 100.0, executablePrice = 100.05,
        equityUsdt = 1_000.0, availableMarginUsdt = 900.0,
        dailyLossPercent = 0.0, peakDrawdownPercent = 0.0,
        portfolioOpenRiskPercent = 0.0, openPositions = 0, maxPositions = 3,
        hasExistingPositionForSymbol = false, neuralModelValidated = true, signalTierApproved = true
    )
    private fun request() = LiveTradingSafetyGate.Request(
        symbol = "BTC/USDT:USDT", side = "BUY", quantity = 0.1, notionalUsdt = 10.0,
        leverage = 2, stopPrice = 98.0, takeProfitPrice = 104.0, riskAtStopUsdt = 0.2,
        clientOrderId = "zoro-test-001"
    )

    @Test fun defaultConfigurationFailsClosed() {
        val decision = LiveTradingSafetyGate.evaluate(LiveTradingSafetyGate.Config(), context(), request())
        assertFalse(decision.allowed)
        assertTrue(decision.reasons.isNotEmpty())
    }

    @Test fun validExplicitlyEnabledRequestPassesGate() {
        assertTrue(LiveTradingSafetyGate.evaluate(config(), context(), request()).allowed)
    }

    @Test fun staleDataAndClockSkewAreRejected() {
        assertFalse(LiveTradingSafetyGate.evaluate(config(), context().copy(marketDataAtMs = 1L), request()).allowed)
        assertFalse(LiveTradingSafetyGate.evaluate(config(), context().copy(exchangeServerTimeMs = 200_000L), request()).allowed)
    }

    @Test fun dailyLossDrawdownAndLeverageLimitsAreEnforced() {
        assertFalse(LiveTradingSafetyGate.evaluate(config(), context().copy(dailyLossPercent = 2.0), request()).allowed)
        assertFalse(LiveTradingSafetyGate.evaluate(config(), context().copy(peakDrawdownPercent = 8.0), request()).allowed)
        assertFalse(LiveTradingSafetyGate.evaluate(config(), context(), request().copy(leverage = 4)).allowed)
    }

    @Test fun duplicateSymbolAndUnvalidatedModelAreRejected() {
        assertFalse(LiveTradingSafetyGate.evaluate(config(), context().copy(hasExistingPositionForSymbol = true), request()).allowed)
        assertFalse(LiveTradingSafetyGate.evaluate(config(), context().copy(neuralModelValidated = false), request()).allowed)
    }

    @Test fun priceDriftAndInvalidProtectionGeometryAreRejected() {
        assertFalse(LiveTradingSafetyGate.evaluate(config(), context().copy(executablePrice = 101.0), request()).allowed)
        assertFalse(LiveTradingSafetyGate.evaluate(config(), context(), request().copy(stopPrice = 101.0)).allowed)
    }
}

class LiveOrderCoordinatorTest {
    private class Journal : LiveOrderCoordinator.LiveOrderJournal {
        val claimed = mutableSetOf<String>()
        val phases = mutableListOf<LiveOrderCoordinator.Phase>()
        var killed = false
        override fun claim(clientOrderId: String) = claimed.add(clientOrderId)
        override fun record(clientOrderId: String, phase: LiveOrderCoordinator.Phase, detail: String) { phases += phase }
        override fun engageKillSwitch(reason: String) { killed = true }
    }
    private class Gateway(var entry: LiveOrderCoordinator.ExchangeResult) : LiveOrderCoordinator.LiveExecutionGateway {
        var protectionOk = true
        var closeCalled = false
        var entryCalls = 0
        override suspend fun submitEntry(request: LiveTradingSafetyGate.Request): LiveOrderCoordinator.ExchangeResult {
            entryCalls++; return entry
        }
        override suspend fun placeProtection(symbol: String, side: String, quantity: Double,
            stopPrice: Double, takeProfitPrice: Double, clientOrderId: String) = protectionOk
        override suspend fun emergencyClose(symbol: String, side: String, quantity: Double, clientOrderId: String): Boolean {
            closeCalled = true; return true
        }
    }
    private fun config() = LiveTradingSafetyGate.Config(
        liveModeEnabled = true, ownerConfirmedLiveRisk = true, killSwitchEngaged = false,
        exchangeId = "xt", exchangeAllowlist = setOf("xt"), credentialsAvailableInSecureStore = true
    )
    private fun context() = LiveTradingSafetyGate.Context(
        100_000L,99_000L,100_500L,100.0,100.05,1000.0,900.0,0.0,0.0,0.0,0,3,false,true,true
    )
    private fun request() = LiveTradingSafetyGate.Request("BTC/USDT:USDT","BUY",0.1,10.0,2,98.0,104.0,0.2,"zoro-001")

    @Test fun duplicateClientOrderIdNeverSubmitsTwice() = runBlocking {
        val journal = Journal()
        val gateway = Gateway(LiveOrderCoordinator.ExchangeResult(LiveOrderCoordinator.Status.FILLED,"ex1",0.1,100.0))
        val coordinator = LiveOrderCoordinator(gateway,journal)
        coordinator.execute(config(),context(),request())
        val second = coordinator.execute(config(),context(),request())
        assertEquals(1,gateway.entryCalls)
        assertEquals(LiveOrderCoordinator.Phase.REJECTED,second.phase)
    }

    @Test fun protectionFailureRequestsEmergencyCloseAndKillsSwitch() = runBlocking {
        val journal = Journal()
        val gateway = Gateway(LiveOrderCoordinator.ExchangeResult(LiveOrderCoordinator.Status.FILLED,"ex1",0.1,100.0))
        gateway.protectionOk = false
        val result = LiveOrderCoordinator(gateway,journal).execute(config(),context(),request())
        assertTrue(gateway.closeCalled)
        assertTrue(journal.killed)
        assertEquals(LiveOrderCoordinator.Phase.HALTED,result.phase)
    }

    @Test fun unknownEntryOutcomeIsNeverRetriedAndHalts() = runBlocking {
        val journal = Journal()
        val gateway = Gateway(LiveOrderCoordinator.ExchangeResult(LiveOrderCoordinator.Status.UNKNOWN,detail="timeout"))
        val result = LiveOrderCoordinator(gateway,journal).execute(config(),context(),request())
        assertEquals(1,gateway.entryCalls)
        assertTrue(journal.killed)
        assertEquals(LiveOrderCoordinator.Phase.UNKNOWN,result.phase)
    }

    @Test fun rejectedEntryDoesNotPlaceProtection() = runBlocking {
        val journal = Journal()
        val gateway = Gateway(LiveOrderCoordinator.ExchangeResult(LiveOrderCoordinator.Status.REJECTED,detail="rejected"))
        val result = LiveOrderCoordinator(gateway,journal).execute(config(),context(),request())
        assertEquals(LiveOrderCoordinator.Phase.REJECTED,result.phase)
        assertFalse(gateway.closeCalled)
    }
}
