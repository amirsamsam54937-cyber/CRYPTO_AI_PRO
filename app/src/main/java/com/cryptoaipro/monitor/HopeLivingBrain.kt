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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Supervisory meta-controller for HOPE HUNTER.
 *
 * It coordinates outcome learning, research priorities, model-health checks and optional
 * adaptive scan cadence. It cannot change credentials, risk limits, or place exchange orders.
 * Web text is evidence to review, never executable instructions or direct neural labels.
 */
internal object HopeLivingBrain {
    private const val PREFS = "hope_living_brain"
    private const val AUTO_INTERVAL = "auto_interval"
    private const val MIN_SAMPLES_FOR_ADVISORY = 50

    data class Status(
        val cycles: Int,
        val learnedSamples: Int,
        val pendingSamples: Int,
        val researchItems: Int,
        val recentWinRate: Int,
        val onlineConfidence: Int,
        val scanIntervalMinutes: Int,
        val autoInterval: Boolean,
        val neuralReady: Boolean,
        val neuralManagement: String,
        val mode: String,
        val lastCycle: Long,
        val neuralForecast: WolfNeuralForecastJournal.Snapshot
    )

    fun status(context: Context): Status {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val learning = WolfLearningMemory.stats(context)
        val model = WolfPretrainedEnsemble.status()
        val outcomes = WolfSignalOutcomeJournal.snapshot(context).records.filter { it.closed }.takeLast(80)
        val wins = outcomes.count { it.pnlPct > 0.0 }
        val winRate = if (outcomes.isEmpty()) 0 else (wins * 100.0 / outcomes.size).toInt()
        val samples = p.getInt("last_signal_count", 0)
        val online = p.getInt("online_confidence", 50)
        val interval = context.getSharedPreferences("wolf_app_settings", Context.MODE_PRIVATE)
            .getInt("scan_interval_minutes", 3).coerceIn(1, 10)
        val auto = p.getBoolean(AUTO_INTERVAL, false)
        val ready = model.validatedForScoring
        val mode = p.getString("mode", if (ready) "ناظر تطبیقی" else "یادگیری و اعتبارسنجی") ?: "یادگیری و اعتبارسنجی"
        val neuralManagement = p.getString("neural_management", if (ready) "رأی شبکه عصبی تحت نظارت و قابل مقایسه با نظر سیستم" else "رأی شبکه عصبی فقط مشورتی؛ تا اعتبارسنجی از تصمیم نهایی جداست") ?: "در انتظار ارزیابی شبکه عصبی"
        return Status(
            p.getInt("cycles", 0), learning.samples, learning.pending,
            HopeResearchEngine.memoryCount(context), winRate, online, interval, auto,
            ready, neuralManagement, mode, p.getLong("last_cycle_at", 0L),
            WolfNeuralForecastJournal.snapshot(context)
        )
    }

