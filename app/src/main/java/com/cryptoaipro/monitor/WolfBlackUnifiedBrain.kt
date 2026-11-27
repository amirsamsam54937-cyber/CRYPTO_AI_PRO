package com.cryptoaipro.monitor

/**
 * WOLFBLACK unified decision boundary.
 *
 * Existing engines remain specialist producers. This coordinator is the only place where
 * their independently measured evidence may be arbitrated. It deliberately requires
 * explicit spread, data-quality, account and out-of-sample reliability inputs; it never
 * invents missing values or promotes an unvalidated neural model.
 *
 * This class does not place orders. Live execution remains behind XtLiveTradingEngine's
 * separate credential, arming, exchange, sizing and protective-order gates.
 */
internal object WolfBlackUnifiedBrain {
    private val requiredSpecialists = setOf(
        "trend", "price_action", "liquidity", "momentum",
        "volume", "derivatives", "volatility", "scenario", "chart"
    )

    data class Inputs(
        val market: ZoroApexQueenCore.MarketState,
        val experts: List<ZoroApexQueenCore.ExpertEvidence>,
        val neural: ZoroApexQueenCore.NeuralCouncil?,
        val modelStatus: WolfPretrainedEnsemble.Status,
        val neuralOutOfDistribution: Boolean,
        val risk: ZoroApexQueenCore.RiskSettings
    )

    data class Snapshot(
        val decision: ZoroApexQueenCore.Decision,
        val neuralMode: NeuralMode,
        val suppliedSpecialists: Int,
        val missingSpecialists: List<String>,
        val liveExecutionEligible: Boolean
    )

    enum class NeuralMode {
        VALIDATED_AND_IN_DISTRIBUTION,
        RESEARCH_ONLY_UNVALIDATED,
        ABSTAIN_OUT_OF_DISTRIBUTION,
        UNAVAILABLE
    }

    fun evaluate(input: Inputs): Snapshot {
        val supplied = input.experts
            .filter { it.available }
            .map { it.id }
            .toSet()
        val missing = (requiredSpecialists - supplied).sorted()

        val model = input.modelStatus
        val neuralMode = when {
            !model.loaded || model.members < 5 -> NeuralMode.UNAVAILABLE
            !model.validatedForScoring -> NeuralMode.RESEARCH_ONLY_UNVALIDATED
            input.neuralOutOfDistribution -> NeuralMode.ABSTAIN_OUT_OF_DISTRIBUTION
            input.neural == null -> NeuralMode.UNAVAILABLE
            else -> NeuralMode.VALIDATED_AND_IN_DISTRIBUTION
        }
        val authorizedNeural = if (neuralMode == NeuralMode.VALIDATED_AND_IN_DISTRIBUTION) {
            input.neural
        } else {
            null
        }

        // Queen Core independently checks data quality, spread, volatility, position,
        // emergency stop, account readiness, specialist count, consensus and risk sizing.
        val decision = ZoroApexQueenCore.decide(
            state = input.market,
            experts = input.experts,
            neural = authorizedNeural,
            risk = input.risk
        )

        // This is an eligibility summary, not an order authorization. The live executor
        // must still verify XT account state, armed mode, exchange filters, costs and
        // exchange-side protective orders immediately before and after each fill.
        val eligible = decision.direction != ZoroApexQueenCore.Direction.WAIT &&
            decision.blockers.isEmpty() &&
            decision.specialistsUsed >= 4 &&
            input.market.accountReady &&
            !input.market.emergencyStop

        return Snapshot(
            decision = decision,
            neuralMode = neuralMode,
            suppliedSpecialists = supplied.size,
            missingSpecialists = missing,
            liveExecutionEligible = eligible
        )
    }

    fun neuralModeLabel(mode: NeuralMode): String = when (mode) {
        NeuralMode.VALIDATED_AND_IN_DISTRIBUTION -> "شبکه عصبی معتبر و داخل محدوده داده"
        NeuralMode.RESEARCH_ONLY_UNVALIDATED -> "شبکه عصبی پژوهشی؛ خارج از اختیار تصمیم"
        NeuralMode.ABSTAIN_OUT_OF_DISTRIBUTION -> "شبکه عصبی به‌علت داده خارج از توزیع سکوت کرد"
        NeuralMode.UNAVAILABLE -> "شبکه عصبی در دسترس نیست"
    }
}
