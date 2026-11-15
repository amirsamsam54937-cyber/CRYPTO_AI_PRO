package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WolfAdaptiveMemberWeightsTest {
    private fun stats(samples: Int, brier: Double) =
        WolfNeuralForecastJournal.MemberStats(1, samples, 0, 0.0, brier, 0.0, 0.0)

    @Test fun insufficientHistoryKeepsUniformWeights() {
        val weights = WolfNeuralForecastJournal.adaptiveMemberWeights(List(10) { stats(29, 0.05) })
        assertEquals(10, weights.size)
        assertTrue(weights.all { it == 1.0 })
    }

    @Test fun betterValidatedMembersReceiveBoundedHigherWeight() {
        val members = List(10) { index ->
            stats(200, if (index == 0) 0.08 else 0.20)
        }
        val weights = WolfNeuralForecastJournal.adaptiveMemberWeights(members)
        assertTrue(weights[0] > weights[1])
        assertTrue(weights.all { it in 0.75..1.25 })
        assertEquals(1.0, weights.average(), 1e-9)
    }

    @Test fun lowSampleMemberIsNotPromoted() {
        val members = List(10) { index ->
            if (index == 0) stats(5, 0.001) else stats(150, 0.20)
        }
        val weights = WolfNeuralForecastJournal.adaptiveMemberWeights(members)
        assertTrue(weights[0] <= weights[1])
        assertTrue(weights.all { it in 0.75..1.25 })
    }

    @Test fun malformedMemberCountFailsClosedToUniformWeights() {
        val weights = WolfNeuralForecastJournal.adaptiveMemberWeights(List(9) { stats(100, 0.1) })
        assertEquals(List(10) { 1.0 }, weights)
    }
}
