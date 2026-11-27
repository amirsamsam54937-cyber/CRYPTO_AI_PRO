package com.cryptoaipro.monitor

import kotlin.math.abs
import kotlin.math.max

/**
 * TENAXIS Market Fingerprint: an evidence-led market identity and deception reader.
 *
 * Combines independent candle-native specialists. It does not claim to see hidden
 * orders, exchange liquidations, spoofing intent, or "smart money" identities.
 * All classifications are hypotheses from completed OHLCV candles.
 */
internal object WolfMarketFingerprintEngine {
    enum class Regime { TREND_UP, TREND_DOWN, RANGE, COMPRESSION, EXPANSION, UNCERTAIN }
    enum class Deception { NONE_SEEN, BUY_TRAP_RISK, SELL_TRAP_RISK, TWO_SIDED_TRAP_RISK, BREAKOUT_ACCEPTED, INSUFFICIENT_EVIDENCE }

    data class Fingerprint(
        val available: Boolean,
        val identity: String,
        val regime: Regime,
        val deception: Deception,
        val trapSeverity: Int,
        val directionalBias: String,
        val liquiditySummary: String,
        val marketStructure: String,
        val auctionRead: String,
        val volumeRead: String,
        val behaviorTraits: List<String>,
        val evidence: List<String>,
        val warnings: List<String>,
        val blockLong: Boolean,
        val blockShort: Boolean,
        val decision: String
    )

