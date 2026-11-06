package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Persistent autonomous simulator. This object has no exchange-order dependency by design. */
object PaperTradingEngine {
    private const val PREFS = "crypto_ai_paper_trader_v2"
    private const val POSITIONS = "positions"
    private const val JOURNAL = "journal"
    private const val MAX_POSITIONS = 3
    private const val MAX_DAILY_TRADES = 8
    private const val DAILY_LOSS_CAP = 0.02
    private const val DRAWDOWN_CAP = 0.08
    private const val PORTFOLIO_RISK_CAP = 0.03
    private const val TRADE_RISK_CAP = 0.01
    private const val COOLDOWN_MS = 3_600_000L
    private const val FEE = 0.0004
    private const val SLIPPAGE = 0.0006

    data class Snapshot(
        val equity: Double, val startCapital: Double, val dailyPnl: Double, val totalPnl: Double,
        val openPositions: Int, val closedTrades: Int, val wins: Int, val losses: Int,
        val tradesToday: Int, val winRate: Double, val peak: Double,
        val halted: Boolean, val haltReason: String, val journal: List<String>
    )

    @Synchronized
    fun process(context: Context, signals: List<MarketSignal>, startingCapital: Double, riskPct: Double, leverage: Int): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val today = utcDay(now)
        var equity = prefs.getString("equity", null)?.toDoubleOrNull() ?: startingCapital.coerceIn(10.0, 1_000_000.0)
        val initial = prefs.getString("initial", null)?.toDoubleOrNull() ?: startingCapital.coerceIn(10.0, 1_000_000.0)
        var peak = max(prefs.getString("peak", null)?.toDoubleOrNull() ?: equity, equity)
        var dayStart = prefs.getString("day_start", null)?.toDoubleOrNull() ?: equity
        var dayKey = prefs.getString("day", today) ?: today
        var dailyPnl = prefs.getString("daily_pnl", null)?.toDoubleOrNull() ?: 0.0
        var tradesToday = prefs.getInt("trades_today", 0)
        var closed = prefs.getInt("closed", 0)
        var wins = prefs.getInt("wins", 0)
        var losses = prefs.getInt("losses", 0)
        val positions = JSONArray(prefs.getString(POSITIONS, "[]") ?: "[]")
        val journal = JSONArray(prefs.getString(JOURNAL, "[]") ?: "[]")
        if (dayKey != today) {
            dayKey = today; dayStart = equity; dailyPnl = 0.0; tradesToday = 0
            note(journal, "روز جدید UTC؛ محدودیت روزانه بازنشانی شد")
        }

