package com.cryptoaipro.monitor

import java.net.URI
import kotlin.math.abs

/**
 * Immutable, research-only ledger for publicly observable trader calls.
 * A call is not proof of an executed account trade or account-level profitability.
 * This module has no access to exchange credentials and no order-execution authority.
 */
internal object ZoroTraderEvidenceLedger {
    enum class Side { LONG, SHORT, NEUTRAL }
    enum class CallStatus { OPEN, TARGET, STOP, MANUAL_EXIT, CANCELLED, INVALIDATED, NO_FILL, UNKNOWN }
    enum class EvidenceGrade { A, B, C, D }

    data class Call(
        val id: String,
        val traderId: String,
        val sourceUrl: String,
        val publishedAtEpochMs: Long,
        val capturedAtEpochMs: Long,
        val symbol: String?,
        val venue: String?,
        val timeframe: String?,
        val side: Side,
        val entry: Double?,
        val stop: Double?,
        val target: Double?,
        val status: CallStatus,
        /** Net R after estimated costs, only when it can be reconstructed without guessing. */
        val netR: Double?,
        val evidence: EvidenceGrade,
        val notes: String? = null
    )

    data class Audit(val valid: Boolean, val errors: List<String>)

    data class Metrics(
        val resolvedCount: Int,
        val wins: Int,
        val losses: Int,
        val winRatePercent: Double?,
        val expectancyR: Double?,
        val profitFactor: Double?,
        val maxDrawdownR: Double?,
        val missingResultCount: Int
    )

    fun audit(call: Call): Audit {
        val errors = mutableListOf<String>()
        if (call.id.isBlank()) errors += "شناسه نمونه خالی است"
        if (call.traderId.isBlank()) errors += "شناسه معامله‌گر خالی است"
        val uri = runCatching { URI(call.sourceUrl) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            errors += "نشانی منبع معتبر نیست"
        }
        if (call.publishedAtEpochMs <= 0L) errors += "زمان انتشار ثبت نشده"
        if (call.capturedAtEpochMs <= 0L) errors += "زمان ثبت شواهد ثبت نشده"
        if (call.publishedAtEpochMs > 0L && call.capturedAtEpochMs > 0L &&
            call.capturedAtEpochMs < call.publishedAtEpochMs) {
            errors += "زمان ثبت شواهد نمی‌تواند قبل از انتشار باشد"
        }
        if (call.entry != null && !call.entry.isFinite()) errors += "قیمت ورود نامعتبر است"
        if (call.stop != null && !call.stop.isFinite()) errors += "حدضرر نامعتبر است"
        if (call.target != null && !call.target.isFinite()) errors += "هدف نامعتبر است"
        if (call.netR != null && !call.netR.isFinite()) errors += "نتیجه R نامعتبر است"
        if (call.status in setOf(CallStatus.TARGET, CallStatus.STOP, CallStatus.MANUAL_EXIT) &&
            call.netR == null) {
            errors += "معامله بسته‌شده بدون نتیجه قابل محاسبه است"
        }
        if (call.status != CallStatus.CANCELLED && call.status != CallStatus.NO_FILL &&
            call.side == Side.NEUTRAL && call.entry != null) {
            errors += "جهت معامله با ورود ثبت‌شده سازگار نیست"
        }
        return Audit(errors.isEmpty(), errors)
    }

    /**
     * Only calls with an observed netR are included in performance metrics.
     * Missing outcomes remain visible in missingResultCount and must never be silently
     * converted to wins, losses, or zero-return trades.
     */
    fun metrics(calls: List<Call>): Metrics {
        val resolved = calls.mapNotNull { call ->
            call.netR?.takeIf { it.isFinite() }
        }
        val wins = resolved.count { it > 0.0 }
        val losses = resolved.count { it < 0.0 }
        val grossWins = resolved.filter { it > 0.0 }.sum()
        val grossLosses = -resolved.filter { it < 0.0 }.sum()

        var equity = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        resolved.forEach { r ->
            equity += r
            if (equity > peak) peak = equity
            val drawdown = peak - equity
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        return Metrics(
            resolvedCount = resolved.size,
            wins = wins,
            losses = losses,
            winRatePercent = if (resolved.isEmpty()) null else wins * 100.0 / resolved.size,
            expectancyR = resolved.takeIf { it.isNotEmpty() }?.average(),
            profitFactor = when {
                grossLosses > 0.0 -> grossWins / grossLosses
                grossWins > 0.0 -> Double.POSITIVE_INFINITY
                else -> null
            },
            maxDrawdownR = if (resolved.isEmpty()) null else maxDrawdown,
            missingResultCount = calls.count { it.netR == null }
        )
    }

    /** A screening label only; never grants permission to trade live. */
    fun screeningLabel(metrics: Metrics): String = when {
        metrics.resolvedCount < 30 -> "INSUFFICIENT_SAMPLE"
        metrics.expectancyR == null || metrics.profitFactor == null -> "INCOMPLETE_RESULTS"
        metrics.expectancyR <= 0.0 || metrics.profitFactor <= 1.0 -> "NO_POSITIVE_EDGE_OBSERVED"
        else -> "REQUIRES_OUT_OF_SAMPLE_AND_FORWARD_REVIEW"
    }
}
