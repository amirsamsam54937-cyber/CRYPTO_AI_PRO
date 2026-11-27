package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * WOLFBLACK strategic cortex.
 *
 * It combines candle-derived liquidity pools, observable market-sentiment proxies,
 * strategy recognition, candidate-strategy generation and a two-ply adversarial
 * scenario search. The "motivation" objective is risk-adjusted net expectancy and
 * survival (drawdown/cost penalties), never raw leverage or trade frequency.
 *
 * It never treats candle-derived stop pools as exchange-observed resting orders.
 * News/social sentiment is explicitly unavailable unless a structured feed is supplied.
 * It can veto/downgrade a candidate; it cannot promote an unqualified signal.
 */
internal object WolfStrategicBrain {
    enum class Sentiment { RISK_ON, RISK_OFF, MIXED, UNKNOWN }
    enum class Strategy {
        SWEEP_RECLAIM_REVERSAL,
        BREAKOUT_RETEST,
        TREND_PULLBACK,
        RANGE_MEAN_REVERSION,
        NO_TRADE
    }
    enum class Response { FOLLOW_THROUGH, FAILED_BREAKOUT, LIQUIDITY_TRAP, VOLATILITY_EXPANSION }

    data class Scenario(
        val strategy: Strategy,
        val side: String,
        val baseScore: Int,
        val worstResponse: Response,
        val responseScore: Int,
        val robustScore: Int,
        val rationale: String,
        val pathSupportPct: Double? = null,
        val neuralAdvisoryPct: Double? = null
    )

    data class Report(
        val liquidity: ZoroLiquidityMapEngine.Snapshot?,
        val sentiment: Sentiment,
        val sentimentScore: Int,
        val sentimentBasis: String,
        val recognizedStrategy: Strategy,
        val scenarios: List<Scenario>,
        val selected: Scenario,
        val trustScore: Int,
        val trustSamples: Int,
        val neuralScenarioAdvisoryPct: Double?,
        val objectiveScore: Int,
        val vetoes: List<String>,
        val explanation: String
    )

