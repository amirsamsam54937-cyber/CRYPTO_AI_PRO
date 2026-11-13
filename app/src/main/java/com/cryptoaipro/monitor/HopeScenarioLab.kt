package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.Random
import kotlin.math.*

internal data class HopePriceScenario(
    val label: String, val probabilityPct: Double, val medianMovePct: Double,
    val lowerMovePct: Double, val upperMovePct: Double, val samples: Int,
    val medianPrice: Double, val lowerPrice: Double, val upperPrice: Double,
    val invalidationPrice: Double?
)
internal data class HopePathScenario(
    val label: String, val probabilityPct: Double, val medianMovePct: Double,
    val medianFavorableExcursionPct: Double, val medianAdverseExcursionPct: Double,
    val medianPrice: Double, val samples: Int
)
internal data class HopeScenarioForecast(
    val symbol: String, val basePrice: Double, val candleTimeMs: Long,
    val horizonBars: Int, val thresholdPct: Double, val scenarios: List<HopePriceScenario>,
    val method: String, val pathScenarios: List<HopePathScenario> = emptyList(),
    val tp1BeforeStopPct: Double = 0.0, val tp2BeforeStopPct: Double = 0.0,
    val tp3BeforeStopPct: Double = 0.0, val stopBeforeTargetPct: Double = 0.0,
    val barrierEvaluatedPaths: Int = 0, val noBarrierPct: Double = 0.0
) { val dominant: HopePriceScenario? get() = scenarios.maxByOrNull { it.probabilityPct } }
internal data class HopeScenarioScorecard(
    val forecast: HopeScenarioForecast?, val evaluated: Int, val correct: Int,
    val accuracyPct: Double, val brierScore: Double
)

/** Empirical block-bootstrap paths. Frequencies are not calibrated market odds. */
internal object HopeScenarioLab {
    private const val PREFS = "hope_scenario_lab"
    private const val KEY = "forecast_journal_v1"
    private const val HORIZON = 8
    private const val PATHS = 5000
    private const val BLOCK = 3
    private const val BAR_MS = 15L * 60L * 1000L

