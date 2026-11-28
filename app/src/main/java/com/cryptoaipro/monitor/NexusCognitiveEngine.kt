package com.cryptoaipro.monitor

import android.content.Context
import kotlin.math.abs
import kotlin.math.max

/**
 * NEXUS cognitive decision loop.
 * OBSERVE -> VALIDATE -> FORM SCENARIOS -> STRESS TEST COSTS -> DECIDE -> LOG.
 * This is an auditable software reasoning pipeline, not consciousness.
 * A validated ensemble may nominate direction; otherwise independent OHLCV price action
 * nominates direction. Other evidence may veto or scale risk.
 */
internal object NexusCognitiveEngine {
    data class Thought(
        val allowed: Boolean,
        val side: String,
        val regime: String,
        val neuralAgreement: Int,
        val scenarioOpposition: Int,
        val netRewardRisk: Double,
        val estimatedRoundTripCostPct: Double,
        val riskMultiplier: Double,
        val phase: String,
        val reasons: List<String>
    )

    fun think(
        signal: MarketSignal,
        modelValidated: Boolean = WolfPretrainedEnsemble.status().validatedForScoring,
        context: Context? = null
    ): Thought {
        val reasons = mutableListOf<String>()
        val neuralSide = signal.neuralDirection.uppercase()
        val chart = ZoroHumanMarketReader.analyze(signal.candles)
        val timeframeFrames = signal.timeframeAnalysis.filter { it.available }
        val timeframeScore = ZoroTimeframeAnalysisEngine.consensusScore(timeframeFrames)
        val combinedChartScore = if (timeframeFrames.size >= 3)
            (chart.score * 0.55 + timeframeScore * 0.45).toInt().coerceIn(-100, 100)
        else chart.score
        val chartSide = when {
            chart.available && combinedChartScore >= 25 -> "LONG_BIAS"
            chart.available && combinedChartScore <= -25 -> "SHORT_BIAS"
            else -> "NEUTRAL"
        }
        val side = if (modelValidated) neuralSide else chartSide
        // The chart-cognition network is part of the NEXUS decision boundary, not a detached panel.
        // Its unvalidated probabilities can only raise a contradiction warning / reduce risk.
        val chartBrain = context?.let { runCatching { TenaxisChartCognition.analyze(it, signal) }.getOrNull() }
        val chartBrainConflict = chartBrain?.let { brain ->
            (side == "LONG_BIAS" && brain.score <= -35) ||
                (side == "SHORT_BIAS" && brain.score >= 35)
        } ?: false
        val chartNeuralConflict = chartBrain?.prediction?.let { prediction ->
            (side == "LONG_BIAS" && prediction.direction == "DOWN" ||
                side == "SHORT_BIAS" && prediction.direction == "UP") && prediction.confidencePct >= 60
        } ?: false
        val timeframeOpposition = ZoroTimeframeAnalysisEngine.opposingCount(timeframeFrames, side)
        val timeframeConflict = timeframeFrames.size >= 4 && timeframeOpposition >= 4 &&
            (if (side == "LONG_BIAS") timeframeScore <= -20 else if (side == "SHORT_BIAS") timeframeScore >= 20 else true)
        val probs = signal.neuralMemberProbabilities.filter { it.isFinite() && it in 0.0..1.0 }
        val meanUp = if (probs.isEmpty()) 0.5 else probs.average()
        val longVotes = probs.count { it >= 0.55 }
        val shortVotes = probs.count { it <= 0.45 }
        val agreement = max(longVotes, shortVotes)
        val technical = signal.technical
        val atr = technical.atrPercent.takeIf { it.isFinite() } ?: Double.NaN
        val volume = technical.relativeVolume.takeIf { it.isFinite() } ?: 0.0
        val adx = technical.adx.takeIf { it.isFinite() } ?: 0.0
        val regime = when {
            !atr.isFinite() -> "UNKNOWN"
            atr >= 5.0 -> "EXTREME_VOLATILITY"
            atr >= 3.0 -> "HIGH_VOLATILITY"
            adx >= 25.0 -> "TRENDING"
            adx < 18.0 -> "RANGE"
            else -> "BALANCED"
        }

        if (modelValidated) {
            if (probs.size != 10) reasons += "برای تصمیم عصبی، هر ۱۰ خروجی متخصص لازم است"
            if (signal.neuralUncertainty.highDisagreement) reasons += "اختلاف یا آنتروپی متخصصان بیش از حد مجاز است"
            if (side == "LONG_BIAS" && (longVotes < 7 || meanUp < 0.60))
                reasons += "اجماع صعودی متخصصان کمتر از حد لازم است"
            if (side == "SHORT_BIAS" && (shortVotes < 7 || meanUp > 0.40))
                reasons += "اجماع نزولی متخصصان کمتر از حد لازم است"
        } else {
            reasons += "جهت از خوانش مستقل رفتار قیمت تعیین شد؛ رأی مدل اعتبارسنجی‌نشده کنار گذاشته شد"
            if (!chart.available) reasons += "داده کافی برای خوانش مستقل چارت وجود ندارد"
            else reasons += "امتیاز ساختار چارت: ${chart.score}/100؛ ${chart.structure}"
            if (timeframeFrames.isNotEmpty()) reasons += "اجماع وزنی تایم‌فریم‌ها: ${timeframeScore}/100 از ${timeframeFrames.size} بازه؛ مخالف جهت منتخب: ${timeframeOpposition}"
            if (timeframeConflict) reasons += "تعارض غالب تایم‌فریم‌ها؛ ورود مسدود"
        }
        if (side != "LONG_BIAS" && side != "SHORT_BIAS") reasons += "شواهد جهت‌دار برای ورود کافی نیست"
        if (chartBrain != null) {
            reasons += "شبکه شناخت چارت TENAXIS: امتیاز ساختار ${chartBrain.score}/100؛ ${chartBrain.regime}؛ ${chartBrain.structure}"
            if (chartBrain.prediction != null) reasons += "خروجی پژوهشی شبکه چارت DOWN/NEUTRAL/UP: " +
                "${(chartBrain.prediction.down * 100).toInt()}/${(chartBrain.prediction.neutral * 100).toInt()}/${(chartBrain.prediction.up * 100).toInt()}٪ از ${chartBrain.reviewedSamples} نمونه"
        }
        if (chartBrainConflict) reasons += "تعارض قوی تحلیل ساختاری چارت با جهت نامزد؛ ورود متوقف"
        if (chartNeuralConflict) reasons += "شبکه عصبی چارت با جهت نامزد تعارض دارد؛ ریسک کاهش می‌یابد"

        val candleTime = signal.candles.lastOrNull()?.getOrNull(5)?.toLong() ?: 0L
        val age = System.currentTimeMillis() - candleTime
        if (candleTime <= 0L || age !in 0L..30L * 60L * 1000L)
            reasons += "کندل مرجع تازه یا معتبر نیست"
        if (!signal.price.isFinite() || signal.price <= 0.0 ||
            !signal.entry.isFinite() || signal.entry <= 0.0 ||
            abs(signal.price - signal.entry) / signal.entry > 0.0075)
            reasons += "قیمت از نقطه ورود بیش از محدوده لغزش مجاز فاصله دارد"
        if (!atr.isFinite() || atr !in 0.2..5.0) reasons += "نوسان خارج از محدوده مجاز است"
        if (adx < 18.0) reasons += "قدرت روند برای ورود کافی نیست"
        if (volume < 0.75) reasons += "حجم نسبی پایین است"

        val scenario = signal.multiHorizonOutlook
        val directionalHorizons = scenario?.horizons.orEmpty().filter {
            it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS"
        }
        val opposition = if (side == "LONG_BIAS")
            directionalHorizons.count { it.direction == "SHORT_BIAS" }
        else if (side == "SHORT_BIAS")
            directionalHorizons.count { it.direction == "LONG_BIAS" }
        else directionalHorizons.size
        if (scenario?.available == true && scenario.dataQuality >= 65 && opposition >= 3)
            reasons += "حداقل ۳ سناریوی چندافقی با جهت عصبی تعارض دارند"
        if (scenario == null || !scenario.available || scenario.dataQuality < 65)
            // Missing optional forecast data is an advisory, not an automatic veto.
            // The independent chart + timeframe, freshness, trend, risk and net-cost
            // gates below still have to pass before a signal can be published.
            reasons += "داده چندافقی کافی نیست؛ این عامل از تصمیم حذف شد"

        val riskDistance = abs(signal.entry - signal.stop) / signal.entry.coerceAtLeast(1e-12)
        val rewardDistance = abs(signal.tp1 - signal.entry) / signal.entry.coerceAtLeast(1e-12)
        val cost = runCatching {
            XtExecutionCostModel.estimate(
                atrPercent = atr,
                relativeVolume = volume,
                liquidityStrength = technical.liquidityStrength
            )
        }.getOrNull()
        val costPct = cost?.let {
            (it.feePerSide * 2.0 + it.slippagePerSide * 2.0 +
                it.fundingReservePer8h * (6.0 / 8.0)) * 100.0
        } ?: Double.POSITIVE_INFINITY
        val netRewardRisk = if (riskDistance > 0.0 && rewardDistance > costPct / 100.0)
            (rewardDistance - costPct / 100.0) / (riskDistance + costPct / 100.0)
        else 0.0
        if (!netRewardRisk.isFinite() || netRewardRisk < 1.35)
            reasons += "نسبت سود به زیان خالص پس از هزینه‌ها کمتر از ۱٫۳۵ است"

        val riskMultiplier = when {
            chartBrainConflict -> 0.0
            !atr.isFinite() || atr >= 5.0 -> 0.0
            atr >= 3.5 || adx < 20.0 -> 0.50
            atr >= 2.5 || volume < 1.0 -> 0.75
            else -> 1.0
        }
        if (riskMultiplier == 0.0) reasons += "رژیم پرنوسان؛ معامله جدید مسدود است"
        else if (riskMultiplier < 1.0) reasons += "اندازه ریسک به " + (riskMultiplier * 100).toInt() + "٪ کاهش می‌یابد"

        val blockingReason = side == "NEUTRAL" || reasons.any {
            (modelValidated && (it.contains("۱۰ خروجی") ||
                it.contains("اختلاف یا آنتروپی") || it.contains("اجماع"))) ||
                it.contains("خنثی") || it.contains("داده کافی برای خوانش مستقل") || it.contains("کندل مرجع") ||
                it.contains("قیمت از نقطه") || it.contains("نوسان خارج") ||
                it.contains("قدرت روند") || it.contains("حجم نسبی پایین") ||
                it.contains("حداقل ۳ سناریو") ||
                it.contains("تعارض غالب تایم‌فریم‌ها") ||
                it.contains("تعارض قوی تحلیل ساختاری چارت") ||
                it.contains("نسبت سود") || it.contains("مسدود")
        }
        val allowed = !blockingReason
        val thought = Thought(
            allowed = allowed,
            side = if (allowed) side else "NEUTRAL",
            regime = regime,
            neuralAgreement = agreement,
            scenarioOpposition = opposition,
            netRewardRisk = netRewardRisk.takeIf { it.isFinite() } ?: 0.0,
            estimatedRoundTripCostPct = costPct.takeIf { it.isFinite() } ?: 0.0,
            riskMultiplier = riskMultiplier,
            phase = if (allowed) "DECISION_READY" else "ABSTAIN",
            reasons = reasons.distinct()
        )
        if (context != null) {
            val candleAt = signal.candles.lastOrNull()?.getOrNull(5)?.toLong() ?: 0L
            val eventId = "${signal.symbol}|${candleAt}|nexus_decision"
            val payload = org.json.JSONObject()
                .put("symbol", signal.symbol)
                .put("candleAt", candleAt)
                .put("candidateSide", side)
                .put("finalSide", thought.side)
                .put("allowed", thought.allowed)
                .put("regime", thought.regime)
                .put("riskMultiplier", thought.riskMultiplier)
                .put("netRewardRisk", thought.netRewardRisk)
                .put("chartScore", chartBrain?.score)
                .put("chartReviewedSamples", chartBrain?.reviewedSamples ?: 0)
                .put("reasons", org.json.JSONArray(thought.reasons))
                .toString()
            TenaxisChartCognition.recordMemoryEvent(context, "nexus_decision", eventId, payload)
        }
        return thought
    }
}