    fun analyze(
        context: android.content.Context,
        signal: MarketSignal,
        history: WolfSignalOutcomeJournal.Snapshot? = null
    ): Report {
        val t = signal.technical
        val price = signal.price
        val candles = signal.candles
        val liquidity = candles.mapNotNull { r ->
            if (r.size < 6) null else runCatching {
                ZoroLiquidityMapEngine.Candle(r[5].toLong(), r[0], r[1], r[2], r[3], r[4])
            }.getOrNull()
        }.let { ZoroLiquidityMapEngine.analyze(it) }

        // These are observable market-behaviour proxies, not social-media/news sentiment.
        val flow = if (signal.hiddenFlowFresh) signal.hiddenFlowScore.coerceIn(-100, 100) else 0
        val priceMomentum = (signal.change * 9.0).toInt().coerceIn(-35, 35)
        val rsiBias = ((t.mfi - 50.0) * 0.45 + (signal.rsi - 50.0) * 0.35)
            .toInt().coerceIn(-30, 30)
        val volumeConfirm = when {
            t.relativeVolume >= 1.8 && (priceMomentum + flow) > 0 -> 12
            t.relativeVolume >= 1.8 && (priceMomentum + flow) < 0 -> -12
            t.relativeVolume < 0.65 -> 0
            else -> 0
        }
        val rawMarketProxy = (flow * if (signal.hiddenFlowFresh) 0.35 else 0.0) +
            priceMomentum + rsiBias + volumeConfirm
        val prefs = context.getSharedPreferences("wolf_background_intelligence", android.content.Context.MODE_PRIVATE)
        val sentimentJson = runCatching {
            org.json.JSONObject(prefs.getString("sentiment_network_snapshot", "{}") ?: "{}")
        }.getOrElse { org.json.JSONObject() }
        val sentimentAge = System.currentTimeMillis() - sentimentJson.optLong("updatedAt", 0L)
        val sentimentFresh = sentimentAge in 0L..7_200_000L
        val normalizedSymbol = signal.symbol.uppercase().replace(Regex("[^A-Z0-9]"), "")
            .removeSuffix("USDT").removeSuffix("USD")
        val nodes = sentimentJson.optJSONArray("nodes")
        val node = (0 until (nodes?.length() ?: 0)).mapNotNull { nodes?.optJSONObject(it) }
            .firstOrNull { it.optString("id").equals(normalizedSymbol, true) }
        val articleScore = node?.optInt("score")?.coerceIn(-100, 100)
        val sentimentScore = if (sentimentFresh && articleScore != null)
            (articleScore * 0.65 + rawMarketProxy * 0.35).toInt().coerceIn(-100, 100)
        else rawMarketProxy.toInt().coerceIn(-100, 100)
        val sentiment = when {
            !sentimentFresh && !signal.hiddenFlowFresh && t.relativeVolume <= 0.0 -> Sentiment.UNKNOWN
            sentimentScore >= 18 -> Sentiment.RISK_ON
            sentimentScore <= -18 -> Sentiment.RISK_OFF
            else -> Sentiment.MIXED
        }
        val sentimentBasis = when {
            sentimentFresh && articleScore != null ->
                "شبکه احساسات خبر تازه: " + node?.optInt("evidenceCount", 0) + " شاهد؛ ترکیب با قیمت/حجم/مومنتوم"
            signal.hiddenFlowFresh ->
                "قیمت/حجم/مومنتوم + جریان داخلی تازه؛ خوراک خبر تازه برای این نماد موجود نیست"
            else ->
                "پروکسی قیمت/حجم/مومنتوم؛ داده تازه خبر/جریان سفارش برای این نماد موجود نیست"
        }

        val long = signal.direction == "LONG_BIAS"
        val short = signal.direction == "SHORT_BIAS"
        val side = signal.direction
        val trendAligned = (long && t.ema20 > t.ema50 && t.ema50 > t.ema200) ||
            (short && t.ema20 < t.ema50 && t.ema50 < t.ema200)
        val trendPullback = trendAligned && t.adx >= 18.0 &&
            if (long) signal.rsi in 42.0..68.0 else signal.rsi in 32.0..58.0
        val buyPool = liquidity.nearestBuySide
        val sellPool = liquidity.nearestSellSide
        val sweptAgainstLong = long && liquidity.sweptSellSide
        val sweptAgainstShort = short && liquidity.sweptBuySide
        val nearBuyPool = buyPool != null && buyPool.distancePercent <= max(0.35, t.atrPercent * 0.8)
        val nearSellPool = sellPool != null && sellPool.distancePercent <= max(0.35, t.atrPercent * 0.8)
        val sweepReversal = (long && liquidity.sweptSellSide) || (short && liquidity.sweptBuySide)
        val breakoutRetest = (long && nearBuyPool && buyPool?.event == ZoroLiquidityMapEngine.Event.BREAK_AND_HOLD) ||
            (short && nearSellPool && sellPool?.event == ZoroLiquidityMapEngine.Event.BREAK_AND_HOLD)
        val rangeMode = t.adx < 18.0 && t.bollingerWidthPct < 5.0

        val recognized = when {
            sweepReversal -> Strategy.SWEEP_RECLAIM_REVERSAL
            breakoutRetest -> Strategy.BREAKOUT_RETEST
            trendPullback -> Strategy.TREND_PULLBACK
            rangeMode -> Strategy.RANGE_MEAN_REVERSION
            else -> Strategy.NO_TRADE
        }

        val baseCandidates = mutableListOf<Scenario>()
        fun add(strategy: Strategy, base: Int, rationale: String) {
            val responsePairs = listOf(
                Response.FOLLOW_THROUGH to (if (trendAligned) 18 else 8),
                Response.FAILED_BREAKOUT to (if (breakoutRetest) -28 else -16),
                Response.LIQUIDITY_TRAP to (if (liquidity.sweptBuySide || liquidity.sweptSellSide) -30 else -12),
                Response.VOLATILITY_EXPANSION to (if (t.atrPercent > 4.0) -25 else -8)
            )
            val worst = responsePairs.minByOrNull { it.second }!!
            // Conservative two-ply choice: strategy score plus the weakest plausible reply.
            val pathLabel = if (side == "LONG_BIAS") "صعودی" else "نزولی"
            val pathSupport = signal.scenarioForecast?.scenarios
                ?.firstOrNull { it.label == pathLabel }?.probabilityPct
            val pathAdjustment = pathSupport?.let { ((it - 33.333) * 0.24).toInt().coerceIn(-8, 16) } ?: 0
            val adjustedBase = base + pathAdjustment
            val robust = (adjustedBase + worst.second * 0.55).toInt().coerceIn(-100, 100)
            val neuralAdvisory = signal.deepNeuralSuccessProbability
                ?.takeIf { signal.deepNeuralReviewedSamples >= 100 && it.isFinite() }
                ?.times(100.0)
            baseCandidates += Scenario(strategy, side, adjustedBase, worst.first, worst.second, robust,
                rationale + if (pathSupport != null) "؛ پشتیبانی مسیرهای شبیه‌سازی‌شده %.1f%%".format(java.util.Locale.US, pathSupport) else "",
                pathSupport, neuralAdvisory)
        }

        if (long || short) {
            if (sweepReversal) add(Strategy.SWEEP_RECLAIM_REVERSAL, 72 + min(18, (liquidity.pools.maxOfOrNull { it.strength } ?: 0) / 5),
                "جاروب نقدینگی و بازپس‌گیری سطح در خلاف جهت حرکت قبلی")
            if (breakoutRetest) add(Strategy.BREAKOUT_RETEST, 66 + if (t.relativeVolume >= 1.5) 12 else 0,
                "شکست و تثبیت آن‌سوی خوشه نقدینگی با توجه به حجم")
            if (trendPullback) add(Strategy.TREND_PULLBACK, 62 + min(16, (t.adx - 18.0).toInt().coerceAtLeast(0)),
                "هم‌جهتی EMAها و ADX همراه با اصلاح کنترل‌شده")
            if (rangeMode) {
                val meanReversionSideOk = if (long) t.bollingerPosition <= 0.25 else t.bollingerPosition >= 0.75
                if (meanReversionSideOk) add(Strategy.RANGE_MEAN_REVERSION, 52,
                    "بازار کم‌روند؛ برگشت از لبه باند فقط با تأیید کندل")
            }
        }
        val sentimentConflict = (long && sentiment == Sentiment.RISK_OFF) ||
            (short && sentiment == Sentiment.RISK_ON)
        if (baseCandidates.isEmpty()) add(Strategy.NO_TRADE, 20, "هیچ ستاپ ساختاری به‌اندازه کافی تأیید نشد")
        if (sentimentConflict) {
            for (i in baseCandidates.indices) {
                val s = baseCandidates[i]
                baseCandidates[i] = s.copy(baseScore = s.baseScore - 14,
                    robustScore = s.robustScore - 14,
                    rationale = s.rationale + "؛ تعارض احساسات قابل مشاهده")
            }
        }
        val selected = baseCandidates.maxByOrNull { it.robustScore }!!
        val closed = history?.records.orEmpty().filter { it.closed }
        val wins = closed.count { it.pnlPct > 0.0 }
        val n = closed.count { it.pnlPct.isFinite() }
        // Beta(5,5) shrinkage prevents a handful of lucky outcomes from creating false confidence.
        val posteriorWinRate = (wins + 5.0) / (n + 10.0)
        val sampleWeight = n.toDouble() / (n + 30.0)
        val trustScore = (50.0 + (posteriorWinRate - 0.5) * 100.0 * sampleWeight)
            .toInt().coerceIn(0, 100)

        val rr = rewardRisk(signal)
        val costs = PaperOutcomeEconomics.costPercentOnNotional(15L * 60L * 1000L)
        val expectedNetProxy = (selected.robustScore / 100.0) *
            (rr ?: 0.0) - costs * 2.0
        val drawdownPenalty = if (n >= 8 && history != null && history.totalPnlPct < 0.0) 12 else 0
        val objective = (50.0 + expectedNetProxy * 10.0 - drawdownPenalty -
            if (t.atrPercent > 6.0) 12.0 else 0.0).toInt().coerceIn(0, 100)

        val vetoes = buildList {
            if (!long && !short) add("سیگنال جهت‌دار وجود ندارد")
            if (selected.strategy == Strategy.NO_TRADE) add("هیچ استراتژی معتبر شناسایی نشد")
            if (selected.robustScore < 35) add("سناریو در پاسخ بدبینانه امتیاز کافی ندارد")
            if (sentimentConflict) add("احساسات قابل مشاهده خلاف جهت سیگنال است")
            if (liquidity.pools.isEmpty()) add("استخر نقدینگی معتبر از کندل‌ها استخراج نشد")
            if (t.atrPercent > 8.0) add("ریسک نوسان شدید")
            if (rr == null || rr < 1.2) add("نسبت سود به زیان خام ناکافی")
        }
        val explanation = buildString {
            append("استراتژی: ").append(strategyLabel(selected.strategy))
            append(" • جست‌وجوی دوحرکتی: ").append(responseLabel(selected.worstResponse))
            append(" • امتیاز مقاوم: ").append(selected.robustScore)
            append(" • اعتماد تاریخی: ").append(trustScore).append("/100 از ").append(n).append(" نتیجه بسته")
            append(" • هدف بقا/بازده خالص: ").append(objective).append("/100")
            if (selected.neuralAdvisoryPct != null) append(" • برآورد پژوهشی DNN: ")
                .append(String.format(java.util.Locale.US, "%.1f%%", selected.neuralAdvisoryPct))
                .append(" (بدون مجوز تصمیم؛ اعتبارسنجی مستقل لازم است)")
            if (vetoes.isNotEmpty()) append(" • توقف: ").append(vetoes.joinToString("، "))
        }
        return Report(liquidity, sentiment, sentimentScore, sentimentBasis, recognized,
            baseCandidates.sortedByDescending { it.robustScore }, selected, trustScore, n,
            selected.neuralAdvisoryPct, objective, vetoes, explanation)
    }

