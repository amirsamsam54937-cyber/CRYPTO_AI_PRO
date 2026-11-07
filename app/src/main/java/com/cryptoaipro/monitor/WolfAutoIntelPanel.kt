package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun WolfAutoIntelPanel(context: Context) {
    var reports by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var headlines by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var refreshed by remember { mutableLongStateOf(0L) }
    val gold = Color(0xFFE1B86B)
    val muted = Color(0xFFAEB7BC)
    val green = Color(0xFFB9D69A)
    val red = Color(0xFFE9877E)
    LaunchedEffect(Unit) {
        while (isActive) {
            val prefs = context.getSharedPreferences("wolf_background_intelligence", Context.MODE_PRIVATE)
            reports = runCatching {
                val a = JSONArray(prefs.getString("flow_reports", "[]"))
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            }.getOrDefault(emptyList())
            headlines = runCatching {
                val a = JSONArray(prefs.getString("market_news", "[]"))
                (0 until minOf(a.length(), 5)).mapNotNull { a.optJSONObject(it) }
            }.getOrDefault(emptyList())
            refreshed = prefs.getLong("last_refresh", 0L)
            delay(30_000L)
        }
    }
    Surface(
        color = Color(0xFF171B1E),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Color(0xFF9E8354).copy(alpha = 0.42f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("رادار خودکار اخبار و نهنگ‌ها", color = Color.White, fontSize = 15.sp)
                    Text("تشخیص و جمع‌آوری در پس‌زمینه؛ بدون نیاز به ورود دستی", color = muted, fontSize = 9.sp)
                }
                Surface(color = Color(0xFF26352A), shape = RoundedCornerShape(20.dp)) {
                    Text("AUTO", color = green, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Divider(color = Color(0xFF343A3D))
            Text("ردیابی معاملات درشت", color = gold, fontSize = 11.sp)
            if (reports.isEmpty()) {
                Text("هنوز گزارش تأییدشده‌ای ذخیره نشده است؛ موتور هوشمند پس از اسکن بازار داده‌های عمومی معاملات تجمیعی را بررسی می‌کند.", color = muted, fontSize = 10.sp)
            } else {
                reports.take(4).forEach { r ->
                    val imbalance = r.optDouble("imbalance", 0.0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.optString("symbol"), color = Color.White, modifier = Modifier.weight(1f), fontSize = 11.sp)
                        Text(
                            (if (imbalance > 0.12) "غلبه خرید " else if (imbalance < -0.12) "غلبه فروش " else "متعادل ") +
                                String.format(Locale.US, "%.1f%%", imbalance * 100.0),
                            color = if (imbalance > 0.12) green else if (imbalance < -0.12) red else muted,
                            fontSize = 10.sp
                        )
                    }
                    Text("معاملات درشت: خرید " + r.optInt("largeBuys") + " • فروش " + r.optInt("largeSells") + " | " + r.optString("summary"), color = muted, fontSize = 9.sp)
                }
            }
            HorizontalDivider(color = Color(0xFF343A3D))
            Text("رصد هوشمند اخبار بازار", color = gold, fontSize = 11.sp)
            if (headlines.isEmpty()) {
                Text("خبر تازه‌ای دریافت نشده یا منبع خبری در دسترس نیست. نبود خبر به معنی نبود رویداد نیست.", color = muted, fontSize = 10.sp)
            } else {
                headlines.take(4).forEach { n ->
                    Text("• " + n.optString("title"), color = Color.White, fontSize = 10.sp)
                    Text(n.optString("source") + " • " + n.optString("date"), color = muted, fontSize = 8.sp)
                }
            }
            Text(
                if (refreshed > 0L) "آخرین به‌روزرسانی: " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(refreshed)) + " • منابع عمومی Binance و RSS"
                else "وضعیت: در انتظار نخستین چرخه دریافت داده",
                color = muted, fontSize = 8.sp
            )
            Text("این بخش داده‌های عمومی را پایش می‌کند؛ معاملات درشت الزاماً متعلق به یک نهنگ مشخص نیستند.", color = muted, fontSize = 8.sp)
        }
    }
}
