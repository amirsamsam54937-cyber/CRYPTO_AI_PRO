package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ZORO APEX Queen Core.
 *
 * Ten independent analytical specialists submit bounded evidence. This class is the
 * single deterministic arbiter. It does not place orders and never treats evidence
 * scores as calibrated probabilities. Neural votes are excluded from trade authority
 * until an independently validated ensemble is supplied.
 */
object ZoroApexQueenCore {
    enum class Direction { LONG, SHORT, WAIT }
    enum class Regime { TREND, RANGE, HIGH_VOLATILITY, UNKNOWN }

    data class ExpertEvidence(
        val id: String,
        val score: Double,          // -1.0 bearish .. +1.0 bullish
        val reliability: Double,    // out-of-sample historical reliability, 0..1
        val freshness: Double,      // freshness of source data, 0..1
        val available: Boolean = true,
        val explanation: String = ""
    )

    data class NeuralCouncil(
        val memberScores: List<Double>,
        val independentlyValidated: Boolean,
        val disagreement: Double   // 0..1, normalized dispersion
    )

    data class MarketState(
        val symbol: String,
        val price: Double,
        val atrPercent: Double,
        val spreadPercent: Double,
        val dataQuality: Double,    // 0..100
        val regime: Regime,
        val openPositionExists: Boolean,
        val emergencyStop: Boolean,
        val accountReady: Boolean
    )

    data class RiskSettings(
        val equity: Double,
        val riskPercent: Double = 0.25,
        val maxMarginPercent: Double = 5.0,
        val leverage: Double = 3.0,
        val rewardRisk1: Double = 1.5,
        val rewardRisk2: Double = 2.5
    )

    data class Decision(
        val direction: Direction,
        val evidenceScore: Int,
        val agreement: Int,
        val specialistsUsed: Int,
        val neuralCouncilUsed: Boolean,
        val entryReference: Double?,
        val stopReference: Double?,
        val target1: Double?,
        val target2: Double?,
        val suggestedQuantity: Double,
        val suggestedMargin: Double,
        val reasons: List<String>,
        val blockers: List<String>
    )

    private val baseWeights = mapOf(
        "trend" to 1.15, "price_action" to 1.20, "liquidity" to 1.15,
        "momentum" to 0.95, "volume" to 1.05, "derivatives" to 0.95,
        "volatility" to 0.85, "macro" to 0.75, "scenario" to 1.0,
        "chart" to 1.15
    )

