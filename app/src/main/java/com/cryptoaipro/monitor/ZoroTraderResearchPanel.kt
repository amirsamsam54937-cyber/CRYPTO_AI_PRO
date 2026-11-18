package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Public-trader research workbench. It discovers public Instagram pages through the
 * existing keyless research provider; it does not scrape private accounts or treat
 * social posts as proof of executed PnL.
 */
internal object ZoroTraderResearchRegistry {
    data class Trader(val id: String, val displayName: String, val handle: String, val focus: String, val query: String)

    val traders = listOf(
        Trader("TRH", "Khosro / Trading Room Hunter", "tradingroomhunter", "لایوترید طلا، ساختار بازار و اجرای معامله",
            "site:instagram.com/tradingroomhunter خسرو اتاق شکار تحلیل طلا"),
        Trader("BENY", "Beny Trade", "beny.trade", "تحلیل BTC و آلت‌کوین، شکست و تأیید",
            "site:instagram.com/beny.trade Beny Trade crypto analysis"),
        Trader("DAAN", "Daan Crypto Trades", "DaanCrypto", "سطوح BTC، محدوده‌ها و نقدینگی",
            "site:instagram.com Daan Crypto Trades DaanCrypto"),
        Trader("DONALT", "DonAlt", "DonAlt", "تحلیل چرخه و ساختار بازار کریپتو",
            "site:instagram.com DonAlt crypto trader"),
        Trader("CHASE", "Crypto Chase", "CryptoChase", "معاملات کریپتو و تحلیل تکنیکال",
            "site:instagram.com Crypto Chase crypto trader"),
        Trader("XO", "Trader XO", "TraderXO", "پرایس‌اکشن و مدیریت معامله",
            "site:instagram.com Trader XO trading"),
        Trader("SATOSHI", "Satoshi Flipper", "SatoshiFlipper", "فیوچرز کریپتو",
            "site:instagram.com Satoshi Flipper futures trader"),
        Trader("DENIS", "Denis_D / SaturnX-Live", "Denis_D", "سیستم‌های روندی خودکار؛ سابقه Myfxbook",
            "site:instagram.com Denis_D SaturnX Live trader")
    )
}

@Composable
internal fun ZoroTraderResearchPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var activeTrader by remember { mutableStateOf<String?>(null) }
    var sources by remember { mutableStateOf<List<HopeResearchSource>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("پروفایل عمومی را جست‌وجو کن؛ نتیجه فقط سرنخ تحقیق است، نه اثبات سود واقعی.") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF15130F))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("PROJECT MIRROR • آزمایشگاه معامله‌گرها", color = Color(0xFFFFD45A),
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("فهرست از پرونده‌های پژوهشی GitHub استخراج شده؛ جست‌وجو فقط صفحات عمومی و ایندکس‌شده را بررسی می‌کند.",
                color = Color(0xFFB8B0A0), fontSize = 11.sp)
            ZoroTraderResearchRegistry.traders.forEach { trader ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(trader.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("@${trader.handle} • ${trader.focus}", color = Color(0xFFB8B0A0), fontSize = 10.sp)
                    }
                    Button(
                        enabled = !searching,
                        onClick = {
                            activeTrader = trader.id
                            searching = true
                            sources = emptyList()
                            status = "در حال جست‌وجوی منابع عمومی برای ${trader.displayName}…"
                            scope.launch {
                                val found = runCatching { HopeResearchEngine.search(context, trader.query) }
                                sources = found.getOrDefault(emptyList())
                                searching = false
                                status = if (sources.isEmpty())
                                    "نتیجه عمومی قابل اتکا پیدا نشد؛ نبود نتیجه به معنی نبود حساب نیست."
                                else "${sources.size} منبع عمومی پیدا شد؛ هویت حساب و ادعاها باید جداگانه بررسی شوند."
                            }
                        },
                        shape = RoundedCornerShape(9.dp)
                    ) { Text("جست‌وجو", fontSize = 10.sp) }
                }
            }
            if (searching) CircularProgressIndicator(color = Color(0xFFFFD45A))
            Text(status, color = Color(0xFFB8B0A0), fontSize = 10.sp)
            sources.forEach { source ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { runCatching { uriHandler.openUri(source.url) } }
                        .padding(vertical = 4.dp)
                ) {
                    Text(source.title, color = Color(0xFFFFD45A), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(source.snippet.take(280), color = Color.White, fontSize = 10.sp)
                    Text(source.url, color = Color(0xFF9A9A9A), fontSize = 9.sp)
                }
            }
            Text("۱۲ فرضیه استراتژی ثبت‌شده در کتابخانه ZORO", color = Color(0xFFFFD45A),
                fontWeight = FontWeight.Bold, fontSize = 13.sp)
            ZoroStrategyLibrary.patterns.forEachIndexed { index, pattern ->
                Text("${index + 1}. ${pattern.name}  •  ${pattern.family.name}",
                    color = Color.White, fontSize = 10.sp)
                Text("شواهد لازم: ${pattern.requiredEvidence.take(2).joinToString("؛ ")}",
                    color = Color(0xFFB8B0A0), fontSize = 9.sp)
            }
            Text(
                "قانون پذیرش: ثبت زمان پست، ثبت همه برد و باخت‌ها، تطبیق قیمت پس از انتشار، تفکیک حساب واقعی از دمو/پراپ، و آزمون مستقل با کارمزد و اسلیپیج. هیچ پست اینستاگرامی مستقیماً سیگنال یا سفارش تولید نمی‌کند.",
                color = Color(0xFFB8B0A0), fontSize = 10.sp
            )
        }
    }
}
