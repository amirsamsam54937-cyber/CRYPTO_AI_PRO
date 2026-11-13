package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.min

/**
 * Deterministic risk governor for the autonomous paper pilot.
 *
 * Signal confidence is a filter, not a probability of profit. Neural P(UP) is
 * directional evidence only and is accepted only when the ensemble is validated.
 * Leverage is reduced as volatility rises; it never increases the risk budget.
 */
internal object DynamicTradingPolicy {
    data class Input(
        val direction: String,
        val confidence: Int,
        val neuralUpProbability: Double,
        val neuralValidated: Boolean,
        val consensusDirection: String,
        val consensusAgreement: Int,
        val riskReward: Double,
        val atrPercent: Double,
        val volatilityRatio: Double,
        val structuralBreak: Boolean,
        val dailyLossPercent: Double,
        val portfolioOpenRiskPercent: Double,
        val openPositions: Int,
        val maxPositions: Int = 3,
        val dailyEntries: Int = 0,
        val consecutiveLosses: Int = 0,
        val peakDrawdownPercent: Double = 0.0,
        val neuralDisagreement: Double = 0.0
    )

    data class Plan(
        val direction: String,
        val leverage: Int,
        val riskFraction: Double,
        val riskBudgetUsdt: Double,
        val quantity: Double,
        val notionalUsdt: Double,
        val estimatedLossAtStopUsdt: Double,
        val marginRequiredUsdt: Double,
        val qualityScore: Double
    )

    /** Reward/risk after round-trip fees and adverse exit slippage. */
    fun netRiskReward(entry: Double, stop: Double, target: Double, costPercent: Double): Double {
        if (listOf(entry, stop, target, costPercent).any { !it.isFinite() } ||
            entry <= 0.0 || stop <= 0.0 || target <= 0.0 || costPercent !in 0.0..1.0) return 0.0
        val cost = costPercent / 100.0
        val netReward = abs(target - entry) / entry - cost
        val netRisk = abs(entry - stop) / entry + cost
        if (netReward <= 0.0 || netRisk <= 0.0) return 0.0
        return netReward / netRisk
    }