    fun forecast(signal: MarketSignal): HopeScenarioForecast? {
        val c = signal.candles
        if (c.size < 80 || c.any { r -> r.size < 6 || r.take(6).any { !it.isFinite() } ||
            r[0] <= 0.0 || r[1] < max(r[0], r[3]) || r[2] > min(r[0], r[3]) ||
            r[2] <= 0.0 || r[4] < 0.0 || r[5] <= 0.0 }) return null
        if (c.zipWithNext().any { (a,b) -> b[5] <= a[5] }) return null
        val closes = c.map { it[3] }
        val returns = closes.zipWithNext { a,b -> ln(b/a) }.filter { it.isFinite() }
        if (returns.size < 64) return null
        val recent = returns.takeLast(48)
        val mean = recent.average()
        val variance = recent.sumOf { (it-mean)*(it-mean) } / max(1, recent.size-1)
        val sigma = sqrt(variance).coerceIn(1e-6, 0.08)
        val base = c.last()[3]
        // Conditional tilt is deliberately bounded and only uses already-scored evidence.
        // Unvalidated neural votes never influence the path distribution.
        val directionSign = when (signal.direction) {
            "LONG_BIAS" -> 1.0
            "SHORT_BIAS" -> -1.0
            else -> 0.0
        }
        val confidenceTilt = directionSign * ((signal.confidence - 50.0) / 50.0).coerceIn(-1.0, 1.0) * sigma * 0.06
        val flowTilt = if (signal.hiddenFlowFresh) (signal.hiddenFlowScore / 25.0).coerceIn(-1.0, 1.0) * sigma * 0.04 else 0.0
        val neuralValidated = signal.neuralModelSource.contains("اعتبارسنجی‌شده")
        val neuralTilt = if (neuralValidated && signal.neuralDirection == signal.direction && directionSign != 0.0)
            directionSign * sigma * 0.025 else 0.0
        val drift = (mean * 0.25 + confidenceTilt + flowTilt + neuralTilt).coerceIn(-sigma*0.18, sigma*0.18)
        val threshold = max(0.30, (signal.technical.atrPercent.takeIf { it.isFinite() && it > 0.0 } ?: sigma*150.0) * sqrt(HORIZON.toDouble()) * 0.38)
        val rng = Random((signal.symbol.hashCode().toLong() shl 32) xor c.last()[5].toLong())
        val moves = ArrayList<Double>(PATHS)
        data class SimPath(val move: Double, val highExcursion: Double, val lowExcursion: Double, val firstBarrier: String?, val firstTradeBarrier: String?)
        val isLong = signal.direction == "LONG_BIAS"
        val isShort = signal.direction == "SHORT_BIAS"
        val stopLevel = signal.stop.takeIf { it.isFinite() && it > 0.0 }
        val targets = listOf(signal.tp1, signal.tp2, signal.tp3).filter { it.isFinite() && it > 0.0 }
        val hasTradeLevels = (isLong || isShort) && stopLevel != null && targets.size == 3
        val paths = ArrayList<SimPath>(PATHS)
        val support = signal.technical.support.takeIf { it.isFinite() && it > 0.0 && it < base }
        val resistance = signal.technical.resistance.takeIf { it.isFinite() && it > base }
        repeat(PATHS) {
            var sum = 0.0
            var highExcursion = 0.0
            var lowExcursion = 0.0
            var firstBarrier: String? = null
            var firstTradeBarrier: String? = null
            var step = 0
            while (step < HORIZON) {
                val start = rng.nextInt(recent.size)
                for (j in 0 until BLOCK) {
                    if (step >= HORIZON) break
                    val sourceIndex = (start + j) % recent.size
                    val sourceCandle = c[c.size - recent.size + sourceIndex]
                    sum += drift + (recent[sourceIndex] - mean) * 0.90
                    val move = (exp(sum) - 1.0) * 100.0
                    highExcursion = max(highExcursion, move)
                    lowExcursion = min(lowExcursion, move)
                    val simulatedPrice = base * exp(sum)
                    // Reuse sampled OHLC range around simulated close. If both barriers
                    // are touched in one candle, choose the stop first (conservative ordering).
                    val simulatedHigh = simulatedPrice * (sourceCandle[1] / sourceCandle[3])
                    val simulatedLow = simulatedPrice * (sourceCandle[2] / sourceCandle[3])
                    if (firstBarrier == null) {
                        if (resistance != null && simulatedHigh >= resistance) firstBarrier = "شکست مقاومت"
                        else if (support != null && simulatedLow <= support) firstBarrier = "شکست حمایت"
                    }
                    if (hasTradeLevels && firstTradeBarrier == null) {
                        firstTradeBarrier = if (isLong) {
                            when {
                                simulatedLow <= stopLevel!! -> "SL"
                                simulatedHigh >= targets[2] -> "TP3"
                                simulatedHigh >= targets[1] -> "TP2"
                                simulatedHigh >= targets[0] -> "TP1"
                                else -> null
                            }
                        } else {
                            when {
                                simulatedHigh >= stopLevel!! -> "SL"
                                simulatedLow <= targets[2] -> "TP3"
                                simulatedLow <= targets[1] -> "TP2"
                                simulatedLow <= targets[0] -> "TP1"
                                else -> null
                            }
                        }
                    }
                    step++
                }
            }
            val terminalMove = (exp(sum)-1.0)*100.0
            moves += terminalMove
            paths += SimPath(terminalMove, highExcursion, lowExcursion, firstBarrier, firstTradeBarrier)
        }
        fun q(v: List<Double>, p: Double): Double {
            if (v.isEmpty()) return 0.0
            val s = v.sorted()
            return s[((s.size-1)*p).toInt().coerceIn(0,s.lastIndex)]
        }
        val groups = listOf("صعودی" to moves.filter { it >= threshold },
            "خنثی" to moves.filter { it > -threshold && it < threshold },
            "نزولی" to moves.filter { it <= -threshold })
        val scenarios = groups.map { (name, values) ->
            val medianMove = q(values,0.50)
            val lowerMove = q(values,0.10)
            val upperMove = q(values,0.90)
            val invalidation = when (name) {
                "صعودی" -> base * (1.0 - threshold / 100.0)
                "نزولی" -> base * (1.0 + threshold / 100.0)
                else -> null
            }
            HopePriceScenario(name, values.size.toDouble()/moves.size*100.0,
                medianMove, lowerMove, upperMove, values.size,
                base * (1.0 + medianMove/100.0), base * (1.0 + lowerMove/100.0),
                base * (1.0 + upperMove/100.0), invalidation)
        }
        val pathLabels = listOf("شکست مقاومت", "شکست حمایت", "ادامه صعود", "ادامه نزول", "بازگشت صعودی", "بازگشت نزولی", "رنج")
        val classified = paths.groupBy { p ->
            when {
                p.firstBarrier != null -> p.firstBarrier
                p.move >= threshold -> "ادامه صعود"
                p.move <= -threshold -> "ادامه نزول"
                p.highExcursion >= threshold && p.move < 0.0 -> "بازگشت نزولی"
                p.lowExcursion <= -threshold && p.move > 0.0 -> "بازگشت صعودی"
                else -> "رنج"
            }
        }
        val pathScenarios = pathLabels.mapNotNull { label ->
            val group = classified[label].orEmpty()
            if (group.isEmpty()) null else HopePathScenario(
                label, group.size.toDouble() / PATHS * 100.0,
                group.map { it.move }.sorted().let { it[(it.size - 1) / 2] },
                group.map { it.highExcursion }.sorted().let { it[(it.size - 1) / 2] },
                group.map { it.lowExcursion }.sorted().let { it[(it.size - 1) / 2] },
                base * exp(group.map { it.move }.sorted().let { it[(it.size - 1) / 2] } / 100.0),
                group.size
            )
        }
        val tradePaths = paths.mapNotNull { it.firstTradeBarrier }
        val tradeEvaluated = if (hasTradeLevels) PATHS else 0
        val tp1BeforeStop = if (tradeEvaluated == 0) 0.0 else paths.count { it.firstTradeBarrier?.startsWith("TP") == true }.toDouble() / PATHS * 100.0
        val tp2BeforeStop = if (tradeEvaluated == 0) 0.0 else paths.count { it.firstTradeBarrier == "TP2" || it.firstTradeBarrier == "TP3" }.toDouble() / PATHS * 100.0
        val tp3BeforeStop = if (tradeEvaluated == 0) 0.0 else paths.count { it.firstTradeBarrier == "TP3" }.toDouble() / PATHS * 100.0
        val stopBeforeTarget = if (tradeEvaluated == 0) 0.0 else paths.count { it.firstTradeBarrier == "SL" }.toDouble() / PATHS * 100.0
        val noBarrier = if (tradeEvaluated == 0) 0.0 else paths.count { it.firstTradeBarrier == null }.toDouble() / PATHS * 100.0
        return HopeScenarioForecast(signal.symbol, base, c.last()[5].toLong(), HORIZON, threshold,
            scenarios, "۵۰۰۰ مسیر بوت‌استرپ شرطی با داده تاریخی، قدرت سیگنال و شواهد معتبر؛ برخورد تقریبی به سطوح ساختاری", pathScenarios,
            tp1BeforeStop, tp2BeforeStop, tp3BeforeStop, stopBeforeTarget, tradeEvaluated, noBarrier)
    }

