package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Persistent simulated execution engine. It has no exchange account or order API access. */
internal object HopePaperTradingEngine {
    private const val PREFS = "hope_paper_ledger"
    private const val KEY = "ledger_v1"
    private const val STARTING_EQUITY = 1000.0
    private const val FEE_PER_SIDE = 0.0006
    private const val SLIPPAGE_PER_SIDE = 0.0005
    private const val ESTIMATED_FUNDING_PER_8H = 0.0001
    private const val MAX_HOLD_MS = 48L * 60L * 60L * 1000L

    data class Position(
        val symbol: String, val side: String, val quantity: Double,
        val entry: Double, val stop: Double, val target: Double,
        val leverage: Int, val openedAt: Long, val mark: Double,
        val riskUsdt: Double, val notionalUsdt: Double,
        val initialRiskDistance: Double
    )
    data class Snapshot(
        val equity: Double, val startingEquity: Double, val realizedPnl: Double,
        val dailyPnl: Double, val dailyLossPct: Double, val openRiskPct: Double,
        val closedTrades: Int, val wins: Int, val losses: Int,
        val positions: List<Position>, val lastAction: String
    )

    @Synchronized
    fun processScan(context: Context, signals: List<MarketSignal>): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        val day = now / 86_400_000L
        val equity = root.optDouble("equity", STARTING_EQUITY).takeIf { it.isFinite() && it > 0.0 } ?: STARTING_EQUITY
        var dailyStart = root.optDouble("dailyStartEquity", equity)
        var dailyDay = root.optLong("dailyDay", day)
        var dailyPnl = root.optDouble("dailyPnl", 0.0)
        if (dailyDay != day) {
            dailyDay = day
            dailyStart = equity
            dailyPnl = 0.0
        }
        var realized = root.optDouble("realizedPnl", 0.0)
        var closed = root.optInt("closedTrades", 0)
        var wins = root.optInt("wins", 0)
        var losses = root.optInt("losses", 0)
        var lastAction = root.optString("lastAction", "هنوز معامله شبیه‌سازی‌شده‌ای ثبت نشده است.")
        val cooldowns = root.optJSONObject("cooldowns") ?: JSONObject()
        val array = root.optJSONArray("positions") ?: JSONArray()
        val positions = (0 until array.length()).mapNotNull { i ->
            val p = array.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                Position(p.getString("symbol"), p.getString("side"), p.getDouble("quantity"),
                    p.getDouble("entry"), p.getDouble("stop"), p.getDouble("target"),
                    p.optInt("leverage", 1), p.getLong("openedAt"), p.optDouble("mark", p.getDouble("entry")),
                    p.optDouble("riskUsdt"), p.optDouble("notionalUsdt"),
                    p.optDouble("initialRiskDistance", abs(p.getDouble("entry") - p.getDouble("stop"))))
            }.getOrNull()
        }.toMutableList()
        val current = signals.associateBy { it.symbol }

        // Resolve positions against closed 15m candles. If SL and TP occur in the same candle,
        // conservatively assume SL was hit first because OHLC data cannot reveal the path.
        val survivors = mutableListOf<Position>()
        positions.forEach { p ->
            val signal = current[p.symbol]
            var exit: Double? = null
            var exitReason = ""
            if (signal != null) {
                val bars = signal.candles.filter { it.size >= 6 && it[5].isFinite() && it[5].toLong() > p.openedAt }
                    .sortedBy { it[5] }
                for (bar in bars) {
                    val open = bar[0]; val high = bar[1]; val low = bar[2]; val close = bar[3]
                    if (!listOf(open, high, low, close).all { it.isFinite() && it > 0.0 }) continue
                    val isLong = p.side == "LONG_BIAS"
                    val slHit = if (isLong) low <= p.stop else high >= p.stop
                    val tpHit = if (isLong) high >= p.target else low <= p.target
                    if (slHit) {
                        exit = if (isLong) minOf(open, p.stop) else maxOf(open, p.stop)
                        exitReason = "حد ضرر"
                        break
                    }
                    if (tpHit) {
                        exit = p.target
                        exitReason = "هدف اول"
                        break
                    }
                }
                if (exit == null && now - p.openedAt >= MAX_HOLD_MS && signal.price > 0.0) {
                    exit = signal.price
                    exitReason = "پایان حداکثر زمان نگهداری"
                }
                if (exit == null && signal.price > 0.0) {
                    val trailedStop = DynamicTradingPolicy.trailStop(
                        p.side, p.entry, signal.price, p.stop, p.initialRiskDistance, signal.atr,
                        roundTripCostFraction = FEE_PER_SIDE * 2.0 + SLIPPAGE_PER_SIDE * 2.0
                    )
                    survivors += p.copy(mark = signal.price, stop = trailedStop)
                }
            } else {
                // Missing/stale symbol data must never silently erase an open simulated position.
                // Keep the last known mark until fresh data returns; do not invent an exit price.
                survivors += p
            }
            if (exit != null) {
                val isLong = p.side == "LONG_BIAS"
                val exitFill = exit!! * (if (isLong) 1.0 - SLIPPAGE_PER_SIDE else 1.0 + SLIPPAGE_PER_SIDE)
                val gross = (exitFill - p.entry) * p.quantity * (if (isLong) 1.0 else -1.0)
                val fees = (p.entry * p.quantity + exitFill * p.quantity) * FEE_PER_SIDE
                val hours = ((now - p.openedAt).coerceAtLeast(0L)).toDouble() / 3_600_000.0
                val funding = p.notionalUsdt * ESTIMATED_FUNDING_PER_8H * (hours / 8.0)
                val net = gross - fees - funding
                realized += net
                dailyPnl += net
                cooldowns.put(p.symbol, now + 60L * 60L * 1000L)
                closed += 1
                if (net >= 0.0) wins++ else losses++
                lastAction = "${p.symbol} • خروج: $exitReason • نتیجه خالص تخمینی: ${"%.3f".format(java.util.Locale.US, net)} USDT"
            }
        }

        fun unrealizedNet(p: Position): Double {
            val isLong = p.side == "LONG_BIAS"
            val exitFill = p.mark * (if (isLong) 1.0 - SLIPPAGE_PER_SIDE else 1.0 + SLIPPAGE_PER_SIDE)
            val gross = (exitFill - p.entry) * p.quantity * (if (isLong) 1.0 else -1.0)
            val fees = (p.entry * p.quantity + exitFill * p.quantity) * FEE_PER_SIDE
            val hours = ((now - p.openedAt).coerceAtLeast(0L)).toDouble() / 3_600_000.0
            val funding = p.notionalUsdt * ESTIMATED_FUNDING_PER_8H * (hours / 8.0)
            return gross - fees - funding
        }
        val floatingPnl = survivors.sumOf { unrealizedNet(it) }
        val currentEquity = max(0.0, STARTING_EQUITY + realized + floatingPnl)
        val dailyLossPct = if (dailyStart > 0.0) max(0.0, -(dailyPnl + floatingPnl) / dailyStart * 100.0) else 100.0
        val openRiskUsdt = survivors.sumOf { it.riskUsdt }
        val openRiskPct = if (currentEquity > 0.0) openRiskUsdt / currentEquity * 100.0 else 100.0
        val enabled = prefs.getBoolean("hope_risk_ack_v1", true) &&
            prefs.getBoolean("hope_paper_mode_enabled", true)

        val neuralValidated = WolfPretrainedEnsemble.status().validatedForScoring
        if (enabled && dailyLossPct < 2.0 && survivors.size < 3 && openRiskPct < 2.5) {
            val activeSymbols = survivors.map { it.symbol }.toSet()
            signals.asSequence()
                .filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
                .filter { it.symbol !in activeSymbols }
                .filter { cooldowns.optLong(it.symbol, 0L) <= now }
                .filter { s ->
                    val validGeometry = if (s.direction == "LONG_BIAS")
                        s.stop < s.entry && s.tp1 > s.entry else s.stop > s.entry && s.tp1 < s.entry
                    validGeometry && s.entry > 0.0 && s.price > 0.0 &&
                        abs(s.price - s.entry) / s.entry <= 0.0075 &&
                        s.confidence >= 70 && s.signalTier != "بدون رتبه"
                }
                .sortedByDescending { it.confidence }
                .forEach { s ->
                    if (survivors.size >= 3 || dailyLossPct >= 2.0 || currentEquity <= 0.0) return@forEach
                    if (survivors.any { it.symbol == s.symbol }) return@forEach
                    val openRisk = survivors.sumOf { it.riskUsdt }
                    val openRiskPctNow = openRisk / currentEquity * 100.0
                    val regime = WolfRegimeRiskEngine.analyze(s.candles) ?: return@forEach
                    val riskReward = abs(s.tp1 - s.entry) / abs(s.entry - s.stop).coerceAtLeast(1e-12)
                    val plan = DynamicTradingPolicy.evaluate(
                        DynamicTradingPolicy.Input(
                            direction = s.direction,
                            confidence = s.confidence,
                            neuralUpProbability = s.neuralScore.coerceIn(1, 99) / 100.0,
                            neuralValidated = neuralValidated,
                            consensusDirection = s.consensus?.direction ?: "NEUTRAL",
                            consensusAgreement = s.consensus?.agreement ?: 0,
                            riskReward = riskReward,
                            atrPercent = s.technical.atrPercent,
                            volatilityRatio = regime.volatilityRatio,
                            structuralBreak = regime.structuralBreak,
                            dailyLossPercent = dailyLossPct,
                            portfolioOpenRiskPercent = openRiskPctNow,
                            openPositions = survivors.size,
                            maxPositions = 3
                        ),
                        equityUsdt = currentEquity,
                        availableUsdt = currentEquity,
                        entry = s.entry,
                        stop = s.stop,
                        estimatedRoundTripCostPercent = (FEE_PER_SIDE * 2.0 + SLIPPAGE_PER_SIDE * 2.0) * 100.0
                    ) ?: return@forEach
                    if ((openRisk + plan.estimatedLossAtStopUsdt) / currentEquity * 100.0 > 2.5) return@forEach
                    val isLong = s.direction == "LONG_BIAS"
                    val fill = s.entry * (if (isLong) 1.0 + SLIPPAGE_PER_SIDE else 1.0 - SLIPPAGE_PER_SIDE)
                    val position = Position(s.symbol, s.direction, plan.quantity, fill, s.stop, s.tp1,
                        plan.leverage, s.candles.lastOrNull()?.getOrNull(5)?.toLong() ?: now,
                        s.price, plan.estimatedLossAtStopUsdt, plan.notionalUsdt, abs(fill - s.stop))
                    survivors += position
                    lastAction = "${s.symbol} • ورود شبیه‌سازی‌شده ${s.direction} • حجم ${"%.5f".format(java.util.Locale.US, sizing.quantity)}"
                    if (survivors.size >= 3) return@forEach
                }
        }

        val updatedFloatingPnl = survivors.sumOf { unrealizedNet(it) }
        val updatedEquity = max(0.0, STARTING_EQUITY + realized + updatedFloatingPnl)
        val updatedDailyLossPct = if (dailyStart > 0.0) max(0.0, -(dailyPnl + updatedFloatingPnl) / dailyStart * 100.0) else 100.0
        val updatedOpenRiskPct = if (updatedEquity > 0.0) survivors.sumOf { it.riskUsdt } / updatedEquity * 100.0 else 100.0
        val out = JSONObject().apply {
            put("equity", updatedEquity); put("dailyStartEquity", dailyStart); put("dailyDay", dailyDay)
            put("dailyPnl", dailyPnl); put("realizedPnl", realized); put("closedTrades", closed)
            put("wins", wins); put("losses", losses); put("lastAction", lastAction)
            put("cooldowns", cooldowns)
            put("positions", JSONArray().apply { survivors.forEach { p ->
                put(JSONObject().apply {
                    put("symbol", p.symbol); put("side", p.side); put("quantity", p.quantity)
                    put("entry", p.entry); put("stop", p.stop); put("target", p.target)
                    put("leverage", p.leverage); put("openedAt", p.openedAt); put("mark", p.mark)
                    put("riskUsdt", p.riskUsdt); put("notionalUsdt", p.notionalUsdt)
                    put("initialRiskDistance", p.initialRiskDistance)
                })
            } })
        }
        prefs.edit().putString(KEY, out.toString()).apply()
        return Snapshot(updatedEquity, STARTING_EQUITY, realized, dailyPnl, updatedDailyLossPct,
            updatedOpenRiskPct, closed, wins, losses, survivors, lastAction)
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())
        val array = root.optJSONArray("positions") ?: JSONArray()
        val positions = (0 until array.length()).mapNotNull { i ->
            val p = array.optJSONObject(i) ?: return@mapNotNull null
            runCatching { Position(p.getString("symbol"), p.getString("side"), p.getDouble("quantity"),
                p.getDouble("entry"), p.getDouble("stop"), p.getDouble("target"), p.optInt("leverage", 1),
                p.getLong("openedAt"), p.optDouble("mark", p.getDouble("entry")), p.optDouble("riskUsdt"),
                p.optDouble("notionalUsdt"), p.optDouble("initialRiskDistance", abs(p.getDouble("entry") - p.getDouble("stop")))) }.getOrNull()
        }
        val equity = root.optDouble("equity", STARTING_EQUITY)
        val dailyStart = root.optDouble("dailyStartEquity", equity)
        val dailyPnl = root.optDouble("dailyPnl", 0.0)
        return Snapshot(equity, STARTING_EQUITY, root.optDouble("realizedPnl", 0.0), dailyPnl,
            if (dailyStart > 0) max(0.0, -dailyPnl / dailyStart * 100.0) else 100.0,
            if (equity > 0) positions.sumOf { it.riskUsdt } / equity * 100.0 else 100.0,
            root.optInt("closedTrades"), root.optInt("wins"), root.optInt("losses"), positions,
            root.optString("lastAction", "هنوز معامله شبیه‌سازی‌شده‌ای ثبت نشده است."))
    }
}

