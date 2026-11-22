package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLeverageEngineTest {
    private fun input(
        confidence: Int = 90,
        consensus: Int = 90,
        atr: Double = 1.0,
        venueCap: Int? = 20,
        simulation: Boolean = true,
        neuralValidated: Boolean = false
    ) = AdaptiveLeverageEngine.Input(
        direction = "LONG_BIAS",
        confidence = confidence,
        consensusStrength = consensus,
        neuralStrength = 95,
        neuralValidated = neuralValidated,
        neuralDisagreement = false,
        outOfDistribution = !neuralValidated,
        entry = 100.0,
        stop = 98.0,
        tp1 = 104.0,
        atrPercent = atr,
        venueMaxLeverage = venueCap,
        simulation = simulation,
        simulationMaxLeverage = 20
    )

    @Test fun strongerSignalReceivesHigherAdaptiveLeverage() {
        val weak = AdaptiveLeverageEngine.recommend(input(confidence = 35, consensus = 30))
        val strong = AdaptiveLeverageEngine.recommend(input(confidence = 95, consensus = 95))
        assertTrue(strong.leverage > weak.leverage)
        assertTrue(strong.leverage > 3)
    }

    @Test fun leverageIsCappedByVerifiedVenueMaximum() {
        val result = AdaptiveLeverageEngine.recommend(input(venueCap = 7))
        assertTrue(result.leverage <= 7)
    }

    @Test fun unknownLiveVenueLimitFailsClosed() {
        val result = AdaptiveLeverageEngine.recommend(
            input(venueCap = null, simulation = false)
        )
        assertEquals(1, result.leverage)
    }

    @Test fun highVolatilityReducesLeverage() {
        val calm = AdaptiveLeverageEngine.recommend(input(atr = 0.8))
        val volatile = AdaptiveLeverageEngine.recommend(input(atr = 9.0))
        assertTrue(calm.leverage > volatile.leverage)
    }

    @Test fun invalidRiskGeometryDoesNotIncreaseLeverage() {
        val bad = input().copy(stop = 100.0)
        assertEquals(1, AdaptiveLeverageEngine.recommend(bad).leverage)
    }

    @Test fun unvalidatedNeuralOpinionDoesNotContributeToStrength() {
        val unvalidated = AdaptiveLeverageEngine.recommend(input(neuralValidated = false))
        val validated = AdaptiveLeverageEngine.recommend(input(neuralValidated = true))
        assertTrue(validated.leverage >= unvalidated.leverage)
    }

    @Test fun widerStopAndHigherMaintenanceMarginReduceSafeLeverage() {
        val narrowStop = AdaptiveLeverageEngine.recommend(input(neuralValidated = true).copy(stop = 99.0))
        val wideStop = AdaptiveLeverageEngine.recommend(input(neuralValidated = true).copy(stop = 90.0))
        val higherMaintenance = AdaptiveLeverageEngine.recommend(
            input(neuralValidated = true).copy(maintenanceMarginRate = 0.50)
        )
        assertTrue(narrowStop.leverage > wideStop.leverage)
        assertTrue(narrowStop.leverage > higherMaintenance.leverage)
    }
}