    fun evaluate(
        input: Input,
        equityUsdt: Double,
        availableUsdt: Double,
        entry: Double,
        stop: Double,
        estimatedRoundTripCostPercent: Double = 0.12
    ): Plan? {
        if (input.direction !in setOf("LONG_BIAS", "SHORT_BIAS")) return null
        if (!input.neuralValidated || !input.neuralUpProbability.isFinite() ||
            input.neuralUpProbability !in 0.0..1.0) return null
        if (!input.neuralDisagreement.isFinite() || input.neuralDisagreement !in 0.0..0.18) return null
        if (input.confidence !in 70..100 || input.consensusAgreement !in 62..100) return null
        if (input.consensusDirection != input.direction) return null
        if (!input.riskReward.isFinite() || input.riskReward < 1.8) return null
        if (!input.atrPercent.isFinite() || input.atrPercent !in 0.05..6.0) return null
        if (!input.volatilityRatio.isFinite() || input.volatilityRatio > 1.8 || input.volatilityRatio < 0.0) return null
        if (input.structuralBreak) return null
        if (!input.dailyLossPercent.isFinite() || input.dailyLossPercent >= 2.0) return null
        if (!input.peakDrawdownPercent.isFinite() || input.peakDrawdownPercent >= 8.0) return null
        if (!input.portfolioOpenRiskPercent.isFinite() || input.portfolioOpenRiskPercent >= 2.5) return null
        if (input.openPositions < 0 || input.openPositions >= input.maxPositions) return null
        if (input.dailyEntries !in 0 until 8 || input.consecutiveLosses !in 0 until 3) return null
        if (!equityUsdt.isFinite() || equityUsdt <= 0.0 ||
            !availableUsdt.isFinite() || availableUsdt <= 0.0 ||
            !entry.isFinite() || entry <= 0.0 || !stop.isFinite() || stop <= 0.0 || entry == stop) return null
        if (estimatedRoundTripCostPercent !in 0.0..1.0) return null

        val neuralAligned = if (input.direction == "LONG_BIAS") {
            input.neuralUpProbability >= 0.56
        } else {
            input.neuralUpProbability <= 0.44
        }
        if (!neuralAligned) return null
        if (input.direction == "LONG_BIAS" && stop >= entry) return null
        if (input.direction == "SHORT_BIAS" && stop <= entry) return null

        val neuralEdge = if (input.direction == "LONG_BIAS")
            input.neuralUpProbability - 0.5 else 0.5 - input.neuralUpProbability
        val confidenceQuality = ((input.confidence - 70.0) / 20.0).coerceIn(0.0, 1.0)
        val consensusQuality = ((input.consensusAgreement - 62.0) / 28.0).coerceIn(0.0, 1.0)
        val neuralQuality = ((neuralEdge - 0.06) / 0.20).coerceIn(0.0, 1.0)
        val quality = (confidenceQuality * 0.40 + consensusQuality * 0.35 + neuralQuality * 0.25)
            .coerceIn(0.0, 1.0)

        // Dynamic risk stays within 0.15%-0.50% of equity, independent of leverage.
        val riskFraction = 0.0015 + quality * 0.0035
        val leverage = when {
            input.volatilityRatio <= 0.85 && input.atrPercent <= 2.5 -> 3
            input.volatilityRatio <= 1.25 -> 2
            else -> 1
        }
        val stopDistanceFraction = abs(entry - stop) / entry
        if (stopDistanceFraction < 0.0005 || stopDistanceFraction > 0.20) return null
        val totalLossFraction = stopDistanceFraction + estimatedRoundTripCostPercent / 100.0
        if (!totalLossFraction.isFinite() || totalLossFraction <= 0.0) return null

        val riskBudget = equityUsdt * riskFraction
        val notionalByRisk = riskBudget / totalLossFraction
        val notionalByMargin = min(availableUsdt * leverage * 0.15, equityUsdt * leverage * 0.15)
        val notional = min(notionalByRisk, notionalByMargin)
        if (!notional.isFinite() || notional <= 0.0) return null
        val quantity = notional / entry
        val estimatedLoss = notional * totalLossFraction
        val margin = notional / leverage
        if (estimatedLoss > riskBudget * 1.000001 || margin > availableUsdt * 0.150001) return null

        return Plan(
            input.direction, leverage, riskFraction, riskBudget, quantity, notional,
            estimatedLoss, margin, quality
        )
    }

    /** Tightens, never widens, a stop using only the latest completed-scan mark. */
    fun trailStop(
        direction: String,
        entry: Double,
        currentMark: Double,
        currentStop: Double,
        initialRiskDistance: Double,
        atr: Double,
        roundTripCostFraction: Double = 0.0012
    ): Double {
        if (direction !in setOf("LONG_BIAS", "SHORT_BIAS") ||
            listOf(entry, currentMark, currentStop, initialRiskDistance, atr).any { !it.isFinite() || it <= 0.0 }) {
            return currentStop
        }
        val isLong = direction == "LONG_BIAS"
        val favorableMove = if (isLong) currentMark - entry else entry - currentMark
        val rMultiple = favorableMove / initialRiskDistance
        if (rMultiple < 1.0) return currentStop

        val breakEven = if (isLong) entry * (1.0 + roundTripCostFraction)
            else entry * (1.0 - roundTripCostFraction)
        var candidate = breakEven
        if (rMultiple >= 1.5) {
            val atrTrail = if (isLong) currentMark - 1.2 * atr else currentMark + 1.2 * atr
            candidate = if (isLong) maxOf(candidate, atrTrail) else minOf(candidate, atrTrail)
        }
        return if (isLong) maxOf(currentStop, candidate) else minOf(currentStop, candidate)
    }
}