    /** Only veto or reduce confidence; never creates a new directional signal. */
    fun enrich(
        context: android.content.Context,
        signals: List<MarketSignal>,
        history: WolfSignalOutcomeJournal.Snapshot? = null
    ): List<MarketSignal> = signals.map { signal ->
            val report = analyze(context, signal, history)
            val mustVeto = report.vetoes.any {
                it == "هیچ استراتژی معتبر شناسایی نشد" ||
                    it == "سناریو در پاسخ بدبینانه امتیاز کافی ندارد" ||
                    it == "احساسات قابل مشاهده خلاف جهت سیگنال است" ||
                    it == "ریسک نوسان شدید" ||
                    it == "نسبت سود به زیان خام ناکافی"
            }
            val penalty = when {
                mustVeto -> 22
                report.selected.robustScore < 48 -> 12
                report.trustSamples < 10 -> 4
                else -> 0
            }
            val downgrade = mustVeto || report.selected.strategy == Strategy.NO_TRADE
            signal.copy(
                direction = if (downgrade) "NEUTRAL" else signal.direction,
                score = if (downgrade) 0 else signal.score,
                confidence = (signal.confidence - penalty).coerceIn(0, 100),
                reason = (signal.reason + " | مغز استراتژیک WOLFBLACK: " + report.explanation +
                    " | احساسات: " + sentimentLabel(report.sentiment) + " (" + report.sentimentScore +
                    ") | " + report.sentimentBasis + " | نقدینگی: " + (report.liquidity?.summary ?: "ناموجود"))
                    .take(1800)
            )
        }

