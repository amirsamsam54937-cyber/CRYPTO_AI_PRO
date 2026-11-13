package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@Composable
internal fun XtLivePositionsPanel(context: Context = LocalContext.current) {
    var positions by remember { mutableStateOf(emptyList<JSONObject>()) }
    var syncedAt by remember { mutableStateOf(0L) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val prefs = context.getSharedPreferences("zoro_live_trade_state", Context.MODE_PRIVATE)
            val array = runCatching { JSONArray(prefs.getString("open_positions", "[]")) }
                .getOrDefault(JSONArray())
            positions = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            syncedAt = prefs.getLong("positions_synced_at", 0L)
            refresh = (refresh + 1) % 1_000_000
            delay(2_000L)
        }
    }
    val panel = Color(0xFF101211)
    val silver = Color(0xFFE1E5EA)
    val muted = Color(0xFFB8B0A0)
    val green = Color(0xFF25D978)
    val red = Color(0xFFFF5361)
    Surface(
        color = panel,
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("پوزیشن‌های واقعی XT", color = silver, fontSize = 13.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f))
                Text("${positions.size} باز", color = if (positions.isEmpty()) muted else green,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (positions.isEmpty()) {
                Text(
                    if (XtCredentialVault.hasCredentials(context)) "در آخرین همگام‌سازی، پوزیشن بازی در حساب فیوچرز XT دیده نشد."
                    else "برای نمایش پوزیشن‌های حساب، ابتدا اتصال امن XT را در تنظیمات ذخیره کن.",
                    color = muted, fontSize = 10.sp, lineHeight = 15.sp
                )
            } else {
                positions.forEach { p ->
                    val symbol = p.optString("symbol", "—").uppercase()
                    val side = p.optString("side", "—").uppercase()
                    val entry = p.optDouble("entryPrice")
                    val mark = p.optDouble("markPrice")
                    val qty = p.optDouble("quantity")
                    val leverage = p.optInt("leverage", 1)
                    val key = symbol.filter(Char::isLetterOrDigit).lowercase() + "_" + side
                    val protection = runCatching {
                        JSONObject(context.getSharedPreferences("zoro_live_trade_state", Context.MODE_PRIVATE)
                            .getString("position_protection", "{}")).optJSONObject(key)
                    }.getOrNull()
                    Surface(color = Color(0xFF191D1B), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(symbol, color = silver, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                                Text(if (side == "LONG") "LONG" else "SHORT",
                                    color = if (side == "LONG") green else red, fontWeight = FontWeight.Black, fontSize = 11.sp)
                            }
                            Text(
                                "ورود: ${String.format(Locale.US, "%.8f", entry)} • مارک: ${String.format(Locale.US, "%.8f", mark)}",
                                color = muted, fontSize = 9.sp
                            )
                            Text(
                                "حجم: ${String.format(Locale.US, "%.4f", qty)} • اهرم: ${leverage}x",
                                color = muted, fontSize = 9.sp
                            )
                            if (protection != null) {
                                Text(
                                    "SL: ${String.format(Locale.US, "%.8f", protection.optDouble("stop"))} • TP: ${String.format(Locale.US, "%.8f", protection.optDouble("takeProfit"))}",
                                    color = silver, fontSize = 9.sp
                                )
                            } else {
                                Text("حدود حفاظتی محلی ثبت‌شده نیست؛ بررسی حفاظتی صرافی لازم است.", color = red, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
            Text(
                if (syncedAt > 0L) "آخرین همگام‌سازی حساب: " + java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(syncedAt))
                else "در انتظار اولین همگام‌سازی سرویس پس‌زمینه",
                color = muted, fontSize = 8.sp
            )
        }
    }
}
