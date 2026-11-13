package com.cryptoaipro.monitor

import kotlin.math.abs

/**
 * Final conservative quality gate for a proposed 15m futures signal.
 *
 * This engine does not invent direction or claim calibrated win probabilities.
 * It checks consensus quality, participation, trend strength and entry extension.
 * Weak setups are downgraded or vetoed rather than forced into a trade.
 */
internal object WolfSignalQualityEngine {
    fun apply(signal: MarketSignal): MarketSignal {
        if (signal.direction != "LONG_BIAS" && signal.direction != "SHORT_BIAS") return signal

        val isLong = signal.direction == "LONG_BIAS"
        val t = signal.technical
        val consensus = signal.consensus
        var penalty = 0
        var veto = false
        val notes = mutableListOf<String>()

        if (consensus == null) {
            penalty += 10
            notes += "اجماع چندتحلیلی در دسترس نیست"
        } else {
            when {
                consensus.agreement < 58 -> {
                    penalty += 15
                    veto = true
                    notes += "اجماع کمتر از حداقل فرمانده است"
                }
                consensus.agreement < 60 -> {
                    // Borderline consensus is a soft penalty, not an automatic signal kill.
                    // Commander still checks directional evidence, timeframe conflict and risk.
                    penalty += 6
                    notes += "اجماع نزدیک به آستانه؛ نیازمند تأیید مستقل فرمانده"
                }
                consensus.agreement < 66 -> {
                    penalty += 7
                    notes += "هم‌گرایی تحلیلگرها متوسط است"
                }
            }
            val directionalVote = consensus.votes.firstOrNull { it.name == "هم‌گرایی تایم‌فریم" }
            if (directionalVote == null || directionalVote.direction == "NEUTRAL") {
                penalty += 5
                notes += "تأیید کامل روند بین تایم‌فریم‌ها وجود ندارد"
            } else if ((isLong && directionalVote.direction == "SHORT") ||
                (!isLong && directionalVote.direction == "LONG")) {
                penalty += 12
                veto = true
                notes += "رأی هم‌گرایی تایم‌فریم مخالف جهت سیگنال است"
            }
        }

        if (t.adx < 16.0) {
            penalty += 8
            notes += "قدرت روند ۱۵دقیقه‌ای پایین است"
            if (t.adx < 12.0) veto = true
        } else if (t.adx < 20.0) {
            penalty += 4
            notes += "قدرت روند هنوز متوسط است"
        }

        if (t.relativeVolume < 0.55) {
            penalty += 8
            notes += "حجم نسبی برای تأیید حرکت کافی نیست"
            if (t.relativeVolume < 0.35) veto = true
        } else if (t.relativeVolume < 0.80) {
            penalty += 4
            notes += "مشارکت حجم ضعیف‌تر از حد مطلوب است"
        }

        if (isLong && signal.rsi >= 76.0) {
            penalty += 7
            notes += "ورود لانگ در وضعیت کشیدگی مومنتوم است"
            if (signal.rsi >= 84.0) veto = true
        } else if (!isLong && signal.rsi <= 24.0) {
            penalty += 7
            notes += "ورود شورت در وضعیت کشیدگی مومنتوم است"
            if (signal.rsi <= 16.0) veto = true
        }

        // Reject invalid geometry. Non-scalp setups retain the 1.8R quality gate;
        // 5m scalps defer their small-target acceptance to the live XT net-fee check.
        val riskDistance = abs(signal.entry - signal.stop)
        val rewardDistance = abs(signal.tp1 - signal.entry)
        val validGeometry = signal.entry.isFinite() && signal.stop.isFinite() &&
            signal.tp1.isFinite() && signal.entry > 0.0 && signal.stop > 0.0 &&
            signal.tp1 > 0.0 && riskDistance > 0.0 && rewardDistance > 0.0 &&
            (if (isLong) signal.stop < signal.entry && signal.tp1 > signal.entry
             else signal.stop > signal.entry && signal.tp1 < signal.entry)
        val rewardRisk = if (validGeometry) rewardDistance / riskDistance else 0.0
        if (!validGeometry) {
            penalty += 20
            veto = true
            notes += "هندسه ورود، حدضرر یا هدف اول نامعتبر است"
        } else if (!signal.tradeTimeframe.startsWith("5m") && rewardRisk < 1.8) {
            penalty += 16
            veto = true
            notes += "نسبت سود به زیان هدف اول کمتر از ۱٫۸ است"
        }

        val atr = signal.atr
        if (atr.isFinite() && atr > 0.0) {
            val extension = abs(signal.price - t.ema20) / atr
            if (extension > 1.75) {
                penalty += 8
                notes += "قیمت از EMA20 دور شده؛ ورود تعقیبی پرریسک است"
                if (extension > 2.35) veto = true
            }
        }

        val confidence = (signal.confidence - penalty.coerceAtMost(28)).coerceIn(0, 95)
        val note = if (notes.isEmpty()) {
            " | کنترل کیفیت نهایی: مانع عمده‌ای در داده‌های در دسترس دیده نشد"
        } else {
            " | کنترل کیفیت نهایی: " + notes.distinct().joinToString("؛ ")
        }
        return signal.copy(
            direction = if (veto) "NEUTRAL" else signal.direction,
            confidence = if (veto) minOf(confidence, 49) else confidence,
            score = if (veto) 0 else signal.score,
            reason = signal.reason + note
        )
    }
}