    /** Apply empirical path-risk evidence to the signal score. This is a conservative filter,
     * not a calibrated probability or a guarantee; only the top 24 valid candidates are simulated. */
    fun applySignalImpact(signals: List<MarketSignal>): List<MarketSignal> {
        val selected = signals.filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
            .sortedByDescending { it.confidence }.take(24).associateBy { it.symbol }
        return signals.map { signal ->
            if (signal.symbol !in selected) return@map signal
            val forecast = forecast(signal) ?: return@map signal
            if (forecast.barrierEvaluatedPaths < PATHS) return@map signal
            val edge = forecast.tp1BeforeStopPct - forecast.stopBeforeTargetPct
            val adjustment = (edge / 100.0 * 24.0).toInt().coerceIn(-10, 10)
            val weakSetup = forecast.tp1BeforeStopPct <= 25.0 && forecast.stopBeforeTargetPct >= 50.0
            val side = if (signal.direction == "LONG_BIAS") 1 else -1
            val summary = " • ونوم/۵۰۰۰ مسیر: هدف قبل از حدضرر " + String.format(Locale.US, "%.1f", forecast.tp1BeforeStopPct) + "٪؛ حدضرر قبل از هدف " + String.format(Locale.US, "%.1f", forecast.stopBeforeTargetPct) + "٪"
            if (weakSetup) signal.copy(
                direction = "NEUTRAL", score = 0,
                confidence = (signal.confidence - 12).coerceIn(0, 100),
                reason = signal.reason + summary + " • رد سیگنال: ریسک مسیر شبیه‌سازی‌شده نامطلوب است."
            ) else signal.copy(
                score = (signal.score + side * adjustment).coerceIn(-100, 100),
                confidence = (signal.confidence + adjustment).coerceIn(0, 100),
                reason = signal.reason + summary + if (adjustment > 0) " • شواهد مسیر تقویت‌کننده" else if (adjustment < 0) " • شواهد مسیر تضعیف‌کننده" else " • اثر مسیر خنثی"
            )
        }
    }

