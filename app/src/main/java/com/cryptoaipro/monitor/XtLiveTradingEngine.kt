package com.cryptoaipro.monitor

import android.content.Context

/**
 * Permanent safety stub.
 *
 * ZORO is an analysis-and-signals application. Live order execution and account
 * position management are intentionally disabled in this product build.
 */
internal object XtLiveTradingEngine {
    fun positionCount(context: Context): Int = 0

    fun isEnabled(context: Context): Boolean = false

    fun setEnabled(context: Context, enabled: Boolean) {
        // Persist only the disabled state so legacy preferences cannot re-enable execution.
        context.applicationContext
            .getSharedPreferences("zoro_live_trade_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("live_execution_enabled", false)
            .apply()
    }

    suspend fun processScan(context: Context, signals: List<MarketSignal>) = Unit

    suspend fun monitorOpenPositions(context: Context) = Unit
}
