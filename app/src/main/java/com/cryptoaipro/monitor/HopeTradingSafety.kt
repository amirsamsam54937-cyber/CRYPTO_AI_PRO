package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Safety-first foundation for HOPE HUNTER.
 * This release deliberately contains no live-order adapter. Paper mode is simulation only.
 */
internal enum class HopeTradingMode { PAPER, LIVE_DISABLED }

internal data class HopeRiskConfig(
    val riskPerTradePct: Double = 0.5,
    val maxDailyLossPct: Double = 2.0,
    val maxPortfolioOpenRiskPct: Double = 3.0,
    val maxLeverage: Int = 3,
    val maxConcurrentPositions: Int = 3,
    val maxSlippagePct: Double = 0.15,
    val isolatedMarginOnly: Boolean = true
)

internal data class HopePositionSize(
    val quantity: Double,
    val riskBudgetUsdt: Double,
    val estimatedLossAtStopUsdt: Double,
    val notionalUsdt: Double,
    val marginRequiredUsdt: Double
)

internal object HopeRiskManager {
    fun calculateLinearUsdtSize(
        equityUsdt: Double,
        entry: Double,
        stop: Double,
        leverage: Int,
        config: HopeRiskConfig = HopeRiskConfig(),
        estimatedRoundTripCostPct: Double = 0.12
    ): HopePositionSize? {
        if (!equityUsdt.isFinite() || equityUsdt <= 0.0 ||
            !entry.isFinite() || entry <= 0.0 ||
            !stop.isFinite() || stop <= 0.0 || stop == entry ||
            leverage !in 1..config.maxLeverage ||
            estimatedRoundTripCostPct !in 0.0..2.0) return null

        val stopDistancePct = abs(entry - stop) / entry
        if (stopDistancePct <= 0.0005 || stopDistancePct >= 0.25) return null

        val riskBudget = equityUsdt * config.riskPerTradePct / 100.0
        // Fees, funding and slippage are conservatively reserved as a fraction of notional.
        val totalLossFraction = stopDistancePct + estimatedRoundTripCostPct / 100.0
        if (totalLossFraction <= 0.0) return null
        val notional = min(riskBudget / totalLossFraction, equityUsdt * leverage.toDouble())
        if (!notional.isFinite() || notional <= 0.0) return null
        val quantity = notional / entry
        val stopLoss = notional * totalLossFraction
        return HopePositionSize(
            quantity = quantity,
            riskBudgetUsdt = riskBudget,
            estimatedLossAtStopUsdt = stopLoss,
            notionalUsdt = notional,
            marginRequiredUsdt = notional / leverage.toDouble()
        )
    }

    fun mayOpenNewPosition(
        dailyLossPct: Double,
        currentOpenRiskPct: Double,
        openPositions: Int,
        proposedLeverage: Int,
        config: HopeRiskConfig = HopeRiskConfig()
    ): Boolean = dailyLossPct.isFinite() && currentOpenRiskPct.isFinite() &&
        dailyLossPct < config.maxDailyLossPct &&
        currentOpenRiskPct < config.maxPortfolioOpenRiskPct &&
        openPositions < config.maxConcurrentPositions &&
        proposedLeverage in 1..config.maxLeverage
}

@Composable
internal fun PaperTradingSafetyPanel(context: Context, prefs: android.content.SharedPreferences) {
    var acknowledged by remember {
        mutableStateOf(prefs.getBoolean("hope_risk_ack_v1", false))
    }
    var paperEnabled by remember {
        mutableStateOf(prefs.getBoolean("hope_paper_mode_enabled", false))
    }
    val gold = Color(0xFFFFD45A)
    val ivory = Color(0xFFECE5D6)
    val muted = Color(0xFFB8B0A0)
    val panel = Color(0xFF111111)

    Surface(
        color = Color(0xFF17130A),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, gold.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("مرکز کنترل معامله‌گری • ایمنی", color = ivory, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("حالت فعلی", color = muted, modifier = Modifier.weight(1f), fontSize = 11.sp)
                Text(
                    if (paperEnabled) "شبیه‌سازی آزمایشی فعال" else "فقط تحلیل بازار",
                    color = if (paperEnabled) Color(0xFF20D477) else gold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
            Text(
                "پس از تأیید شما، شبیه‌ساز با ۱۰۰۰ تتر مجازی و داده زنده بازار XT آغاز می‌شود. هیچ سفارش واقعی به صرافی ارسال نمی‌شود و سرمایه واقعی درگیر نیست.",
                color = ivory, fontSize = 10.sp, lineHeight = 16.sp
            )
            Divider(color = gold.copy(alpha = 0.22f))
            Text("پیش‌فرض‌های محافظه‌کارانه شبیه‌ساز", color = gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(
                "ریسک پویا: ۰٫۱۵ تا ۰٫۵٪ سرمایه بر اساس کیفیت رأی‌ها • زیان روزانه: ۲٪ • ریسک باز کل: ۲٫۵٪ • اهرم تطبیقی: ۱ تا ۳× • حداکثر موقعیت هم‌زمان: ۳",
                color = muted, fontSize = 10.sp, lineHeight = 16.sp
            )
            Text(
                "حجم فرضی از فاصله ورود تا حد ضرر و هزینه تخمینی محاسبه می‌شود. ورود خودکار به مدل عصبی اعتبارسنجی‌شده، توافق جهت شبکه و تحلیل تکنیکال، کیفیت ریسک‌به‌بازده و رژیم بازار وابسته است. با افزایش نوسان، اهرم کاهش می‌یابد؛ کارمزد، فاندینگ و لغزش قیمت نیز تخمین زده می‌شوند.",
                color = muted, fontSize = 10.sp, lineHeight = 16.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = {
                        acknowledged = it
                        prefs.edit().putBoolean("hope_risk_ack_v1", it).apply()
                        if (!it) {
                            paperEnabled = false
                            prefs.edit().putBoolean("hope_paper_mode_enabled", false).apply()
                        }
                    }
                )
                Text(
                    "می‌دانم این فقط شبیه‌سازی با پول مجازی است؛ خطای مدل، داده ناقص و هزینه‌های تخمینی می‌توانند نتیجه را غیرواقعی کنند و سود آزمایشی تضمین‌کننده سود آینده نیست.",
                    color = ivory, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
            Button(
                onClick = {
                    paperEnabled = true
                    prefs.edit()
                        .putBoolean("hope_risk_ack_v1", true)
                        .putBoolean("hope_paper_mode_enabled", true)
                        .putString("hope_trading_mode", HopeTradingMode.PAPER.name)
                        .putLong("hope_risk_ack_timestamp", System.currentTimeMillis())
                        .apply()
                    acknowledged = true
                },
                enabled = acknowledged && !paperEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (paperEnabled) "شبیه‌ساز آزمایشی فعال است" else "تأیید و فعال‌سازی شبیه‌ساز")
            }
            if (paperEnabled) {
                Text(
                    "شبیه‌سازی فعال است؛ این وضعیت به معنی معامله واقعی نیست.",
                    color = Color(0xFF20D477), fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
            }
            Text(
                "معامله واقعی در این نسخه عمداً قفل است: اتصال امن API، بررسی مستندات و سفارش‌های حفاظتی XT، آزمون خطا و بازیابی، و اعتبارسنجی زمانی مثبت هنوز پیش‌نیازند. تیک این بخش قرارداد حقوقی یا تضمین ایمنی نیست.",
                color = gold, fontSize = 10.sp, lineHeight = 16.sp
            )
            Text(
                "سازنده: amir arsalan samsami",
                color = muted, fontSize = 9.sp
            )
        }
    }
}