    @Synchronized
    fun recordAndEvaluate(context: Context, signals: List<MarketSignal>): HopeScenarioScorecard {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val arr = root.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.toMutableList()
        val current = signals.associateBy { it.symbol }
        items.forEach { item ->
            if (item.optBoolean("resolved") || item.optBoolean("invalid")) return@forEach
            val s = current[item.optString("symbol")] ?: return@forEach
            val origin = item.optLong("candleTimeMs")
            val targetTime = origin + HORIZON*BAR_MS
            // Score at the forecast horizon, not at whatever much-later candle happens
            // to be newest when the app next wakes up. Allow at most one missing interval.
            val bar = s.candles.firstOrNull { it.size >= 6 &&
                it[5].toLong() >= targetTime && it[5].toLong() <= targetTime + BAR_MS } ?: return@forEach
            val base = item.optDouble("basePrice", 0.0)
            val actual = if (base > 0.0) (bar[3]/base-1.0)*100.0 else Double.NaN
            if (!actual.isFinite()) { item.put("invalid", true); return@forEach }
            val threshold = item.optDouble("thresholdPct", 0.30).coerceAtLeast(0.30)
            val realized = when { actual >= threshold -> "صعودی"; actual <= -threshold -> "نزولی"; else -> "خنثی" }
            val probabilities = item.optJSONArray("probabilities") ?: JSONArray()
            var brier = 0.0
            for (i in 0 until probabilities.length()) {
                val p = probabilities.optJSONObject(i) ?: continue
                val predicted = (p.optDouble("probability", 0.0) / 100.0).coerceIn(0.0, 1.0)
                val observed = if (p.optString("label") == realized) 1.0 else 0.0
                brier += (predicted - observed) * (predicted - observed)
            }
            item.put("resolved", true).put("actual", realized).put("actualMovePct", actual)
                .put("brier", brier).put("correct", item.optString("dominant") == realized)
        }
        var latest: HopeScenarioForecast? = null
        signals.sortedByDescending { it.confidence }.take(12).forEach { s ->
            val f = forecast(s) ?: return@forEach
            if (items.any { it.optString("symbol") == f.symbol && it.optLong("candleTimeMs") == f.candleTimeMs }) return@forEach
            val dominant = f.dominant ?: return@forEach
            val threshold = f.thresholdPct
            val probabilities = JSONArray().apply {
                f.scenarios.forEach { put(JSONObject().put("label",it.label).put("probability",it.probabilityPct)) }
            }
            items += JSONObject().put("symbol",f.symbol).put("basePrice",f.basePrice)
                .put("candleTimeMs",f.candleTimeMs).put("dominant",dominant.label)
                .put("probabilities",probabilities).put("thresholdPct",threshold)
                .put("resolved",false).put("createdAt",System.currentTimeMillis())
            if (latest == null) latest = f
        }
        while (items.size > 240) items.removeAt(0)
        prefs.edit().putString(KEY, JSONObject().put("items",JSONArray().apply { items.forEach { put(it) } }).toString()).apply()
        val done = items.filter { it.optBoolean("resolved") }
        val wins = done.count { it.optBoolean("correct") }
        val brierValues = done.mapNotNull { it.optDouble("brier", Double.NaN).takeIf { value -> value.isFinite() } }
        val meanBrier = if (brierValues.isEmpty()) 0.0 else brierValues.average()
        val bestAvailable = signals.maxByOrNull { it.confidence }?.let { forecast(it) }
        return HopeScenarioScorecard(latest ?: bestAvailable,
            done.size, wins, if (done.isEmpty()) 0.0 else wins.toDouble()/done.size*100.0, meanBrier)
    }
}

