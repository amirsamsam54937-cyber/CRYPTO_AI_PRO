package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max

/**
 * Independent adversarial review of a proposed directional signal.
 *
 * It searches for reasons the thesis may be wrong: higher-timeframe trend conflict,
 * late entries, weak participation, opposing liquidity sweeps, exhaustion and nearby
 * opposing structure. It uses only fields actually available in the app's snapshots.
 * It does not pretend to see a live order book, liquidation map, or exchange-native
 * whale feed. Those require separate verified data sources.
 */
internal object WolfAdversarialReviewEngine {
    data class Review(
        val accepted: Boolean,
        val riskScore: Int,
        val objections: List<String>,
        val supportingEvidence: List<String>
    )

    fun review(signal: MarketSignal): Review {
        if (signal.direction != "LONG_BIAS" && signal.direction != "SHORT_BIAS") {
            return Review(false, 100, listOf("جهت قطعی برای بازبینی وجود ندارد"), emptyList())
        }
        val long = signal.direction == "LONG_BIAS"
        val t = signal.technical
        val objections = mutableListOf<String>()
        val support = mutableListOf<String>()
        var risk = 0

        fun oppose(points: Int, message: String) { risk += points; objections += message }
        fun confirm(message: String) { support += message }

        // Trend and structure are deliberately checked independently.
        val emaOpposes = if (long) t.ema20 < t.ema50 && t.ema50 < t.ema100
            else t.ema20 > t.ema50 && t.ema50 > t.ema100
        if (emaOpposes) oppose(22, "چیدمان EMA در خلاف جهت سیگنال است")
        else if ((long && t.ema20 > t.ema50) || (!long && t.ema20 < t.ema50)) confirm("EMA20/50 هم‌جهت است")

        val structureOpposes = if (long)
            t.marketStructure.contains("LH/LL") || t.marketStructure.contains("BOS نزولی") || t.marketStructure.contains("CHOCH نزولی")
        else t.marketStructure.contains("HH/HL") || t.marketStructure.contains("BOS صعودی") || t.marketStructure.contains("CHOCH صعودی")
        if (structureOpposes) oppose(24, "ساختار بازار با جهت پیشنهادی تعارض دارد")
        else if (t.marketStructure.contains("HH/HL") || t.marketStructure.contains("LH/LL")) confirm("ساختار سویینگ قابل‌تشخیص است")

        val sweepOpposes = if (long) t.liquiditySweep.contains("برگشت نزولی")
            else t.liquiditySweep.contains("برگشت صعودی")
        if (sweepOpposes) oppose(18, "جاروب نقدینگی اخیر می‌تواند علیه جهت سیگنال باشد")

        if (t.adx < 14.0) oppose(8, "قدرت روند پایین است؛ احتمال بازار خنثی")
        else if (t.adx >= 25.0) confirm("قدرت روند مناسب است")

        if (long && signal.rsi >= 78.0) oppose(12, "ورود لانگ در ناحیه اشباع خرید و مستعد تعقیب قیمت")
        if (!long && signal.rsi <= 22.0) oppose(12, "ورود شورت در ناحیه اشباع فروش و مستعد برگشت")
        if (long && t.stochasticRsi >= 95.0) oppose(7, "Stoch RSI بسیار داغ است")
        if (!long && t.stochasticRsi <= 5.0) oppose(7, "Stoch RSI بسیار پایین است")

        if (t.relativeVolume < 0.60) oppose(12, "حجم مشارکت برای تأیید حرکت ضعیف است")
        else if (t.relativeVolume >= 1.35) confirm("حجم نسبی از میانگین بالاتر است")

        if (t.bollingerWidthPct < 0.65) oppose(7, "فشردگی شدید باندها؛ جهت شکست هنوز قطعی نیست")
        if (t.atrPercent > 8.0) oppose(14, "نوسان بسیار بالا؛ ریسک لغزش و حدضرر افزایش یافته")

        val nearOpposingLevel = if (long) t.resistance > 0.0 &&
            (t.resistance - signal.price) / max(signal.price, 1e-12) in 0.0..0.003
        else t.support > 0.0 && (signal.price - t.support) / max(signal.price, 1e-12) in 0.0..0.003
        if (nearOpposingLevel) oppose(14, "قیمت به سطح ساختاری مقابل بسیار نزدیک است")

        val stretched = signal.atr > 0.0 && abs(signal.price - signal.entry) > signal.atr * 1.8
        if (stretched) oppose(8, "ورود از قیمت فعلی نسبت به ATR کشیده شده است")

        risk = risk.coerceIn(0, 100)
        return Review(risk < 58, risk, objections.distinct().take(6), support.distinct().take(4))
    }

    /** Bounded review: can downgrade fragile ideas to NO TRADE, never upgrades a neutral signal. */
    fun apply(signal: MarketSignal): MarketSignal {
        if (signal.direction != "LONG_BIAS" && signal.direction != "SHORT_BIAS") return signal
        val r = review(signal)
        val penalty = when {
            r.riskScore >= 58 -> 18
            r.riskScore >= 42 -> 10
            r.riskScore >= 25 -> 5
            else -> 0
        }
        val veto = r.riskScore >= 58
        val newDirection = if (veto) "NEUTRAL" else signal.direction
        val note = buildString {
            append(" | بازبینی خصمانه مستقل: ریسک ")
            append(r.riskScore).append("/100")
            if (veto) append("؛ رد ایده و تبدیل به عدم معامله")
            if (r.objections.isNotEmpty()) append("؛ ایرادها: ").append(r.objections.joinToString("، "))
            if (r.supportingEvidence.isNotEmpty()) append("؛ شواهد موافق: ").append(r.supportingEvidence.joinToString("، "))
        }
        return signal.copy(
            direction = newDirection,
            confidence = (signal.confidence - penalty).coerceIn(0, 95),
            score = if (veto) 0 else (signal.score - penalty / 2).coerceIn(-100, 100),
            reason = signal.reason + note
        )
    }
}
