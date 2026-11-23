package com.cryptoaipro.monitor

import kotlin.math.sqrt

/**
 * Detects inference inputs that are materially outside the training distribution.
 * This is a conservative abstention guard, not a claim that all forms of drift are detected.
 */
internal object WolfModelInputGuard {
    data class Assessment(
        val available: Boolean,
        val rmsZScore: Double,
        val maxAbsZScore: Double,
        val outOfDistribution: Boolean,
        val reason: String
    )

    fun assess(raw: DoubleArray, mean: DoubleArray, scale: DoubleArray): Assessment {
        if (raw.size != 7 || mean.size != 7 || scale.size != 7 ||
            raw.any { !it.isFinite() } || mean.any { !it.isFinite() } ||
            scale.any { !it.isFinite() || it <= 0.0 }
        ) return Assessment(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, true,
            "ورودی یا آمار آموزش نامعتبر است")
        val z = raw.indices.map { (raw[it] - mean[it]) / scale[it] }
        val rms = sqrt(z.sumOf { it * it } / z.size)
        val maxAbs = z.maxOf { kotlin.math.abs(it) }
        val outside = !rms.isFinite() || !maxAbs.isFinite() || rms > 3.0 || maxAbs > 6.0
        return Assessment(true, rms, maxAbs, outside, when {
            outside -> "ورودی بازار خارج از دامنه آموزش؛ مدل باید از تصمیم اجرایی خودداری کند"
            else -> "ورودی در محدوده آماری قابل قبول آموزش"
        })
    }
}