@Composable
internal fun HopeScenarioLabCard(scorecard: HopeScenarioScorecard?) {
    val gold = Color(0xFFFFD45A)
    val muted = Color(0xFFB8B0A0)
    val f = scorecard?.forecast
    Surface(color=Color(0xFF100E09), shape=RoundedCornerShape(17.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,gold.copy(alpha=0.48f)), modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("آزمایشگاه سناریوهای آینده", color=Color(0xFFECE5D6), fontWeight=FontWeight.Black, fontSize=14.sp, modifier=Modifier.weight(1f))
                Text("VENOM • 5K SCENARIO ENGINE", color=gold, fontSize=8.sp, fontWeight=FontWeight.Bold)
            }
            if (f == null) Text("برای شبیه‌سازی حداقل ۸۰ کندل معتبر لازم است.", color=muted, fontSize=10.sp)
            else {
                Text("${f.symbol} • قیمت مبنا ${String.format(Locale.US,"%.6f",f.basePrice)} • افق ${f.horizonBars} کندل ۱۵دقیقه‌ای", color=muted, fontSize=9.sp)
                // Visual price corridor: each horizontal segment is the 10th–90th
                // percentile range; the dot is its median and the vertical line is spot.
                val scenarioMin = (f.scenarios.minOfOrNull { it.lowerPrice } ?: f.basePrice).coerceAtMost(f.basePrice)
                val scenarioMax = (f.scenarios.maxOfOrNull { it.upperPrice } ?: f.basePrice).coerceAtLeast(f.basePrice)
                val scenarioSpan = (scenarioMax - scenarioMin).coerceAtLeast(f.basePrice * 0.0001)
                Canvas(Modifier.fillMaxWidth().height(74.dp)) {
                    val left = 12f
                    val right = size.width - 12f
                    val x = { price: Double -> left + (((price - scenarioMin) / scenarioSpan).toFloat().coerceIn(0f, 1f)) * (right - left) }
                    val baseX = x(f.basePrice)
                    drawLine(Color(0xFFEEE6D5).copy(alpha = 0.65f), Offset(baseX, 3f), Offset(baseX, size.height - 3f), 1.5f)
                    f.scenarios.forEachIndexed { index, scenario ->
                        val y = 15f + index * 23f
                        val tint = when(scenario.label) { "صعودی" -> Color(0xFF38C987); "نزولی" -> Color(0xFFFF6670); else -> Color(0xFFFFC65A) }
                        drawLine(tint.copy(alpha = 0.25f), Offset(left, y), Offset(right, y), 1f)
                        drawLine(tint, Offset(x(scenario.lowerPrice), y), Offset(x(scenario.upperPrice), y), 5f)
                        drawCircle(tint, 4f, Offset(x(scenario.medianPrice), y))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("کف بازه", color=muted, fontSize=8.sp)
                    Text("│ قیمت مبنا", color=Color(0xFFECE5D6), fontSize=8.sp)
                    Text("سقف بازه", color=muted, fontSize=8.sp)
                }
                f.scenarios.forEach { s ->
                    val tint = when(s.label) { "صعودی" -> Color(0xFF38C987); "نزولی" -> Color(0xFFFF6670); else -> Color(0xFFFFC65A) }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(s.label,color=tint,fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                        Text("${String.format(Locale.US,"%.1f",s.probabilityPct)}٪",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold)
                    }
                    Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFF302A1D),RoundedCornerShape(8.dp))) {
                        Box(Modifier.fillMaxWidth((s.probabilityPct/100.0).toFloat().coerceIn(0f,1f)).height(7.dp).background(tint,RoundedCornerShape(8.dp)))
                    }
                    Text("قیمت میانه ${String.format(Locale.US,"%.6f",s.medianPrice)} • محدوده شبیه‌سازی ${String.format(Locale.US,"%.6f",s.lowerPrice)} تا ${String.format(Locale.US,"%.6f",s.upperPrice)}",
                        color=muted,fontSize=9.sp)
                    Text("تغییر: ${String.format(Locale.US,"%+.2f",s.medianMovePct)}٪ میانه • بازه ۱۰ تا ۹۰٪: ${String.format(Locale.US,"%+.2f",s.lowerMovePct)} تا ${String.format(Locale.US,"%+.2f",s.upperMovePct)}٪" +
                        (s.invalidationPrice?.let { " • سطح ابطال: ${String.format(Locale.US,"%.6f",it)}" } ?: " • ابطال: خروج از محدوده نوسان"),
                        color=muted,fontSize=9.sp)
                }
                Text("سناریوی غالب شبیه‌سازی: ${f.dominant?.label ?: "نامشخص"} • فراوانی شرطی ${String.format(Locale.US,"%.1f",f.dominant?.probabilityPct ?: 0.0)}٪",
                    color=gold,fontSize=10.sp,fontWeight=FontWeight.Bold)
                if (f.barrierEvaluatedPaths > 0) {
                    Text("آزمون برخورد هدف قبل از حد ضرر • ${f.barrierEvaluatedPaths} مسیر کل شبیه‌سازی", color=gold, fontSize=10.sp, fontWeight=FontWeight.Bold)
                    Text("TP1 قبل از SL: ${String.format(Locale.US,"%.1f",f.tp1BeforeStopPct)}٪ • TP2 قبل از SL: ${String.format(Locale.US,"%.1f",f.tp2BeforeStopPct)}٪ • TP3 قبل از SL: ${String.format(Locale.US,"%.1f",f.tp3BeforeStopPct)}٪ • SL قبل از هدف: ${String.format(Locale.US,"%.1f",f.stopBeforeTargetPct)}٪ • بدون برخورد: ${String.format(Locale.US,"%.1f",f.noBarrierPct)}٪", color=Color.White, fontSize=9.sp)
                } else Text("احتمال هدف قبل از حد ضرر برای این سیگنال قابل محاسبه نیست؛ سطوح معتبر یا جهت مشخص وجود ندارد.", color=muted, fontSize=8.sp)
                Text("مسیرهای شرطی شبیه‌سازی‌شده", color=gold, fontSize=10.sp, fontWeight=FontWeight.Bold)
                f.pathScenarios.forEach { p ->
                    val tint = when {
                        p.label.contains("صعود") || p.label == "شکست مقاومت" -> Color(0xFF38C987)
                        p.label.contains("نزول") || p.label == "شکست حمایت" -> Color(0xFFFF6670)
                        else -> Color(0xFFFFC65A)
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(p.label, color=tint, fontSize=9.sp, modifier=Modifier.weight(1f))
                        Text(String.format(Locale.US,"%.1f%%",p.probabilityPct), color=Color.White, fontSize=9.sp, fontWeight=FontWeight.Bold)
                    }
                    Text("قیمت میانه ${String.format(Locale.US,"%.6f",p.medianPrice)} • حرکت ${String.format(Locale.US,"%+.2f",p.medianMovePct)}٪ • بیشینه مسیر ${String.format(Locale.US,"%+.2f",p.medianFavorableExcursionPct)}٪ • کمینه مسیر ${String.format(Locale.US,"%+.2f",p.medianAdverseExcursionPct)}٪ • نمونه ${p.samples}", color=muted, fontSize=8.sp)
                }
                Text("روش: ${f.method}. این فراوانی شبیه‌سازی است، نه احتمال کالیبره‌شده بازار و نه تضمین قیمت. برخورد به حمایت/مقاومت با مسیر close و دامنه تقریبی شبیه‌سازی می‌شود.",color=muted,fontSize=8.sp)
            }
            if (scorecard != null) Text(
                if (scorecard.evaluated >= 30)
                    "کارنامه پیش‌بینی‌ها: ${scorecard.correct}/${scorecard.evaluated} درست • " +
                        String.format(Locale.US,"%.1f",scorecard.accuracyPct)+"٪ دقت جهت • Brier " +
                        String.format(Locale.US,"%.3f",scorecard.brierScore)
                else "کارنامه اولیه: ${scorecard.evaluated} نتیجه ارزیابی‌شده؛ برای قضاوت آماری حداقل ۳۰ نمونه لازم است.",
                color=gold,fontSize=9.sp)
        }
    }
}


