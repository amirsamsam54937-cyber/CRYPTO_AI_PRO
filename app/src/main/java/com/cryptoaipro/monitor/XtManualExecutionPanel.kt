package com.cryptoaipro.monitor

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun XtManualExecutionPanel(context: Context, signals: List<MarketSignal>, onMonitoringStarted: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(XtLiveTradingEngine.isEnabled(context)) }
    var autoEnabled by remember { mutableStateOf(XtLiveTradingEngine.isAutoEnabled(context)) }
    var modelValidated by remember { mutableStateOf(WolfPretrainedEnsemble.status().validatedForScoring) }
    var emergency by remember { mutableStateOf(XtLiveTradingEngine.isEmergencyStopped(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(XtLiveTradingEngine.lastStatus(context)) }
    var selectedSignal by remember { mutableStateOf<MarketSignal?>(null) }
    var preview by remember { mutableStateOf<XtLiveTradingEngine.ManualPreview?>(null) }
    val gold = Color(0xFF24E6D3)
    val ivory = Color(0xFFEAF7FF)
    val muted = Color(0xFF91A9BD)

    LaunchedEffect(context) {
        if (XtLiveTradingEngine.isEnabled(context) || XtLiveTradingEngine.hasManagedSymbols(context)) {
            val serviceIntent = Intent(context, HopeHunterMonitorService::class.java).apply {
                action = HopeHunterMonitorService.ACTION_START
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            onMonitoringStarted()
        }
        while (true) {
            val currentEnabled = XtLiveTradingEngine.isEnabled(context)
            val currentEmergency = XtLiveTradingEngine.isEmergencyStopped(context)
            val currentAuto = XtLiveTradingEngine.isAutoEnabled(context)
            val currentModelValidated = WolfPretrainedEnsemble.status().validatedForScoring
            if (currentEnabled != enabled || currentEmergency != emergency ||
                currentAuto != autoEnabled || currentModelValidated != modelValidated) {
                enabled = currentEnabled
                emergency = currentEmergency
                autoEnabled = currentAuto
                modelValidated = currentModelValidated
                status = XtLiveTradingEngine.lastStatus(context)
            }
            delay(1500L)
        }
    }

    Surface(
        color = Color(0xFF07111F),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, gold.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("معامله واقعی XT • دستی و خودکار", color = ivory, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Text(
                        when {
                            !enabled -> "خاموش • هیچ سفارش واقعی ارسال نمی‌شود"
                            autoEnabled -> "LIVE + خودکار مسلح است • اجرای سفارش طبق شروط ریسک"
                            else -> "LIVE مسلح است • سفارش‌ها دستی و با تأیید جداگانه"
                        },
                        color = if (enabled) gold else muted,
                        fontSize = 10.sp
                    )
                }
                Switch(
                    checked = enabled,
                    enabled = XtCredentialVault.hasCredentials(context) &&
                        XtCredentialVault.isConnectionVerified(context) && !emergency && !busy,
                    onCheckedChange = {
                        XtLiveTradingEngine.setEnabled(context, it)
                        enabled = it
                        if (!it) autoEnabled = false
                        if (it) {
                            val serviceIntent = Intent(context, HopeHunterMonitorService::class.java).apply {
                                action = HopeHunterMonitorService.ACTION_START
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                            onMonitoringStarted()
                        }
                        status = XtLiveTradingEngine.lastStatus(context)
                    }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("معامله‌گر خودکار هوشمند", color = ivory, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            autoEnabled -> "فعال • یک فرصت برتر در هر اسکن، با کنترل کامل ریسک"
                            !modelValidated -> "مدل عصبی مشورتی است؛ اجرای خودکار فقط با اجماع تکنیکال و کنترل ریسک مستقل انجام می‌شود"
                            !XtCredentialVault.isConnectionVerified(context) -> "ابتدا اتصال و مجوزهای Futures را با موفقیت آزمایش کن"
                            !enabled -> "ابتدا حالت LIVE را مسلح کن"
                            else -> "خاموش • فقط با روشن‌کردن این کلید سفارش خودکار مجاز می‌شود"
                        },
                        color = if (autoEnabled) gold else muted,
                        fontSize = 9.sp
                    )
                }
                Switch(
                    checked = autoEnabled,
                    enabled = enabled && !emergency && !busy && preview == null &&
                        XtCredentialVault.isConnectionVerified(context) &&
                        XtLiveTradingEngine.canArmAutomatic(context),
                    onCheckedChange = {
                        XtLiveTradingEngine.setAutoEnabled(context, it)
                        autoEnabled = XtLiveTradingEngine.isAutoEnabled(context)
                        status = XtLiveTradingEngine.lastStatus(context)
                    }
                )
            }
            Text(
                "سقف‌های حفاظتی: ریسک هر معامله ۰٫۲۵٪ Equity، مارجین حداکثر ۵٪، اهرم حداکثر ۳ برابر، حداکثر ۳ ورود روزانه، یک پوزیشن در کل حساب، توقف روزانه در زیان ۲٪ و توقف افت سرمایه ۸٪. اسکن فقط یک سیگنال برتر را بررسی می‌کند؛ تکرار یک کندل دوباره سفارش نمی‌فرستد.",
                color = muted, fontSize = 10.sp, lineHeight = 15.sp
            )
            if (emergency) {
                Text("توقف اضطراری فعال است. تا بررسی پوزیشن‌ها و طرح‌های حفاظتی، سفارش جدید ممنوع است.", color = Color(0xFFFF7777), fontSize = 10.sp)
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val reset = runCatching { XtLiveTradingEngine.resetEmergencyStopIfFlat(context) }.getOrDefault(false)
                            emergency = XtLiveTradingEngine.isEmergencyStopped(context)
                            enabled = XtLiveTradingEngine.isEnabled(context)
                            status = XtLiveTradingEngine.lastStatus(context)
                            if (!reset && !emergency) status = "بازنشانی انجام نشد؛ وضعیت حساب را بررسی کن."
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("بررسی حساب و بازنشانی توقف اضطراری") }
            }
            Text(status, color = gold, fontSize = 10.sp, lineHeight = 15.sp)
            Text("پوزیشن‌های ثبت‌شده توسط NEXUS: " + XtLiveTradingEngine.positionCount(context), color = ivory, fontSize = 10.sp)

            val candidates = signals.asSequence()
                .filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
                .filter { it.signalTier != "بدون رتبه" }
                .sortedWith(compareByDescending<MarketSignal> { it.confidence }.thenByDescending { it.score })
                .take(6)
                .toList()

            Text("سیگنال‌های قابل بررسی", color = ivory, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (candidates.isEmpty()) {
                Text("در اسکن فعلی سیگنال رتبه‌دار LONG یا SHORT وجود ندارد.", color = muted, fontSize = 10.sp)
            }
            candidates.forEach { signal ->
                Surface(
                    color = Color(0xFF102033),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(signal.symbol, color = ivory, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                if (signal.direction == "LONG_BIAS") "LONG" else "SHORT",
                                color = if (signal.direction == "LONG_BIAS") Color(0xFFB5F36B) else Color(0xFFFF8585),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                            Text("  ${signal.confidence}٪", color = gold, fontSize = 10.sp)
                        }
                        Text(
                            "ورود ${fmt(signal.entry)}  •  SL ${fmt(signal.stop)}  •  TP1 ${fmt(signal.tp1)}",
                            color = muted, fontSize = 9.sp
                        )
                        Button(
                            enabled = enabled && !autoEnabled && !emergency && !busy,
                            onClick = {
                                selectedSignal = signal
                                busy = true
                                status = "در حال بررسی حساب XT، موجودی آزاد، قرارداد، کارمزد و حجم مجاز..."
                                scope.launch {
                                    try {
                                        preview = XtLiveTradingEngine.prepareManual(context, signal)
                                        status = "پیش‌نمایش آماده است؛ هنوز هیچ سفارشی ارسال نشده."
                                    } catch (error: Exception) {
                                        preview = null
                                        status = "ورود مسدود شد: ${error.message ?: error.javaClass.simpleName}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "در حال بررسی..." else "محاسبه حجم و بررسی سفارش") }
                    }
                }
            }
        }
    }

    if (preview != null && selectedSignal != null) {
        val p = preview!!
        AlertDialog(
            onDismissRequest = { if (!busy) { preview = null; selectedSignal = null } },
            title = { Text("تأیید نهایی سفارش واقعی XT", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${p.symbol} • ${p.side} • ${p.contracts} قرارداد", fontWeight = FontWeight.Bold)
                    Text("قیمت مرجع: ${fmt(p.referencePrice)}")
                    Text("حدضرر: ${fmt(p.stop)}  •  هدف: ${fmt(p.target)}")
                    Text("اهرم: ${p.leverage}x  •  مارجین تخمینی: ${money(p.marginRequiredUsdt)} USDT")
                    Text("بودجه ریسک: ${money(p.riskBudgetUsdt)} USDT")
                    Text("کارمزد واقعی XT (Taker): ${String.format(Locale.US, "%.4f", p.takerFeeRate * 100.0)}٪ برای هر سمت")
                    Text("کارمزد ورود: ${money(p.estimatedEntryFeeUsdt)} • کارمزد خروج در SL: ${money(p.estimatedStopExitFeeUsdt)} USDT")
                    Text("لغزش پویا: ${String.format(Locale.US, "%.3f", p.slippageRatePerSide * 100.0)}٪ هر سمت • هزینه تخمینی: ${money(p.estimatedSlippageCostUsdt)} USDT")
                    Text("نرخ Funding زنده XT: ${String.format(Locale.US, "%.5f", p.actualFundingRatePer8h * 100.0)}٪ در هر دوره")
                    Text("نرخ Funding زنده XT: ${String.format(Locale.US, "%.5f", p.actualFundingRatePer8h * 100.0)}٪ در هر دوره")
                    Text("ذخیره فاندینگ تخمینی: ${money(p.estimatedFundingCostUsdt)} USDT")
                    Text("زیان تخمینی در حدضرر با همه هزینه‌ها: ${money(p.estimatedStopLossUsdt)} USDT")
                    Text("سود خالص تخمینی در هدف: ${money(p.estimatedNetTargetProfitUsdt)} USDT")
                    Text("نسبت سود به زیان خالص: ${String.format(Locale.US, "%.2f", p.netRewardRisk)}")
                    Text(
                        "این سفارش به حساب واقعی XT ارسال می‌شود. قیمت اجرا و لغزش می‌تواند با پیش‌نمایش فرق کند. فقط در صورت تأیید دوباره، دکمه ارسال را بزن.",
                        color = Color(0xFFB45309), fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val signal = selectedSignal ?: return@TextButton
                        busy = true
                        scope.launch {
                            val result = XtLiveTradingEngine.executeManual(context, signal, p)
                            status = result.message + if (result.orderId.isNotBlank()) " • Order: ${result.orderId}" else ""
                            emergency = XtLiveTradingEngine.isEmergencyStopped(context)
                            enabled = XtLiveTradingEngine.isEnabled(context)
                            preview = null
                            selectedSignal = null
                            busy = false
                        }
                    }
                ) { Text(if (busy) "در حال ارسال..." else "ارسال سفارش واقعی") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { preview = null; selectedSignal = null }) {
                    Text("انصراف")
                }
            }
        )
    }
}

private fun fmt(value: Double): String = String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
private fun money(value: Double): String = String.format(Locale.US, "%.4f", value)