        val quotes = signals.associateBy { it.symbol }
        val survivors = JSONArray()
        val exited = mutableSetOf<String>()
        for (i in 0 until positions.length()) {
            val p = positions.optJSONObject(i) ?: continue
            val s = quotes[p.optString("symbol")]
            if (s == null || s.price <= 0.0) { survivors.put(p); continue }
            val symbol = p.optString("symbol")
            val dir = p.optInt("dir", 1)
            val entry = p.optDouble("entry")
            var stop = p.optDouble("stop")
            val originalQty = p.optDouble("qty")
            var remaining = p.optDouble("remaining", originalQty)
            if (entry <= 0.0 || remaining <= 0.0) continue
            val mark = s.price
            val stopped = if (dir > 0) mark <= stop else mark >= stop
            if (stopped) {
                val fill = stop * (1.0 - SLIPPAGE * dir)
                val pnl = (fill - entry) * remaining * dir - (entry + fill) * remaining * FEE
                equity += pnl; dailyPnl += pnl; closed++
                val netTradePnl = p.optDouble("realized_pnl", 0.0) + pnl
                if (netTradePnl >= 0) wins++ else losses++
                updateOnlineLearner(prefs, p, netTradePnl)
                exited.add(symbol); note(journal, "حدضرر $symbol • PnL ${fmt(netTradePnl)} USDT")
                continue
            }
            var stage = p.optInt("stage", 0)
            val targets = listOf(p.optDouble("tp1"), p.optDouble("tp2"), p.optDouble("tp3"))
            val cuts = listOf(0.40, 0.30, 0.30)
            var cyclePnl = 0.0
            while (stage < 3) {
                val target = targets[stage]
                val reached = if (dir > 0) mark >= target else mark <= target
                if (!reached) break
                val part = if (stage == 2) remaining else min(remaining, originalQty * cuts[stage])
                val fill = target * (1.0 - SLIPPAGE * dir)
                val pnl = (fill - entry) * part * dir - (entry + fill) * part * FEE
                equity += pnl; dailyPnl += pnl; cyclePnl += pnl; remaining -= part
                p.put("realized_pnl", p.optDouble("realized_pnl", 0.0) + pnl)
                stage++
                if (stage == 1) { stop = entry; note(journal, "TP1 $symbol • ۴۰٪ خروج؛ استاپ سر به سر شد") }
                else note(journal, "TP$stage $symbol • خروج مرحله‌ای")
            }
            if (remaining <= originalQty * 0.001) {
                val netTradePnl = p.optDouble("realized_pnl", 0.0)
                closed++; if (netTradePnl >= 0) wins++ else losses++
                updateOnlineLearner(prefs, p, netTradePnl)
                exited.add(symbol); note(journal, "تکمیل خروج $symbol • PnL ${fmt(netTradePnl)}")
                continue
            }
            if (stage >= 1 && s.atr > 0.0) {
                val trail = if (dir > 0) mark - 1.25 * s.atr else mark + 1.25 * s.atr
                stop = if (dir > 0) max(stop, trail) else min(stop, trail)
            }
            p.put("stop", stop).put("remaining", remaining).put("stage", stage).put("mark", mark)
            survivors.put(p)
        }
        val unrealizedPnl = markToMarket(survivors, quotes)
        val markedEquity = equity + unrealizedPnl
        peak = max(peak, markedEquity)
        val dailyLoss = if (dayStart > 0) max(0.0, -(dailyPnl + unrealizedPnl)) / dayStart else 0.0
        val drawdown = if (peak > 0) max(0.0, peak - markedEquity) / peak else 0.0
        val halt = when {
            dailyLoss >= DAILY_LOSS_CAP -> "زیان روزانه به سقف ۲٪ رسید"
            drawdown >= DRAWDOWN_CAP -> "افت سرمایه از اوج به ۸٪ رسید"
            tradesToday >= MAX_DAILY_TRADES -> "سقف ۸ معامله روزانه تکمیل شد"
            else -> ""
        }
        val cooldowns = JSONObject(prefs.getString("cooldowns", "{}") ?: "{}")
        if (halt.isEmpty() && survivors.length() < MAX_POSITIONS && tradesToday < MAX_DAILY_TRADES) {
            val requestedRisk = markedEquity * (riskPct.coerceIn(0.1, 1.0) / 100.0)
            var riskBudget = max(0.0, equity * PORTFOLIO_RISK_CAP - openRisk(survivors))
            signals.asSequence()
                .filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
                .filter { it.confidence >= 72 && abs(it.score) >= 6 && it.technical.adx >= 18.0 }
                .filter { it.technical.relativeVolume >= 0.75 && it.entry > 0.0 && it.atr > 0.0 }
                .sortedWith(compareByDescending<MarketSignal> { it.confidence }.thenByDescending { abs(it.score) })
                .forEach { s ->
                    if (survivors.length() >= MAX_POSITIONS || tradesToday >= MAX_DAILY_TRADES || riskBudget <= 0.0) return@forEach
                    if ((0 until survivors.length()).any { survivors.optJSONObject(it)?.optString("symbol") == s.symbol }) return@forEach
                    if (exited.contains(s.symbol) || now - cooldowns.optLong(s.symbol, 0L) < COOLDOWN_MS) return@forEach
                    val dir = if (s.direction == "LONG_BIAS") 1 else -1
                    val distance = abs(s.entry - s.stop)
                    if (distance <= 0.0 || distance / s.entry > 0.06) return@forEach
                    val risk = min(requestedRisk, min(equity * TRADE_RISK_CAP, riskBudget))
                    var qty = risk / (distance * (1.0 + 2.0 * FEE))
                    qty = min(qty, equity * leverage.coerceIn(1, 3) * 0.35 / s.entry)
                    if (!qty.isFinite() || qty <= 0.0) return@forEach
                    val entry = s.entry * (1.0 + SLIPPAGE * dir)
                    val d = distance
                    survivors.put(JSONObject()
                        .put("symbol", s.symbol).put("dir", dir).put("entry", entry)
                        .put("stop", s.stop).put("tp1", entry + dir * d)
                        .put("tp2", entry + dir * d * 2).put("tp3", entry + dir * d * 3)
                        .put("qty", qty).put("remaining", qty).put("stage", 0)
                        .put("learning_features", learningFeatures(s))
                        .put("realized_pnl", 0.0)
                        .put("leverage", leverage.coerceIn(1, 3)).put("opened", now))
                    cooldowns.put(s.symbol, now); tradesToday++; riskBudget -= risk
                    note(journal, "ورود خودکار دمو ${s.symbol} ${if (dir > 0) "LONG" else "SHORT"} • ریسک ${fmt(risk)} USDT")
                }
        }
        prefs.edit()
            .putString("equity", equity.toString()).putString("initial", initial.toString())
            .putString("peak", peak.toString()).putString("day_start", dayStart.toString())
            .putString("day", dayKey).putString("daily_pnl", dailyPnl.toString())
            .putString("unrealized_pnl", unrealizedPnl.toString())
            .putString(POSITIONS, survivors.toString()).putString(JOURNAL, journal.toString())
            .putString("cooldowns", cooldowns.toString()).putInt("trades_today", tradesToday)
            .putInt("closed", closed).putInt("wins", wins).putInt("losses", losses).apply()
        return "دمو خودکار: ${survivors.length()} باز • امروز $tradesToday معامله • $closed بسته • سرمایه ${fmt(equity)} USDT" +
            if (halt.isNotEmpty()) " • توقف حفاظتی: $halt" else " • فعال"
    }

    @Synchronized
    fun snapshot(context: Context, fallback: Double = 1000.0): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val equity = p.getString("equity", null)?.toDoubleOrNull() ?: fallback
        val initial = p.getString("initial", null)?.toDoubleOrNull() ?: fallback
        val day = p.getString("day", utcDay(System.currentTimeMillis())) ?: utcDay(System.currentTimeMillis())
        val today = day == utcDay(System.currentTimeMillis())
        val dayStart = p.getString("day_start", null)?.toDoubleOrNull() ?: equity
        val realizedDaily = if (today) p.getString("daily_pnl", null)?.toDoubleOrNull() ?: 0.0 else 0.0
        val unrealized = p.getString("unrealized_pnl", null)?.toDoubleOrNull() ?: 0.0
        val daily = realizedDaily + unrealized
        val markedEquity = equity + unrealized
        val peak = p.getString("peak", null)?.toDoubleOrNull() ?: markedEquity
        val closed = p.getInt("closed", 0); val wins = p.getInt("wins", 0); val losses = p.getInt("losses", 0)
        val dailyLoss = if (dayStart > 0) max(0.0, -daily) / dayStart else 0.0
        val dd = if (peak > 0) max(0.0, peak - equity) / peak else 0.0
        val reason = when {
            dailyLoss >= DAILY_LOSS_CAP -> "سقف زیان روزانه ۲٪"
            dd >= DRAWDOWN_CAP -> "سقف افت سرمایه ۸٪"
            today && p.getInt("trades_today", 0) >= MAX_DAILY_TRADES -> "سقف معاملات روزانه"
            else -> ""
        }
        val j = JSONArray(p.getString(JOURNAL, "[]") ?: "[]")
        val entries = (0 until j.length()).mapNotNull { j.optString(it).takeIf(String::isNotBlank) }.takeLast(8).reversed()
        return Snapshot(markedEquity, initial, daily, markedEquity - initial,
            JSONArray(p.getString(POSITIONS, "[]") ?: "[]").length(), closed, wins, losses,
            if (today) p.getInt("trades_today", 0) else 0,
            if (closed > 0) wins.toDouble() / closed * 100.0 else 0.0,
            peak, reason.isNotEmpty(), reason, entries)
    }

    @Synchronized
    fun learningUpdates(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("learning_updates", 0)

    private fun learningFeatures(s: MarketSignal): JSONArray {
        val values = listOf(
            (s.score / 10.0).coerceIn(-1.0, 1.0),
            ((s.rsi - 50.0) / 50.0).coerceIn(-1.0, 1.0),
            when {
                s.technical.ema20 > s.technical.ema50 -> 1.0
                s.technical.ema20 < s.technical.ema50 -> -1.0
                else -> 0.0
            },
            (s.technical.adx / 50.0).coerceIn(0.0, 1.0),
            (s.technical.relativeVolume / 3.0).coerceIn(0.0, 1.0),
            ((s.technical.bollingerPosition - 0.5) * 2.0).coerceIn(-1.0, 1.0)
        )
        return JSONArray().also { array -> values.forEach { array.put(it) } }
    }

    private fun updateOnlineLearner(prefs: android.content.SharedPreferences, position: JSONObject, netPnl: Double) {
        val features = position.optJSONArray("learning_features") ?: return
        if (features.length() != 6 || !netPnl.isFinite()) return
        val weightsJson = JSONArray(prefs.getString("learning_weights", "[0.35,0.15,0.15,0.10,0.10,0.15]") ?: "[]")
        if (weightsJson.length() != 6) return
        val x = DoubleArray(6) { features.optDouble(it, 0.0).coerceIn(-1.0, 1.0) }
        val weights = DoubleArray(6) { weightsJson.optDouble(it, 0.0).coerceIn(-3.0, 3.0) }
        val bias = prefs.getString("learning_bias", "0.0")?.toDoubleOrNull() ?: 0.0
        val z = (bias + weights.indices.sumOf { weights[it] * x[it] }).coerceIn(-30.0, 30.0)
        val probability = 1.0 / (1.0 + kotlin.math.exp(-z))
        val target = if (netPnl > 0.0) 1.0 else 0.0
        val error = target - probability
        val rate = 0.03
        for (i in weights.indices) weights[i] = (weights[i] + rate * error * x[i]).coerceIn(-3.0, 3.0)
        val nextBias = (bias + rate * error).coerceIn(-3.0, 3.0)
        val nextWeights = JSONArray().also { array -> weights.forEach { array.put(it) } }
        prefs.edit()
            .putString("learning_weights", nextWeights.toString())
            .putString("learning_bias", nextBias.toString())
            .putInt("learning_updates", prefs.getInt("learning_updates", 0) + 1)
            .apply()
    }

    private fun markToMarket(positions: JSONArray, quotes: Map<String, MarketSignal>): Double {
        var total = 0.0
        for (i in 0 until positions.length()) {
            val p = positions.optJSONObject(i) ?: continue
            val quote = quotes[p.optString("symbol")] ?: continue
            val entry = p.optDouble("entry")
            val qty = p.optDouble("remaining")
            val dir = p.optInt("dir", 1)
            if (entry > 0.0 && qty > 0.0 && quote.price > 0.0) {
                total += (quote.price - entry) * qty * dir - (entry + quote.price) * qty * FEE
            }
        }
        return total
    }

    private fun openRisk(a: JSONArray): Double {
        var sum = 0.0
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            sum += abs(p.optDouble("entry") - p.optDouble("stop")) * p.optDouble("remaining")
        }
        return sum
    }
    private fun note(a: JSONArray, s: String) {
        a.put("${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} • $s")
        while (a.length() > 100) a.remove(0)
    }
    private fun utcDay(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
}