    fun analyze(input: List<DoubleArray>): Fingerprint {
        val bars = input.asSequence()
            .filter { c -> c.size >= 6 && c.take(6).all { it.isFinite() } &&
                c[0] > 0.0 && c[1] >= max(c[0], c[3]) &&
                c[2] <= minOf(c[0], c[3]) && c[2] > 0.0 &&
                c[3] > 0.0 && c[4] >= 0.0 && c[5] > 0.0 }
            .sortedBy { it[5] }.distinctBy { it[5].toLong() }.toList().takeLast(240)
        if (bars.size < 80) return unavailable()

        val reader = runCatching { ZoroHumanMarketReader.analyze(bars) }.getOrNull()
        val trap = runCatching { WolfTrapDefenseEngine.analyze(bars) }.getOrNull()
        val structure = runCatching { WolfMarketStructureAudit.analyze(bars) }.getOrNull()
        val liquidity = runCatching {
            ZoroLiquidityMapEngine.analyze(bars.map {
                ZoroLiquidityMapEngine.Candle(it[5].toLong(), it[0], it[1], it[2], it[3], it[4])
            })
        }.getOrNull()

        if (reader?.available != true || trap?.available != true) return unavailable()

        val closes = bars.map { it[3] }
        val ranges = bars.map { it[1] - it[2] }
        val current = bars.last()
        val prior = bars.dropLast(1).takeLast(30)
        val priorHigh = prior.maxOf { it[1] }
        val priorLow = prior.minOf { it[2] }
        val currentRange = (current[1] - current[2]).coerceAtLeast(1e-12)
        val bodyRatio = abs(current[3] - current[0]) / currentRange
        val avgVol = bars.takeLast(21).dropLast(1).map { it[4] }.average().coerceAtLeast(1e-12)
        val relVol = current[4] / avgVol
        val avgRecentRange = ranges.takeLast(12).average()
        val avgOlderRange = ranges.takeLast(48).dropLast(12).average().coerceAtLeast(1e-12)
        val compression = avgRecentRange < avgOlderRange * 0.68
        val expansion = currentRange > ranges.takeLast(20).dropLast(1).average().coerceAtLeast(1e-12) * 1.8
        val slope = if (closes.size >= 30) {
            val sample = closes.takeLast(30)
            val meanX = (sample.size - 1) / 2.0
            val meanY = sample.average()
            var cov = 0.0
            var variance = 0.0
            sample.forEachIndexed { i, value ->
                cov += (i - meanX) * (value - meanY)
                variance += (i - meanX) * (i - meanX)
            }
            if (variance > 0.0) cov / variance / current[3] else 0.0
        } else 0.0

        val regime = when {
            compression -> Regime.COMPRESSION
            expansion -> Regime.EXPANSION
            slope > 0.00022 && reader.score >= 25 -> Regime.TREND_UP
            slope < -0.00022 && reader.score <= -25 -> Regime.TREND_DOWN
            abs(reader.score) < 22 -> Regime.RANGE
            else -> Regime.UNCERTAIN
        }
        val deception = when {
            trap.trap == WolfTrapDefenseEngine.Trap.TWO_SIDED_UNCERTAINTY -> Deception.TWO_SIDED_TRAP_RISK
            trap.trap == WolfTrapDefenseEngine.Trap.BUY_SIDE_TRAP -> Deception.BUY_TRAP_RISK
            trap.trap == WolfTrapDefenseEngine.Trap.SELL_SIDE_TRAP -> Deception.SELL_TRAP_RISK
            trap.acceptanceConfirmed -> Deception.BREAKOUT_ACCEPTED
            else -> Deception.NONE_SEEN
        }

        val traits = mutableListOf<String>()
        if (compression) traits += "کمین پیش از گسترش دامنه: فشردگی؛ جهت حرکت هنوز معلوم نیست"
        if (expansion && relVol >= 1.3) traits += "انفجار دامنه با مشارکت حجم؛ احتمال شروع حرکت یا پایان حرکت فرسوده"
        if (relVol >= 1.8 && bodyRatio <= 0.28) traits += "تلاش زیاد و نتیجه کم؛ جذب سفارش/خستگی احتمالی"
        if (liquidity?.sweptBuySide == true) traits += "جاروب خوشه سقف‌ها و بازگشت زیر سطح"
        if (liquidity?.sweptSellSide == true) traits += "جاروب خوشه کف‌ها و بازگشت بالای سطح"
        if (reader.phase.contains("Spring")) traits += "الگوی Spring احتمالی: فشار زیر کف و بازپس‌گیری"
        if (reader.phase.contains("Upthrust")) traits += "الگوی Upthrust احتمالی: نفوذ سقف و رد قیمت"
        if (reader.auction.contains("POC")) traits += "کشش به ناحیه تعادل حجمی؛ ورود وسط ارزش می‌تواند کم‌مزیت باشد"
        if (traits.isEmpty()) traits += "رفتار غالب مشخص نیست؛ داده بیشتر لازم است"

        val warnings = (reader.warnings + trap.evidence +
            listOf("OHLCV به‌تنهایی دفتر سفارش پنهان، لیکوییدیشن واقعی یا قصد دستکاری را اثبات نمی‌کند.") +
            if (regime == Regime.COMPRESSION) listOf("در فاز فشردگی، جهت شکست را پیش‌بینی نکن؛ منتظر بسته‌شدن و پذیرش قیمت بمان.") else emptyList()
        ).distinct()

        val blockLong = trap.blocksLong || regime == Regime.UNCERTAIN && reader.score < 0
        val blockShort = trap.blocksShort || regime == Regime.UNCERTAIN && reader.score > 0
        val decision = when {
            deception == Deception.TWO_SIDED_TRAP_RISK -> "عدم معامله: ریسک تله دوطرفه"
            blockLong && blockShort -> "عدم معامله: شواهد متعارض یا ریسک تله در هر دو سمت"
            blockLong -> "لانگ ممنوع تا بازپس‌گیری سطح و تأیید مجدد"
            blockShort -> "شورت ممنوع تا شکست معتبر و پذیرش قیمت"
            regime == Regime.COMPRESSION -> "صبر برای شکست و تثبیت؛ ورود پیش‌دستانه ممنوع"
            regime == Regime.UNCERTAIN || regime == Regime.RANGE -> "انتظار؛ مزیت جهت‌دار کافی دیده نشد"
            else -> "فقط سناریوی هم‌جهت با ساختار؛ تأیید چندتایم‌فریمی و کنترل هزینه الزامی"
        }
        val identity = when (regime) {
            Regime.TREND_UP -> "روند صعودی با ساختار جهت‌دار"
            Regime.TREND_DOWN -> "روند نزولی با ساختار جهت‌دار"
            Regime.RANGE -> "بازار تعادلی/رنج"
            Regime.COMPRESSION -> "بازار فشرده و در انتظار محرک"
            Regime.EXPANSION -> "بازار در فاز گسترش نوسان"
            Regime.UNCERTAIN -> "بازار بی‌هویت/متعارض"
        }
        return Fingerprint(
            true, identity, regime, deception, trap.severity, reader.structure,
            liquidity?.summary ?: "نقشه نقدینگی قابل ارزیابی نیست",
            structure?.event ?: "رویداد ساختاری در دسترس نیست",
            reader.auction, reader.flow, traits, reader.evidence + trap.evidence,
            warnings, blockLong, blockShort, decision
        )
    }

    private fun unavailable() = Fingerprint(
        false, "هویت بازار نامشخص", Regime.UNCERTAIN, Deception.INSUFFICIENT_EVIDENCE,
        0, "نامشخص", "داده کافی نیست", "داده کافی نیست", "نامشخص", "نامشخص",
        emptyList(), emptyList(),
        listOf("حداقل ۸۰ کندل معتبر OHLCV لازم است؛ هیچ الگوی بازاری حدس زده نمی‌شود."),
        true, true, "عدم معامله تا فراهم‌شدن داده معتبر"
    )
}