    fun decide(
        state: MarketState,
        experts: List<ExpertEvidence>,
        neural: NeuralCouncil?,
        risk: RiskSettings
    ): Decision {
        val blockers = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        if (!state.price.isFinite() || state.price <= 0.0) blockers += "قیمت مرجع نامعتبر است"
        if (!state.atrPercent.isFinite() || state.atrPercent <= 0.0) blockers += "ATR معتبر در دسترس نیست"
        if (!state.spreadPercent.isFinite() || state.spreadPercent < 0.0) blockers += "اسپرد نامعتبر است"
        if (state.dataQuality < 85.0) blockers += "کیفیت داده کمتر از حد مجاز است"
        if (state.spreadPercent > 0.15) blockers += "اسپرد بیش از حد مجاز است"
        if (state.regime == Regime.HIGH_VOLATILITY) blockers += "رژیم نوسان بسیار بالا است"
        if (state.openPositionExists) blockers += "پوزیشن باز وجود دارد"
        if (state.emergencyStop) blockers += "توقف اضطراری فعال است"
        if (!state.accountReady) blockers += "آمادگی حساب و اتصال تأیید نشده است"
        if (!risk.equity.isFinite() || risk.equity <= 0.0) blockers += "Equity حساب معتبر نیست"

        val usable = experts.filter {
            it.available && it.score.isFinite() && it.reliability.isFinite() &&
                it.freshness.isFinite() && it.reliability in 0.0..1.0 &&
                it.freshness in 0.0..1.0 && it.score in -1.0..1.0
        }.distinctBy { it.id }
        if (usable.size < 4) blockers += "کمتر از چهار متخصص مستقل و معتبر داده دارند"

        val neuralScores = neural?.memberScores.orEmpty().filter { it.isFinite() && it in -1.0..1.0 }
        val neuralUsable = neural != null && neural.independentlyValidated &&
            neuralScores.size >= 5 && neural.disagreement.isFinite() && neural.disagreement <= 0.35
        if (neural != null && !neuralUsable) reasons += "رأی شبکه عصبی فقط نمایشی/پژوهشی است و در اختیار معامله دخالت داده نشد"
        if (neuralUsable) reasons += "شورای شبکه عصبی اعتبارسنجی‌شده در اجماع مرکزی مشارکت کرد"

        val weighted = usable.map { e ->
            val regimeFactor = when (state.regime) {
                Regime.TREND -> if (e.id == "trend" || e.id == "momentum") 1.25 else 1.0
                Regime.RANGE -> if (e.id == "price_action" || e.id == "liquidity" || e.id == "chart") 1.25 else 0.85
                Regime.HIGH_VOLATILITY -> 0.0
                Regime.UNKNOWN -> 0.85
            }
            val w = (baseWeights[e.id] ?: 0.8) * e.reliability * e.freshness * regimeFactor
            e.score to w
        }.filter { it.second > 0.0 }

        var numerator = weighted.sumOf { it.first * it.second }
        var denominator = weighted.sumOf { it.second }
        if (neuralUsable) {
            val neuralMean = neuralScores.average()
            val neuralWeight = 0.75 * (1.0 - neural!!.disagreement).coerceIn(0.0, 1.0)
            numerator += neuralMean * neuralWeight
            denominator += neuralWeight
        }
        val consensus = if (denominator > 0.0) (numerator / denominator).coerceIn(-1.0, 1.0) else 0.0
        val positive = weighted.count { it.first > 0.15 }
        val negative = weighted.count { it.first < -0.15 }
        val agreement = if (usable.isEmpty()) 0 else
            (100.0 * max(positive, negative) / usable.size).toInt()

        val direction = when {
            blockers.isNotEmpty() -> Direction.WAIT
            consensus >= 0.32 && positive >= 3 && agreement >= 60 -> Direction.LONG
            consensus <= -0.32 && negative >= 3 && agreement >= 60 -> Direction.SHORT
            else -> Direction.WAIT
        }
        if (direction == Direction.WAIT && blockers.isEmpty()) reasons += "اجماع متخصصان برای ورود کافی نیست؛ مغز در حالت انتظار می‌ماند"
        usable.sortedByDescending { abs(it.score) * it.reliability * it.freshness }
            .take(5).forEach { if (it.explanation.isNotBlank()) reasons += "${it.id}: ${it.explanation}" }
        reasons += "امتیاز اجماع ${(consensus * 100).toInt()} از ۱۰۰ است؛ این عدد احتمال برد نیست"

        if (direction == Direction.WAIT) return Decision(
            direction, (consensus * 100).toInt(), agreement, usable.size, neuralUsable,
            null, null, null, null, 0.0, 0.0, reasons, blockers
        )

        val stopDistance = state.price * (state.atrPercent / 100.0) * 1.5
        if (!stopDistance.isFinite() || stopDistance <= 0.0) {
            return Decision(Direction.WAIT, (consensus * 100).toInt(), agreement, usable.size,
                neuralUsable, null, null, null, null, 0.0, 0.0, reasons,
                blockers + "فاصله حد ضرر قابل محاسبه نیست")
        }
        val riskFraction = (risk.riskPercent / 100.0).coerceIn(0.0, 0.0025)
        val riskCash = risk.equity * riskFraction
        val rawQty = riskCash / stopDistance
        val maxMargin = risk.equity * (risk.maxMarginPercent / 100.0).coerceIn(0.0, 0.05)
        val leverage = risk.leverage.coerceIn(1.0, 3.0)
        val maxQtyByMargin = maxMargin * leverage / state.price
        val qty = min(rawQty, maxQtyByMargin).coerceAtLeast(0.0)
        if (!qty.isFinite() || qty <= 0.0) {
            return Decision(Direction.WAIT, (consensus * 100).toInt(), agreement, usable.size,
                neuralUsable, null, null, null, null, 0.0, 0.0, reasons,
                blockers + "اندازه پوزیشن صفر یا نامعتبر است")
        }
        val sign = if (direction == Direction.LONG) 1.0 else -1.0
        val stop = state.price - sign * stopDistance
        val target1 = state.price + sign * stopDistance * risk.rewardRisk1.coerceIn(1.0, 3.0)
        val target2 = state.price + sign * stopDistance * risk.rewardRisk2.coerceIn(1.5, 5.0)
        return Decision(
            direction, (consensus * 100).toInt(), agreement, usable.size, neuralUsable,
            state.price, stop, target1, target2, qty, qty * state.price / leverage,
            reasons, emptyList()
        )
    }
}
