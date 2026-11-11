package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import java.util.Locale
import java.util.Random
import kotlin.math.*

internal data class HopePriceScenario(
    val label: String, val probabilityPct: Double, val medianMovePct: Double,
    val lowerMovePct: Double, val upperMovePct: Double, val samples: Int
)
internal data class HopeScenarioForecast(
    val symbol: String, val basePrice: Double, val candleTimeMs: Long,
    val horizonBars: Int, val scenarios: List<HopePriceScenario>, val method: String
) { val dominant: HopePriceScenario? get() = scenarios.maxByOrNull { it.probabilityPct } }
internal data class HopeScenarioScorecard(
    val forecast: HopeScenarioForecast?, val evaluated: Int, val correct: Int, val accuracyPct: Double
)

/** Empirical block-bootstrap paths. Frequencies are not calibrated market odds. */
internal object HopeScenarioLab {
    private const val PREFS = "hope_scenario_lab"
    private const val KEY = "forecast_journal_v1"
    private const val HORIZON = 8
    private const val PATHS = 1600
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
        val drift = (mean * 0.25).coerceIn(-sigma*0.18, sigma*0.18)
        val base = c.last()[3]
        val threshold = max(0.30, (signal.technical.atrPercent.takeIf { it.isFinite() && it > 0.0 } ?: sigma*150.0) * sqrt(HORIZON.toDouble()) * 0.38)
        val rng = Random((signal.symbol.hashCode().toLong() shl 32) xor c.last()[5].toLong())
        val moves = ArrayList<Double>(PATHS)
        repeat(PATHS) {
            var sum = 0.0
            var step = 0
            while (step < HORIZON) {
                val start = rng.nextInt(recent.size)
                for (j in 0 until BLOCK) {
                    if (step >= HORIZON) break
                    sum += drift + (recent[(start+j)%recent.size]-mean)*0.90
                    step++
                }
            }
            moves += (exp(sum)-1.0)*100.0
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
            HopePriceScenario(name, values.size.toDouble()/moves.size*100.0,
                q(values,0.50), q(values,0.10), q(values,0.90), values.size)
        }
        return HopeScenarioForecast(signal.symbol, base, c.last()[5].toLong(), HORIZON,
            scenarios, "۱۶۰۰ مسیر مونت‌کارلو با بازنمونه‌گیری بلوکی بازده‌های تاریخی")
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
            val bar = s.candles.lastOrNull { it.size >= 6 && it[5].toLong() >= origin + HORIZON*BAR_MS } ?: return@forEach
            val base = item.optDouble("basePrice", 0.0)
            val actual = if (base > 0.0) (bar[3]/base-1.0)*100.0 else Double.NaN
            if (!actual.isFinite()) { item.put("invalid", true); return@forEach }
            val threshold = item.optDouble("thresholdPct", 0.30)
            val realized = when { actual >= threshold -> "صعودی"; actual <= -threshold -> "نزولی"; else -> "خنثی" }
            item.put("resolved", true).put("actual", realized).put("actualMovePct", actual)
                .put("correct", item.optString("dominant") == realized)
        }
        var latest: HopeScenarioForecast? = null
        signals.forEach { s ->
            val f = forecast(s) ?: return@forEach
            if (items.any { it.optString("symbol") == f.symbol && it.optLong("candleTimeMs") == f.candleTimeMs }) return@forEach
            val dominant = f.dominant ?: return@forEach
            val threshold = max(0.30, (s.technical.atrPercent.takeIf { it.isFinite() && it > 0.0 } ?: 0.8)*sqrt(HORIZON.toDouble())*0.38)
            items += JSONObject().put("symbol",f.symbol).put("basePrice",f.basePrice)
                .put("candleTimeMs",f.candleTimeMs).put("dominant",dominant.label)
                .put("thresholdPct",threshold).put("resolved",false).put("createdAt",System.currentTimeMillis())
            latest = f
        }
        while (items.size > 240) items.removeAt(0)
        prefs.edit().putString(KEY, JSONObject().put("items",JSONArray().apply { items.forEach { put(it) } }).toString()).apply()
        val done = items.filter { it.optBoolean("resolved") }
        val wins = done.count { it.optBoolean("correct") }
        return HopeScenarioScorecard(latest ?: current.values.firstNotNullOfOrNull { forecast(it) },
            done.size, wins, if (done.isEmpty()) 0.0 else wins.toDouble()/done.size*100.0)
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
                Text("HOPE • SIMULATION", color=gold, fontSize=8.sp, fontWeight=FontWeight.Bold)
            }
            if (f == null) Text("برای شبیه‌سازی حداقل ۸۰ کندل معتبر لازم است.", color=muted, fontSize=10.sp)
            else {
                Text("${f.symbol} • قیمت مبنا ${String.format(Locale.US,"%.6f",f.basePrice)} • افق ${f.horizonBars} کندل ۱۵دقیقه‌ای", color=muted, fontSize=9.sp)
                f.scenarios.forEach { s ->
                    val tint = when(s.label) { "صعودی" -> Color(0xFF38C987); "نزولی" -> Color(0xFFFF6670); else -> Color(0xFFFFC65A) }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(s.label,color=tint,fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                        Text("${String.format(Locale.US,"%.1f",s.probabilityPct)}٪",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold)
                    }
                    Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFF302A1D),RoundedCornerShape(8.dp))) {
                        Box(Modifier.fillMaxWidth((s.probabilityPct/100.0).toFloat().coerceIn(0f,1f)).height(7.dp).background(tint,RoundedCornerShape(8.dp)))
                    }
                    Text("حرکت میانه ${String.format(Locale.US,"%+.2f",s.medianMovePct)}٪ • بازه ۱۰ تا ۹۰٪: ${String.format(Locale.US,"%+.2f",s.lowerMovePct)} تا ${String.format(Locale.US,"%+.2f",s.upperMovePct)}٪",
                        color=muted,fontSize=9.sp)
                }
                Text("سناریوی غالب شبیه‌سازی: ${f.dominant?.label ?: "نامشخص"} • فراوانی شرطی ${String.format(Locale.US,"%.1f",f.dominant?.probabilityPct ?: 0.0)}٪",
                    color=gold,fontSize=10.sp,fontWeight=FontWeight.Bold)
                Text("روش: ${f.method}. این فراوانی شبیه‌سازی است، نه احتمال کالیبره‌شده بازار و نه تضمین قیمت.",color=muted,fontSize=8.sp)
            }
            if (scorecard != null) Text("کارنامه پیش‌بینی‌های ارزیابی‌شده: ${scorecard.correct}/${scorecard.evaluated} درست • " +
                (if(scorecard.evaluated>0) String.format(Locale.US,"%.1f",scorecard.accuracyPct)+"٪" else "هنوز نمونه کافی نیست"),
                color=gold,fontSize=9.sp)
        }
    }
}