@Composable
internal fun HopePaperTradingDashboard(snapshot: HopePaperTradingEngine.Snapshot) {
    val gold = Color(0xFFFFD45A)
    val ivory = Color(0xFFECE5D6)
    val muted = Color(0xFFB8B0A0)
    Surface(
        color = Color(0xFF100E09),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, gold.copy(alpha = 0.48f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("میز معاملات آزمایشی", color = ivory, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("PAPER • بدون پول واقعی", color = gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PaperMetric("موجودی فرضی", "${"%.2f".format(java.util.Locale.US, snapshot.equity)} USDT")
                PaperMetric("سود/زیان بسته", "${"%+.2f".format(java.util.Locale.US, snapshot.realizedPnl)}")
                PaperMetric("موقعیت باز", snapshot.positions.size.toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PaperMetric("زیان روزانه", "${"%.2f".format(java.util.Locale.US, snapshot.dailyLossPct)}٪")
                PaperMetric("ریسک باز", "${"%.2f".format(java.util.Locale.US, snapshot.openRiskPct)}٪")
                PaperMetric("برد/باخت", "${snapshot.wins}/${snapshot.losses}")
            }
            if (snapshot.positions.isEmpty()) {
                Text("در حال حاضر معامله فرضی بازی وجود ندارد.", color = muted, fontSize = 10.sp)
            } else snapshot.positions.forEach { p ->
                val tint = if (p.side == "LONG_BIAS") Color(0xFF20D477) else Color(0xFFFF5965)
                Text("${p.symbol} • ${if (p.side == "LONG_BIAS") "LONG" else "SHORT"} • ${p.leverage}×", color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("ورود: ${"%.6f".format(java.util.Locale.US, p.entry)} • حدضرر: ${"%.6f".format(java.util.Locale.US, p.stop)} • هدف: ${"%.6f".format(java.util.Locale.US, p.target)}", color = muted, fontSize = 9.sp)
            }
            Text("آخرین رویداد: ${snapshot.lastAction}", color = gold, fontSize = 9.sp, lineHeight = 14.sp)
            Text("کارمزد، لغزش و فاندینگ در این شبیه‌ساز تخمینی‌اند؛ نتیجه آن معادل اجرای واقعی صرافی نیست.", color = muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun PaperMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Color(0xFFB8B0A0), fontSize = 8.sp)
        Text(value, color = Color(0xFFFFD45A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