    fun setAutoInterval(context: Context, enabled: Boolean): Int? {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_INTERVAL, enabled).apply()
        if (enabled) {
            val recommended = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("recommended_interval", 3).coerceIn(1, 10)
            context.getSharedPreferences("wolf_app_settings", Context.MODE_PRIVATE)
                .edit().putInt("scan_interval_minutes", recommended).apply()
            return recommended
        }
        return null
    }

    private fun researchPlan(
        learning: WolfLearningMemory.Stats,
        closed: List<WolfSignalOutcomeJournal.Record>,
        model: WolfPretrainedEnsemble.Status,
        signals: List<MarketSignal>
    ): List<String> {
        val topics = linkedSetOf<String>()
        topics += "official XT USDT-M perpetual futures API contract rules tick size lot size funding and liquidation documentation"
        topics += "cryptocurrency futures walk forward validation purged time series cross validation leakage transaction costs"
        if (!model.validatedForScoring) {
            topics += "probability calibration neural ensemble validation Brier score log loss baseline comparison cryptocurrency time series"
            topics += "online learning concept drift detection prequential evaluation replay buffer financial time series"
        }
        val recent = closed.takeLast(80)
        val winRate = if (recent.isEmpty()) 0.5 else recent.count { it.pnlPct > 0.0 }.toDouble() / recent.size
        if (recent.size >= 10 && winRate < 0.48) {
            topics += "crypto perpetual futures drawdown control position sizing volatility targeting risk of ruin research"
            topics += "trend following mean reversion regime detection choppy sideways markets false breakout filters"
        }
        if (recent.count { it.pnlPct < 0.0 } >= 8) {
            topics += "perpetual futures execution slippage spread market impact funding fee realistic backtest research"
        }
        val avgAtr = signals.map { it.technical.atrPercent }.filter { it.isFinite() && it > 0.0 }.average()
        if (avgAtr.isFinite() && avgAtr >= 3.0) {
            topics += "high volatility crypto futures risk management volatility regime transition liquidation cascades"
        } else {
            topics += "crypto futures low volatility compression breakout volume confirmation research"
        }
        if (learning.samples < 250) {
            topics += "minimum sample size statistical power financial trading model validation confidence intervals"
        }
        return topics.take(7)
    }

    suspend fun runCycle(
        context: Context,
        signals: List<MarketSignal>,
        learning: WolfLearningMemory.Stats,
        outcomes: List<WolfSignalOutcomeJournal.Record>
    ) = withContext(Dispatchers.IO) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previous = p.getLong("last_cycle_at", 0L)
        // Activity and foreground service may see the same scan; avoid duplicate learning/research work.
        if (now - previous < 90_000L) return@withContext
        val model = WolfPretrainedEnsemble.initialize(context)
        val closed = outcomes.filter { it.closed }.takeLast(120)
        val recent = closed.takeLast(80)
        val winRate = if (recent.isEmpty()) 50 else (recent.count { it.pnlPct > 0.0 } * 100.0 / recent.size).toInt()

        val onlinePrefs = context.getSharedPreferences("wolf_learning_memory", Context.MODE_PRIVATE)
        val predictions = signals.asSequence()
            .filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
            .take(32)
            .mapNotNull { signal -> runCatching { WolfDeepNeuralNetwork.predict(onlinePrefs, signal) }.getOrNull() }
            .filter { it.isFinite() }
            .toList()
        val onlineConfidence = if (predictions.isEmpty()) 50 else
            (predictions.map { maxOf(it, 1.0 - it) }.average() * 100.0).toInt().coerceIn(50, 99)

        val atr = signals.map { it.technical.atrPercent }.filter { it.isFinite() && it > 0.0 }.average()
        val recommendedInterval = when {
            !atr.isFinite() -> 5
            atr >= 3.0 -> 1
            atr >= 1.5 -> 3
            else -> 5
        }
        val auto = p.getBoolean(AUTO_INTERVAL, false)
        if (auto) context.getSharedPreferences("wolf_app_settings", Context.MODE_PRIVATE)
            .edit().putInt("scan_interval_minutes", recommendedInterval).apply()

        val mode = when {
            !model.validatedForScoring -> "یادگیری و اعتبارسنجی"
            recent.size >= 20 && winRate < 45 -> "بازبینی انتقادی عملکرد"
            else -> "ناظر تطبیقی"
        }
        p.edit()
            .putInt("cycles", p.getInt("cycles", 0) + 1)
            .putInt("last_signal_count", signals.size)
            .putInt("online_confidence", onlineConfidence)
            .putInt("recent_win_rate", winRate)
            .putInt("recommended_interval", recommendedInterval)
            .putString("mode", mode)
            .putString("neural_management", if (model.validatedForScoring) "مدل از دروازه اعتبارسنجی عبور کرده؛ مغز هوشمند توافق و اختلاف آن با سیستم را پایش می‌کند" else "مدل هنوز اعتبارسنجی نشده؛ مغز هوشمند رأی آن را ثبت و نمایش می‌دهد اما اثرش بر سیگنال نهایی را مسدود می‌کند")
            .putLong("last_cycle_at", now)
            .apply()

        val lastResearch = p.getLong("last_research_at", 0L)
        if (now - lastResearch >= 6L * 60L * 60L * 1000L) {
            val topics = researchPlan(learning, closed, model, signals)
            val count = runCatching { HopeResearchEngine.refreshCoreKnowledge(context, topics) }.getOrDefault(0)
            if (count > 0) p.edit().putLong("last_research_at", now).putInt(
                "last_research_count", p.getInt("last_research_count", 0) + count
            ).apply()
        }
    }
}

