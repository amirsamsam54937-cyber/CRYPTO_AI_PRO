package com.cryptoaipro.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfModelInputGuardTest {
    private val mean = DoubleArray(7)
    private val scale = DoubleArray(7) { 1.0 }

    @Test fun normalTrainingLikeFeaturesPassTheDistributionGate() {
        val result = WolfModelInputGuard.assess(DoubleArray(7), mean, scale)
        assertTrue(result.available)
        assertFalse(result.outOfDistribution)
        assertTrue(result.rmsZScore == 0.0)
    }

    @Test fun extremeFeatureShiftTriggersAbstention() {
        val shifted = DoubleArray(7)
        shifted[3] = 7.0
        val result = WolfModelInputGuard.assess(shifted, mean, scale)
        assertTrue(result.available)
        assertTrue(result.outOfDistribution)
        assertTrue(result.maxAbsZScore > 6.0)
    }

    @Test fun broadModerateShiftAlsoTriggersAbstention() {
        val shifted = DoubleArray(7) { 3.2 }
        val result = WolfModelInputGuard.assess(shifted, mean, scale)
        assertTrue(result.outOfDistribution)
        assertTrue(result.rmsZScore > 3.0)
    }

    @Test fun malformedOrNonFiniteFeatureVectorsFailClosed() {
        assertTrue(WolfModelInputGuard.assess(DoubleArray(6), mean, scale).outOfDistribution)
        val invalid = DoubleArray(7)
        invalid[0] = Double.NaN
        assertTrue(WolfModelInputGuard.assess(invalid, mean, scale).outOfDistribution)
        val badScale = DoubleArray(7) { 1.0 }
        badScale[2] = 0.0
        assertTrue(WolfModelInputGuard.assess(DoubleArray(7), mean, badScale).outOfDistribution)
    }
}
