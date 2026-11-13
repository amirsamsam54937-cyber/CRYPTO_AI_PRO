package com.cryptoaipro.monitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local, privacy-safe diagnostic journal. It never changes signal decisions or uploads data. */
internal object ZoroDiagnostics {
    private const val PREFS = "zoro_diagnostics_v1"
    private const val KEY_EVENTS = "events"
    @Synchronized fun record(context: Context, module: String, level: String, message: String) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = runCatching { JSONArray(prefs.getString(KEY_EVENTS, "[]")) }.getOrDefault(JSONArray())
            val safe = message
                .replace(Regex("(?i)(api[_ -]?key|secret|password|authorization|token)\\s*[:=]\\s*[^,;\\s]+"), "$1=[REDACTED]")
                .replace(Regex("https?://[^\\s]+"), "[URL]")
                .take(700)
            val item = JSONObject().put("at", System.currentTimeMillis())
                .put("module", module.take(60)).put("level", level.take(16)).put("message", safe)
            val next = JSONArray()
            val start = (old.length() - 79).coerceAtLeast(0)
            for (i in start until old.length()) next.put(old.getJSONObject(i))
            next.put(item)
            prefs.edit().putString(KEY_EVENTS, next.toString()).putLong("last_event_at", System.currentTimeMillis()).apply()
        }
    }
    fun events(context: Context): List<JSONObject> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.reversed()
    }.getOrDefault(emptyList())
    fun report(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")
        val model = WolfPretrainedEnsemble.status()
        val all = events(context)
        val root = JSONObject()
            .put("app", "ZORO").put("version", version)
            .put("generated_at", System.currentTimeMillis())
            .put("neural_loaded", model.loaded).put("neural_members", model.members)
            .put("neural_validated_for_scoring", model.validatedForScoring)
            .put("neural_test_accuracy", model.testAccuracy)
            .put("neural_baseline_accuracy", model.baselineAccuracy)
            .put("neural_balanced_accuracy", model.balancedAccuracy)
            .put("neural_corpus_rows", model.corpusRows)
            .put("events", JSONArray(all))
        return root.toString(2)
    }
}

@Composable
internal fun ZoroDiagnosticsScreen(context: Context = LocalContext.current) {
    var refresh by remember { mutableIntStateOf(0) }
    val entries = remember(refresh) { ZoroDiagnostics.events(context) }
    val model = remember(refresh) { WolfPretrainedEnsemble.status() }
    val bg = Color(0xFF080808)
    val panel = Color(0xFF151515)
    val silver = Color(0xFFD6D6D6)
    val red = Color(0xFFFF4655)
    Column(Modifier.fillMaxSize().background(bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ZORO CONTROL CENTER", color = silver, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text("مرکز سلامت، خطاها و علت منتشر نشدن سیگنال", color = Color.LightGray, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiagnosticTile("مدل عصبی", if (model.loaded) "بارگذاری شد" else "بارگذاری ناموفق", model.loaded, Modifier.weight(1f))
            DiagnosticTile("اعتبار تصمیم", if (model.validatedForScoring) "تأییدشده" else "فقط پژوهشی", model.validatedForScoring, Modifier.weight(1f))
        }
        Surface(color = panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("وضعیت اعتبارسنجی شبکه", color = silver, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("اعضا: ${model.members}/10 • داده آموزشی ثبت‌شده: ${model.corpusRows}", color = Color.White, fontSize = 10.sp)
                Text("دقت آزمون: ${String.format(Locale.US, "%.3f", model.testAccuracy)} • خط مبنا: ${String.format(Locale.US, "%.3f", model.baselineAccuracy)}", color = Color.LightGray, fontSize = 10.sp)
                Text("Balanced accuracy: ${String.format(Locale.US, "%.3f", model.balancedAccuracy)}", color = Color.LightGray, fontSize = 10.sp)
                Text(if (model.validatedForScoring) "مدل اجازه اثرگذاری بر سیگنال را دارد." else "مدل هنوز اجازه اثرگذاری بر تصمیم نهایی ندارد؛ رأی آن فقط نمایشی است.", color = if (model.validatedForScoring) Color(0xFF35D08A) else red, fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val text = ZoroDiagnostics.report(context)
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "ZORO diagnostic report").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(intent, "ارسال گزارش عیب‌یابی"))
            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E1826))) {
                Text("ارسال گزارش برای بررسی", color = Color.White, fontSize = 10.sp)
            }
            OutlinedButton(onClick = { refresh++ }, modifier = Modifier.weight(1f)) { Text("به‌روزرسانی", color = silver, fontSize = 10.sp) }
        }
        Text("رویدادهای اخیر • ${entries.size}", color = silver, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (entries.isEmpty()) {
            Text("هنوز رویداد تشخیصی ثبت نشده است. یک اسکن بازار اجرا کن.", color = Color.LightGray, fontSize = 11.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(entries) { item ->
                    val level = item.optString("level")
                    Surface(color = panel, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.optString("module"), color = silver, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text(level, color = if (level == "ERROR") red else Color(0xFFB9B9B9), fontSize = 9.sp)
                            }
                            Text(item.optString("message"), color = Color.White, fontSize = 10.sp)
                            Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.optLong("at"))), color = Color.Gray, fontSize = 8.sp)
                        }
                    }
                }
            }
        }
        Text("گزارش فقط با فشردن دکمه اشتراک‌گذاری از دستگاه خارج می‌شود؛ هیچ کلید API یا رمز عبوری در گزارش قرار نمی‌گیرد.", color = Color.Gray, fontSize = 9.sp)
    }
}

@Composable
private fun DiagnosticTile(title: String, value: String, good: Boolean, modifier: Modifier) {
    Surface(color = Color(0xFF151515), shape = RoundedCornerShape(11.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color.LightGray, fontSize = 9.sp)
            Text(value, color = if (good) Color(0xFF35D08A) else Color(0xFFFF5967), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