@Composable
internal fun HopeLivingBrainPanel(context: Context, onIntervalChanged: (Int) -> Unit = {}) {
    var status by remember { mutableStateOf(HopeLivingBrain.status(context)) }
    LaunchedEffect(context) {
        while (true) {
            status = HopeLivingBrain.status(context)
            delay(5000)
        }
    }
    val gold = Color(0xFFFFD45A)
    val muted = Color(0xFFB8B0A0)
    Surface(
        color = Color(0xFF111111),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, gold.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("مغز زنده HOPE HUNTER", color = Color.White, fontSize = 14.sp)
            Text("وضعیت: ${status.mode}", color = gold, fontSize = 11.sp)
            Text("مدیریت شبکه عصبی: ${status.neuralManagement}", color = if (status.neuralReady) Color(0xFF73D6A0) else gold, fontSize = 10.sp, lineHeight = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("چرخه‌های نظارت: ${status.cycles}", color = muted, fontSize = 10.sp)
                Text("نمونه‌های یادگرفته‌شده: ${status.learnedSamples}", color = muted, fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("حافظه پژوهش: ${status.researchItems} منبع", color = muted, fontSize = 10.sp)
                Text("نمونه‌های در انتظار: ${status.pendingSamples}", color = muted, fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("اعتماد خروجی شبکه آنلاین: ${status.onlineConfidence}٪ • مشورتی", color = muted, fontSize = 10.sp)
                Text(if (status.neuralReady) "مدل اصلی معتبر" else "مدل اصلی نیازمند اعتبارسنجی", color = if (status.neuralReady) Color(0xFF73D6A0) else gold, fontSize = 9.sp)
            }
            val forecast = status.neuralForecast
            Text("آزمون مستقل ۱۰ شبکه عصبی • افق واقعی ۶ ساعت", color = Color.White, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ارزیابی‌شده: ${forecast.evaluated}", color = muted, fontSize = 9.sp)
                Text("در انتظار: ${forecast.pending}", color = muted, fontSize = 9.sp)
                Text("خنثی: ${forecast.neutral}", color = muted, fontSize = 9.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("دقت رأی مجموعه: ${String.format(java.util.Locale.US, "%.1f", forecast.ensembleAccuracy * 100.0)}٪", color = muted, fontSize = 9.sp)
                Text("Brier: ${String.format(java.util.Locale.US, "%.3f", forecast.ensembleBrier)}", color = muted, fontSize = 9.sp)
            }
            if (forecast.evaluated == 0) {
                Text("پس از بسته‌شدن افق ۶ساعته، عملکرد هر شبکه اینجا ثبت می‌شود.", color = muted, fontSize = 9.sp)
            } else {
                forecast.members.forEach { member ->
                    val best = forecast.bestMember?.index == member.index
                    Text(
                        "شبکه ${member.index} • نمونه ${member.samples} • دقت رأی ${String.format(java.util.Locale.US, "%.0f", member.accuracy * 100.0)}٪ • پوشش ${String.format(java.util.Locale.US, "%.0f", member.coverage * 100.0)}٪ • Brier ${String.format(java.util.Locale.US, "%.3f", member.brier)}" + if (best) " • بهترین فعلی" else "",
                        color = if (best) gold else muted,
                        fontSize = 9.sp,
                        lineHeight = 13.sp
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("مدیریت خودکار فاصله اسکن", color = Color.White, fontSize = 11.sp)
                    Text("با توجه به نوسان بازار، بین ۱ تا ۵ دقیقه تنظیم می‌شود.", color = muted, fontSize = 9.sp)
                }
                Switch(
                    checked = status.autoInterval,
                    onCheckedChange = {
                        HopeLivingBrain.setAutoInterval(context, it)?.let(onIntervalChanged)
                        status = HopeLivingBrain.status(context)
                    }
                )
            }
            Text("مغز از نتیجه سیگنال‌های بسته‌شده یاد می‌گیرد، موضوعات پژوهش را بر اساس ضعف‌ها تغییر می‌دهد و منابع را در حافظه محلی نگه می‌دارد. محتوای وب مستقیماً برچسب آموزش یا مجوز معامله نیست؛ تغییر وزن‌ها و انتشار سیگنال فقط پس از ارزیابی مستقل مجاز است.", color = muted, fontSize = 9.sp, lineHeight = 14.sp)
        }
    }
}
