package com.cryptoaipro.monitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Broad crypto-market context sourced from CoinGecko public global/market data.
 * TOTAL2/TOTAL3 are explicitly derived market-cap proxies, not TradingView index candles.
 * Session classification is internal only; no session clock or session panel is exposed.
 */
internal data class MacroMarketContext(
    val totalUsd: Double,
    val total2Usd: Double,
    val total3Usd: Double,
    val btcDominance: Double,
    val ethDominance: Double,
    val usdtDominance: Double,
    val total24hPct: Double,
    val total2_24hPct: Double,
    val total3_24hPct: Double,
    val biasScore: Int,
    val sessionCode: String,
    val sessionOverlap: Boolean,
    val fresh: Boolean,
    val sourceLabel: String,
    val updatedAtMs: Long
) {
    fun conciseSummary(): String {
        val freshness = if (fresh) "داده تازه" else "داده کلان قدیمی؛ بدون اثر بر امتیاز"
        return "نمای کلان بازار: TOTAL ${fmt(totalUsd)} | TOTAL2≈${fmt(total2Usd)} | TOTAL3≈${fmt(total3Usd)} میلیارد دلار" +
            " | تغییر ۲۴ساعته TOTAL2≈${fmtPct(total2_24hPct)} و TOTAL3≈${fmtPct(total3_24hPct)}" +
            " | BTC.D ${fmtPct(btcDominance)} | ETH.D ${fmtPct(ethDominance)} | USDT.D ${fmtPct(usdtDominance)}" +
            " | منبع CoinGecko؛ TOTAL2/TOTAL3 برآورد محاسباتی | $freshness"
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v / 1_000_000_000.0)
    private fun fmtPct(v: Double) = String.format(java.util.Locale.US, "%+.2f%%", v)
}

internal object HopeMacroMarketEngine {
    private const val GLOBAL_URL = "https://api.coingecko.com/api/v3/global"
    private const val MARKETS_URL = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=bitcoin%2Cethereum%2Ctether&price_change_percentage=24h"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_STALE_MS = 30 * 60 * 1000L
    @Volatile private var cached: MacroMarketContext? = null

    suspend fun fetch(): MacroMarketContext? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - it.updatedAtMs < CACHE_TTL_MS }?.let { return@withContext it }
        runCatching {
            val globalRoot = JSONObject(get(GLOBAL_URL))
            val global = globalRoot.optJSONObject("data") ?: error("CoinGecko global data missing")
            val caps = global.optJSONObject("total_market_cap") ?: error("Global market cap missing")
            val total = caps.optDouble("usd", Double.NaN)
            require(total.isFinite() && total > 0)
            val dominance = global.optJSONObject("market_cap_percentage") ?: JSONObject()
            val btcDom = dominance.optDouble("btc", Double.NaN)
            val ethDom = dominance.optDouble("eth", Double.NaN)
            require(btcDom.isFinite() && ethDom.isFinite())

            val markets = JSONArray(get(MARKETS_URL))
            fun coin(id: String): JSONObject = (0 until markets.length()).mapNotNull { markets.optJSONObject(it) }
                .firstOrNull { it.optString("id") == id } ?: error("CoinGecko $id market cap missing")
            val btc = coin("bitcoin")
            val eth = coin("ethereum")
            val usdt = coin("tether")
            val btcCap = btc.optDouble("market_cap", Double.NaN)
            val ethCap = eth.optDouble("market_cap", Double.NaN)
            val usdtCap = usdt.optDouble("market_cap", Double.NaN)
            val btcChange = btc.optDouble("price_change_percentage_24h", Double.NaN)
            val ethChange = eth.optDouble("price_change_percentage_24h", Double.NaN)
            val totalChange = global.optDouble("market_cap_change_percentage_24h_usd", Double.NaN)
            require(listOf(btcCap, ethCap, usdtCap, btcChange, ethChange, totalChange).all { it.isFinite() })
            val total2 = (total - btcCap).coerceAtLeast(0.0)
            val total3 = (total - btcCap - ethCap).coerceAtLeast(0.0)
            val totalAgo = total / (1.0 + totalChange / 100.0).coerceAtLeast(0.01)
            val btcAgo = btcCap / (1.0 + btcChange / 100.0).coerceAtLeast(0.01)
            val ethAgo = ethCap / (1.0 + ethChange / 100.0).coerceAtLeast(0.01)
            val total2Ago = (totalAgo - btcAgo).coerceAtLeast(1.0)
            val total3Ago = (totalAgo - btcAgo - ethAgo).coerceAtLeast(1.0)
            val total2Change = (total2 / total2Ago - 1.0) * 100.0
            val total3Change = (total3 / total3Ago - 1.0) * 100.0
            val usdtDom = usdtCap / total * 100.0
            val broadAltImpulse = total3Change * 0.65 + (total3Change - btcChange) * 0.35
            val bias = when {
                broadAltImpulse >= 2.0 -> 10
                broadAltImpulse >= 1.0 -> 6
                broadAltImpulse >= 0.35 -> 3
                broadAltImpulse <= -2.0 -> -10
                broadAltImpulse <= -1.0 -> -6
                broadAltImpulse <= -0.35 -> -3
                else -> 0
            }
            val utcHour = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).hour
            val asia = utcHour in 0..8
            val london = utcHour in 7..15
            val newYork = utcHour in 13..21
            val activeCount = listOf(asia, london, newYork).count { it }
            val sessionCode = when {
                activeCount >= 2 -> "OVERLAP"
                asia -> "ASIA"
                london -> "LONDON"
                newYork -> "NEW_YORK"
                else -> "QUIET"
            }
            MacroMarketContext(
                total, total2, total3, btcDom, ethDom, usdtDom,
                totalChange, total2Change, total3Change, bias,
                sessionCode, activeCount >= 2, true, "CoinGecko public API", now
            ).also { cached = it }
        }.getOrElse {
            cached?.let { previous ->
                previous.copy(
                    biasScore = if (now - previous.updatedAtMs <= MAX_STALE_MS) previous.biasScore else 0,
                    fresh = false
                )
            }
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6500
            connection.readTimeout = 8500
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "HOPE-HUNTER-Android/2.50")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("CoinGecko HTTP $code")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
