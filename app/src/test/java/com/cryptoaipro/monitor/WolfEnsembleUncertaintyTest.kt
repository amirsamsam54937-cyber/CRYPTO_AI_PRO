package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfEnsembleUncertaintyTest {
    @Test fun unanimousDirectionalVotesProduceHighAgreementAndLowSpread() {
        val result = WolfEnsembleUncertainty.analyze(List(10) { 0.82 })
        assertTrue(result.available)
        assertEquals(10, result.upVotes)
        assertEquals(0, result.downVotes)
        assertEquals(1.0, result.agreementRatio, 1e-9)
        assertEquals(0.0, result.standardDeviation, 1e-9)
        assertFalse(result.highDisagreement)
    }

    @Test fun splitDirectionalVotesAreFlaggedAsHighDisagreement() {
        val result = WolfEnsembleUncertainty.analyze(
            List(5) { 0.80 } + List(5) { 0.20 }
        )
        assertEquals(5, result.upVotes)
        assertEquals(5, result.downVotes)
        assertEquals(0.5, result.agreementRatio, 1e-9)
        assertTrue(result.highDisagreement)
        assertEquals("تقسیم آرا", result.assessment)
    }

    @Test fun neutralSpecialistsDoNotArtificiallyIncreaseDirectionalAgreement() {
        val result = WolfEnsembleUncertainty.analyze(
            List(4) { 0.80 } + List(2) { 0.20 } + List(4) { 0.50 }
        )
        assertEquals(4, result.upVotes)
        assertEquals(2, result.downVotes)
        assertEquals(4, result.neutralVotes)
        assertEquals(6, result.directionalVotes)
        assertEquals(4.0 / 6.0, result.agreementRatio, 1e-9)
        assertTrue(result.highDisagreement)
    }

    @Test fun tenUncertainProbabilitiesAreFlaggedEvenWhenTheirMeanIsDirectional() {
        val result = WolfEnsembleUncertainty.analyze(List(10) { 0.56 })
        assertEquals(10, result.upVotes)
        assertTrue(result.normalizedEntropy > 0.95)
        assertTrue(result.highDisagreement)
    }

    @Test fun invalidOrIncompleteMemberSetFailsClosed() {
        assertFalse(WolfEnsembleUncertainty.analyze(List(9) { 0.8 }).available)
        assertFalse(WolfEnsembleUncertainty.analyze(List(10) { Double.NaN }).available)
        assertTrue(WolfEnsembleUncertainty.analyze(emptyList()).highDisagreement)
    }
}