@Composable
internal fun HopeMacroMarketDashboard() {
    var context by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<MacroMarketContext?>(null) }
    var loading by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        loading = true
        context = runCatching { HopeMacroMarketEngine.fetch() }.getOrNull()
        loading = false
    }
    val gold = Color(0xFFFFD45A)
    val muted = Color(0xFFB8B0A0)
    Surface(color=Color(0xFF100E09), shape=RoundedCornerShape(17.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,gold.copy(alpha=0.35f)), modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("نبض کلان بازار کریپتو", color=Color(0xFFECE5D6), fontWeight=FontWeight.Bold, fontSize=13.sp, modifier=Modifier.weight(1f))
                Text(if(context?.fresh==true) "داده تازه" else if(loading) "در حال دریافت" else "داده در دسترس نیست",
                    color=if(context?.fresh==true) Color(0xFF38C987) else gold, fontSize=9.sp)
            }
            val m=context
            if(m==null) {
                Text("داده کلان دریافت نشد؛ در این وضعیت این بخش هیچ امتیازی به سیگنال اضافه نمی‌کند.", color=muted, fontSize=9.sp)
            } else {
                fun billions(v: Double)=String.format(Locale.US,"%.2fB",v/1_000_000_000.0)
                fun pct(v: Double)=String.format(Locale.US,"%+.2f%%",v)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column { Text("TOTAL",color=muted,fontSize=9.sp); Text(billions(m.totalUsd),color=Color.White,fontSize=11.sp,fontWeight=FontWeight.Bold) }
                    Column { Text("TOTAL2≈",color=muted,fontSize=9.sp); Text(billions(m.total2Usd),color=Color.White,fontSize=11.sp,fontWeight=FontWeight.Bold) }
                    Column { Text("TOTAL3≈",color=muted,fontSize=9.sp); Text(billions(m.total3Usd),color=Color.White,fontSize=11.sp,fontWeight=FontWeight.Bold) }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column { Text("BTC.D",color=muted,fontSize=9.sp); Text(pct(m.btcDominance),color=gold,fontSize=10.sp) }
                    Column { Text("ETH.D",color=muted,fontSize=9.sp); Text(pct(m.ethDominance),color=gold,fontSize=10.sp) }
                    Column { Text("USDT.D",color=muted,fontSize=9.sp); Text(pct(m.usdtDominance),color=gold,fontSize=10.sp) }
                }
                Text("تغییر ۲۴ساعته • TOTAL ${pct(m.total24hPct)} | TOTAL2 ${pct(m.total2_24hPct)} | TOTAL3 ${pct(m.total3_24hPct)}",
                    color=muted,fontSize=9.sp)
                Text("امتیاز گرایش کلان بازار: ${m.biasScore} از ۱۰ • منبع: ${m.sourceLabel}",
                    color=Color(0xFFECE5D6),fontSize=9.sp)
                Text("TOTAL2 و TOTAL3 در این نسخه برآورد محاسباتی‌اند، نه کندل رسمی شاخص.",color=muted,fontSize=8.sp)
            }
        }
    }
}