    private fun rewardRisk(s: MarketSignal): Double? {
        val risk = abs(s.entry - s.stop)
        val reward = abs(s.tp1 - s.entry)
        return if (s.entry > 0 && risk > 0 && reward > 0) reward / risk else null
    }

    private fun strategyLabel(s: Strategy) = when (s) {
        Strategy.SWEEP_RECLAIM_REVERSAL -> "بازگشت پس از جاروب نقدینگی"
        Strategy.BREAKOUT_RETEST -> "شکست و بازآزمایی"
        Strategy.TREND_PULLBACK -> "پولبک در روند"
        Strategy.RANGE_MEAN_REVERSION -> "بازگشت به میانگین"
        Strategy.NO_TRADE -> "عدم معامله"
    }

    private fun responseLabel(r: Response) = when (r) {
        Response.FOLLOW_THROUGH -> "ادامه حرکت"
        Response.FAILED_BREAKOUT -> "شکست ناموفق"
        Response.LIQUIDITY_TRAP -> "تله نقدینگی"
        Response.VOLATILITY_EXPANSION -> "انفجار نوسان"
    }

    private fun sentimentLabel(s: Sentiment) = when (s) {
        Sentiment.RISK_ON -> "ریسک‌پذیری/تمایل خرید"
        Sentiment.RISK_OFF -> "ریسک‌گریزی/تمایل فروش"
        Sentiment.MIXED -> "مختلط"
        Sentiment.UNKNOWN -> "نامشخص"
    }
}
