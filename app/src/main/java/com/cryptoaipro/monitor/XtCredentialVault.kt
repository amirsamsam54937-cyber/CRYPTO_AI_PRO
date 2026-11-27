package com.cryptoaipro.monitor

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local AES-GCM vault backed by Android Keystore. Credentials never leave this device in this release. */
internal object XtCredentialVault {
    private const val ALIAS = "hope_hunter_xt_api_aes_v1"
    private const val PREFS = "hope_xt_secure_vault"
    private const val DATA = "encrypted_credentials"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun save(context: Context, appKey: String, secret: String) {
        require(appKey.isNotBlank() && secret.isNotBlank()) { "هر دو فیلد کلید لازم‌اند." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plain = appKey.trim() + "\n" + secret.trim()
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + encrypted
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DATA, encoded).putBoolean("connection_verified", false).apply()
    }

    @Synchronized
    fun load(context: Context): Pair<String, String>? = runCatching {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DATA, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12)
        val iv = packed.copyOfRange(0, 12)
        val payload = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val plain = String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        val split = plain.indexOf('\n')
        require(split > 0 && split < plain.lastIndex)
        plain.substring(0, split) to plain.substring(split + 1)
    }.getOrNull()

    @Synchronized
    fun hasCredentials(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(DATA)

    @Synchronized
    fun isConnectionVerified(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("connection_verified", false)

    @Synchronized
    fun markConnectionVerified(context: Context, verified: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("connection_verified", verified).apply()
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(ALIAS)
        }
    }
}

@Composable
internal fun XtApiCredentialPanel(context: Context, onBackgroundStarted: () -> Unit = {}) {
    var appKey by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (XtCredentialVault.hasCredentials(context))
                "کلید ذخیره‌شده وجود دارد؛ برای بررسی حساب و مجوزهای Futures اتصال را آزمایش کن."
            else "برای اتصال XT، کلید API با مجوز Futures Trade و بدون Withdrawal استفاده کن."
        )
    }
    var accountLine by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val gold = Color(0xFFFFD45A)
    val ivory = Color(0xFFECE5D6)
    val muted = Color(0xFFB8B0A0)

    Surface(
        color = Color(0xFF111111),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, gold.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ZORO • XT FUTURES", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    Text("مرکز اتصال و اجرای معاملات", color = ivory,
                        fontSize = 17.sp, fontWeight = FontWeight.Black)
                }
                Surface(color = Color(0xFF263C32), shape = RoundedCornerShape(50)) {
                    Text("USDT-M", color = Color(0xFF73E0B6), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            Text("اتصال امن حساب و مجوزها", color = ivory,
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "آزمایش اتصال فقط وضعیت حساب USDT-M Futures، موجودی، Equity، مارجین آزاد و مجوزهای حساب را می‌خواند و هیچ سفارشی ارسال نمی‌کند. پس از ذخیره کلید و موفقیت این آزمون، LIVE را در پنل پایین روشن کن؛ سپس کلید معامله‌گر خودکار فعال می‌شود.",
                color = muted, fontSize = 10.sp, lineHeight = 15.sp
            )
            OutlinedTextField(
                value = appKey, onValueChange = { appKey = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("XT API Key") }
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("XT API Secret") }
            )
            Button(
                onClick = {
                    if (!XtCredentialVault.hasCredentials(context)) {
                        XtLiveTradingEngine.setEnabled(context, false)
                        runCatching { XtCredentialVault.save(context, appKey, secret) }
                            .onSuccess {
                                appKey = ""
                                secret = ""
                                status = "کلیدها با AES-GCM و Android Keystore روی همین گوشی ذخیره شدند."
                            }
                            .onFailure { status = "ذخیره امن انجام نشد: ${it.message ?: "خطای رمزنگاری"}" }
                    } else {
                        busy = true
                        status = "در حال بررسی پوزیشن‌ها و سفارش‌های باز پیش از تعویض کلید..."
                        scope.launch {
                            try {
                                if (!XtLiveTradingEngine.canClearCredentials(context)) {
                                    status = "تعویض کلید مسدود شد؛ ابتدا تمام پوزیشن‌ها، سفارش‌ها و طرح‌های TP/SL فعال را در XT تعیین تکلیف کن."
                                } else {
                                    XtLiveTradingEngine.setEnabled(context, false)
                                    XtCredentialVault.save(context, appKey, secret)
                                    appKey = ""
                                    secret = ""
                                    accountLine = ""
                                    status = "کلید جدید با AES-GCM و Android Keystore روی همین گوشی ذخیره شد؛ حالت LIVE خاموش است."
                                }
                            } catch (error: Exception) {
                                status = "تعویض کلید انجام نشد: ${error.message ?: error.javaClass.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy && appKey.isNotBlank() && secret.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("ذخیره امن کلید API فیوچرز") }
            Button(
                onClick = {
                    busy = true
                    status = "در حال بررسی امضای XT، موجودی و مجوزهای حساب..."
                    scope.launch {
                        try {
                            val client = XtAuthenticatedFuturesClient(context)
                            val wallet = client.futuresWallet()
                            val account = client.accountSnapshot()
                            XtCredentialVault.markConnectionVerified(context, account.canTrade && account.canOpenPosition)
                            accountLine = "Wallet: ${String.format(java.util.Locale.US, "%.4f", wallet.walletBalance)} USDT • Equity: ${String.format(java.util.Locale.US, "%.4f", wallet.equity)} USDT • Free margin: ${String.format(java.util.Locale.US, "%.4f", wallet.availableMargin)} USDT • Margin used: ${String.format(java.util.Locale.US, "%.4f", wallet.usedMargin)} USDT"
                            status = if (account.canTrade && account.canOpenPosition)
                                "اتصال خصوصی خواندنی موفق شد و مجوزهای Futures تأیید شدند. اکنون می‌توانی LIVE را روشن کنی. هیچ سفارشی ارسال نشد."
                            else
                                "اتصال برقرار شد، اما مجوز معامله یا بازکردن پوزیشن در XT فعال نیست؛ LIVE و خودکار مسدود ماندند."
                        } catch (error: Exception) {
                            XtCredentialVault.markConnectionVerified(context, false)
                            accountLine = ""
                            status = "بررسی اتصال ناموفق: ${error.message ?: error.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && XtCredentialVault.hasCredentials(context),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "در حال بررسی..." else "آزمایش اتصال و مجوزهای XT") }
            if (accountLine.isNotBlank()) {
                Surface(color = Color(0xFF18231F), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF39745E))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("وضعیت حساب متصل", color = Color(0xFF73E0B6),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(accountLine, color = ivory, fontSize = 10.sp, lineHeight = 16.sp)
                    }
                }
            }
            Surface(color = Color(0xFF201B12), shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, gold.copy(alpha = 0.35f))) {
                Text(status, color = gold, fontSize = 10.sp, lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
            Text(
                XtExecutionPolicy.label(
                    XtExecutionPolicy.State(
                        mode = XtExecutionPolicy.Mode.SHADOW_PAPER,
                        credentialsVerified = accountLine.isNotBlank()
                    )
                ),
                color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
            val neuralStatus = WolfPretrainedEnsemble.status()
            val liveReady = XtLiveTradingEngine.isEnabled(context)
            val autoReady = XtLiveTradingEngine.isAutoEnabled(context)
            val readiness = when {
                !XtCredentialVault.hasCredentials(context) -> "مسدود: کلید API هنوز ذخیره نشده است."
                !XtCredentialVault.isConnectionVerified(context) -> "مسدود: اتصال خصوصی و مجوزهای Futures هنوز تأیید نشده‌اند."
                !neuralStatus.validatedForScoring -> "مدل عصبی فعلی پژوهشی است؛ معامله خودکار فقط از مسیر مغز راهبردی مستقل و کنترل‌های سخت ریسک انجام می‌شود. سفارش دستی نیز پیش‌نمایش و تأیید نهایی جداگانه دارد."
                !liveReady -> "آماده‌سازی ناقص: اتصال تأیید شده، اما LIVE عمداً خاموش است."
                !autoReady -> "LIVE فعال است؛ معامله‌گر خودکار هنوز مسلح نیست."
                else -> "دروازه‌های اولیه فعال‌اند؛ پیش‌بررسی هر سفارش و حفاظت صرافی همچنان اجباری است."
            }
            Text(
                "آمادگی معامله واقعی",
                color = ivory, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            Text(
                readiness,
                color = if (autoReady) Green else gold,
                fontSize = 10.sp, lineHeight = 15.sp
            )
            Surface(color = Color(0xFF171717), shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF34302A))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("دروازه‌های ایمنی معامله", color = ivory, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("شبکه عصبی: " + (if (neuralStatus.validatedForScoring) "اعتبارسنجی‌شده" else "فقط پژوهشی / تأییدنشده"),
                        color = if (neuralStatus.validatedForScoring) Green else gold, fontSize = 10.sp)
                    Text("LIVE: " + (if (liveReady) "روشن" else "خاموش") +
                        "     •     خودکار: " + (if (autoReady) "مسلح" else "خاموش"),
                        color = muted, fontSize = 10.sp)
                    Text("حداکثر ۲ پوزیشن هم‌زمان • حداکثر ۳ ورود روزانه • توقف زیان روزانه ۲٪ • افت سرمایه ۸٪",
                        color = muted, fontSize = 9.sp, lineHeight = 14.sp)
                }
            }
            Text(
                "مدیریت سرمایه ZORO موجودی آزاد را مبنای تخصیص می‌گیرد، نه کل Wallet. نرخ پایه محافظه‌کارانه کارمزد Futures برابر Maker=0.04% و Taker=0.06% است؛ نرخ واقعی VIP باید با کارمزد ثبت‌شده هر Fill تطبیق داده شود. مجوز Futures Trade برای سفارش‌گذاری لازم است؛ Withdrawal باید خاموش بماند و IP whitelist فعال باشد.",
                color = muted, fontSize = 9.sp, lineHeight = 14.sp
            )
            TextButton(
                onClick = {
                    if (!XtCredentialVault.hasCredentials(context)) {
                        XtLiveTradingEngine.setEnabled(context, false)
                        XtCredentialVault.clear(context)
                        accountLine = ""
                        status = "کلیدهای ذخیره‌شده پاک شدند."
                    } else {
                        busy = true
                        scope.launch {
                            try {
                                if (!XtLiveTradingEngine.canClearCredentials(context)) {
                                    status = "تا وقتی پوزیشن باز یا پوزیشن تحت نظارت ZORO وجود دارد، پاک‌کردن کلیدها مسدود است تا پایش حفاظت صرافی قطع نشود."
                                } else {
                                    XtLiveTradingEngine.setEnabled(context, false)
                                    XtCredentialVault.clear(context)
                                    accountLine = ""
                                    status = "کلیدهای ذخیره‌شده و کلید رمزنگاری دستگاه پاک شدند."
                                }
                            } catch (error: Exception) {
                                status = "پیش از پاک‌کردن کلیدها، وضعیت حساب XT قابل تأیید نبود: ${error.message ?: error.javaClass.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.align(Alignment.End)
            ) { Text("پاک‌کردن کلیدهای ذخیره‌شده", color = gold, fontSize = 10.sp) }
            Text(
                "ترتیب فعال‌سازی: ۱) ذخیره امن کلید ۲) آزمایش موفق اتصال ۳) روشن‌کردن LIVE در پنل اجرای XT ۴) روشن‌کردن معامله‌گر خودکار. ورود خودکار فقط پس از تأیید سیگنال، تازگی داده، موجودی و همه کنترل‌های ریسک مجاز است.",
                color = muted, fontSize = 9.sp, lineHeight = 14.sp
            )
        }
    }
}


/**
 * Human-confirmed XT live execution surface. This is deliberately separate from unattended
 * auto trading: every order requires a fresh preflight preview and a second explicit submit.
 */
@Composable
internal fun XtLiveManualExecutionPanel(context: Context, signals: List<MarketSignal>) {
    var selectedSymbol by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<XtLiveTradingEngine.ManualPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var liveEnabled by remember { mutableStateOf(XtLiveTradingEngine.isEnabled(context)) }
    var autoEnabled by remember { mutableStateOf(XtLiveTradingEngine.isAutoEnabled(context)) }
    var emergency by remember { mutableStateOf(XtLiveTradingEngine.isEmergencyStopped(context)) }
    var status by remember { mutableStateOf("برای ارسال سفارش واقعی، اتصال XT را آزمایش و LIVE را جداگانه فعال کن.") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val selected = signals.firstOrNull { it.symbol == selectedSymbol }
    val connected = XtCredentialVault.hasCredentials(context) &&
        XtCredentialVault.isConnectionVerified(context)
    val neuralValidated = WolfPretrainedEnsemble.status().validatedForScoring
    val candidates = signals.filter {
        (it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS") &&
            it.signalTier.isNotBlank() && it.signalTier != "بدون رتبه"
    }.take(12)

    Surface(
        color = Color(0xFF101010),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFE2C477).copy(alpha = 0.42f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("XT • اجرای واقعی با تأیید دستی", color = Color(0xFFE2C477),
                fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(
                "این بخش سفارش واقعی USDT-M می‌فرستد؛ هر سفارش ابتدا پیش‌نمایش تازه می‌گیرد و فقط با لمس دکمه تأیید نهایی ارسال می‌شود. هیچ سفارشی با بازکردن صفحه ارسال نمی‌شود.",
                color = Color(0xFFB8B0A0), fontSize = 10.sp, lineHeight = 15.sp
            )
            Text(
                if (connected) "اتصال خصوصی XT: تأییدشده" else "اتصال خصوصی XT: تأیید نشده؛ ابتدا کلید را ذخیره و اتصال را آزمایش کن.",
                color = if (connected) Color(0xFF39D98A) else Color(0xFFE2C477), fontSize = 10.sp
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("مجازکردن سفارش واقعی (LIVE)", color = Color(0xFFECE5D6),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(if (liveEnabled) "LIVE روشن است؛ ارسال همچنان نیازمند تأیید نهایی است."
                        else "خاموش؛ هیچ سفارش واقعی ارسال نمی‌شود.",
                        color = Color(0xFFB8B0A0), fontSize = 9.sp)
                }
                Switch(
                    checked = liveEnabled,
                    enabled = connected && !busy && !emergency,
                    onCheckedChange = { enabled ->
                        XtLiveTradingEngine.setEnabled(context, enabled)
                        liveEnabled = XtLiveTradingEngine.isEnabled(context)
                        autoEnabled = XtLiveTradingEngine.isAutoEnabled(context)
                        preview = null
                        status = XtLiveTradingEngine.lastStatus(context)
                    }
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("معامله‌گر خودکار", color = Color(0xFFECE5D6),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(if (autoEnabled) "مسلح؛ پس از اسکن و تأیید مغز راهبردی امکان ارسال خودکار دارد."
                        else "خاموش؛ برای فعال‌سازی، LIVE و سپس این کلید را روشن کن.",
                        color = Color(0xFFB8B0A0), fontSize = 9.sp)
                }
                Switch(
                    checked = autoEnabled,
                    enabled = liveEnabled && connected && !busy && !emergency,
                    onCheckedChange = { enabled ->
                        XtLiveTradingEngine.setAutoEnabled(context, enabled)
                        autoEnabled = XtLiveTradingEngine.isAutoEnabled(context)
                        status = XtLiveTradingEngine.lastStatus(context)
                    }
                )
            }
            if (emergency) {
                Text("توقف اضطراری فعال است؛ سفارش جدید مسدود شده.", color = Color(0xFFFF5364), fontSize = 10.sp)
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val reset = XtLiveTradingEngine.resetEmergencyStopIfFlat(context)
                                emergency = XtLiveTradingEngine.isEmergencyStopped(context)
                                liveEnabled = XtLiveTradingEngine.isEnabled(context)
                                status = if (reset) "توقف اضطراری بازنشانی شد؛ LIVE هنوز خاموش است."
                                    else XtLiveTradingEngine.lastStatus(context)
                            } catch (error: Exception) {
                                status = "بازنشانی ایمن ممکن نشد: ${error.message ?: error.javaClass.simpleName}"
                            } finally { busy = false }
                        }
                    }
                ) { Text("بررسی حساب و بازنشانی توقف اضطراری") }
            }
            Text("نامزدهای سیگنال زنده", color = Color(0xFFECE5D6),
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (candidates.isEmpty()) {
                Text("در اسکن فعلی سیگنال جهت‌دار واجد رتبه وجود ندارد؛ ابتدا اسکن بازار را اجرا کن.",
                    color = Color(0xFFB8B0A0), fontSize = 10.sp)
            } else {
                candidates.forEach { signal ->
                    val side = if (signal.direction == "LONG_BIAS") "LONG" else "SHORT"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${signal.symbol} • $side • ${signal.signalTier}", color = Color(0xFFECE5D6),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("قیمت ${"%.6f".format(java.util.Locale.US, signal.price)} • اطمینان ${signal.confidence}٪",
                                color = Color(0xFFB8B0A0), fontSize = 9.sp)
                        }
                        Button(
                            enabled = !busy,
                            onClick = { selectedSymbol = signal.symbol; preview = null; status = "سیگنال ${signal.symbol} انتخاب شد؛ پیش‌نمایش تازه را محاسبه کن." }
                        ) { Text(if (selectedSymbol == signal.symbol) "انتخاب شد" else "انتخاب", fontSize = 9.sp) }
                    }
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && liveEnabled && connected && !emergency && selected != null,
                onClick = {
                    val signal = selected ?: return@Button
                    busy = true
                    preview = null
                    status = "در حال خواندن دوباره حساب، قرارداد، Funding، کارمزد و قیمت XT..."
                    scope.launch {
                        try {
                            preview = XtLiveTradingEngine.prepareManual(context, signal)
                            status = "پیش‌بررسی کامل شد. مقادیر زیر را بررسی کن؛ هنوز هیچ سفارشی ارسال نشده است."
                        } catch (error: Exception) {
                            status = "ارسال سفارش مجاز نشد: ${error.message ?: error.javaClass.simpleName}"
                        } finally { busy = false }
                    }
                }
            ) { Text(if (busy) "در حال بررسی..." else "محاسبه پیش‌نمایش سفارش واقعی") }

            preview?.let { p ->
                Surface(color = Color(0xFF1B1A16), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2C477).copy(alpha = 0.35f))) {
                    Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("پیش‌نمایش سفارش • ${p.symbol} • ${p.side}", color = Color(0xFFE2C477),
                            fontSize = 11.sp, fontWeight = FontWeight.Black)
                        Text("حجم: ${p.contracts} قرارداد • اهرم: ${p.leverage}x • مارجین تخمینی: ${"%.4f".format(java.util.Locale.US, p.marginRequiredUsdt)} USDT",
                            color = Color(0xFFECE5D6), fontSize = 10.sp)
                        Text("ورود مرجع: ${p.referencePrice} • SL: ${p.stop} • TP1: ${p.target}",
                            color = Color(0xFFECE5D6), fontSize = 9.sp)
                        Text("ریسک تخمینی تا SL: ${"%.4f".format(java.util.Locale.US, p.estimatedStopLossUsdt)} USDT • بودجه ریسک: ${"%.4f".format(java.util.Locale.US, p.riskBudgetUsdt)} USDT",
                            color = Color(0xFFFF8A80), fontSize = 9.sp)
                        Text("کارمزد ورود: ${"%.5f".format(java.util.Locale.US, p.estimatedEntryFeeUsdt)} • کارمزد خروج: ${"%.5f".format(java.util.Locale.US, p.estimatedStopExitFeeUsdt)} • Funding ذخیره: ${"%.5f".format(java.util.Locale.US, p.estimatedFundingCostUsdt)} USDT",
                            color = Color(0xFFB8B0A0), fontSize = 9.sp)
                        Text("سود خالص تخمینی تا TP1: ${"%.4f".format(java.util.Locale.US, p.estimatedNetTargetProfitUsdt)} USDT • نسبت خالص R/R: ${"%.2f".format(java.util.Locale.US, p.netRewardRisk)}",
                            color = Color(0xFF39D98A), fontSize = 9.sp)
                        Text("این محاسبه تضمین Fill یا سود نیست. قیمت ممکن است تغییر کند؛ پیش‌نمایش پس از ۴۵ ثانیه منقضی می‌شود.",
                            color = Color(0xFFE2C477), fontSize = 9.sp, lineHeight = 14.sp)
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && liveEnabled && !emergency && selected != null,
                            onClick = {
                                val signal = selected ?: return@Button
                                busy = true
                                status = "در حال ارسال سفارش به XT و راستی‌آزمایی Fill و طرح‌های TP/SL در صرافی..."
                                scope.launch {
                                    try {
                                        val result = XtLiveTradingEngine.executeManual(context, signal, p)
                                        status = (if (result.success) "اجرای واقعی تأیید شد: " else "نتیجه اجرا: ") +
                                            result.message + if (result.orderId.isNotBlank()) " • Order ID: ${result.orderId}" else ""
                                        preview = null
                                    } catch (error: Exception) {
                                        status = "اجرای سفارش متوقف شد: ${error.message ?: error.javaClass.simpleName}"
                                        preview = null
                                    } finally {
                                        emergency = XtLiveTradingEngine.isEmergencyStopped(context)
                                        liveEnabled = XtLiveTradingEngine.isEnabled(context)
                                        autoEnabled = XtLiveTradingEngine.isAutoEnabled(context)
                                        busy = false
                                    }
                                }
                            }
                        ) { Text("تأیید نهایی و ارسال سفارش واقعی به XT") }
                    }
                }
            }
            Text(
                if (!neuralValidated)
                    "وضعیت هوش مصنوعی: مدل عصبی فعلی هنوز اعتبارسنجی مستقل معاملاتی ندارد و در تصمیم اجرای خودکار دخالت داده نمی‌شود. معامله خودکار فقط با رأی مستقل مغز راهبردی، اسکن پس‌زمینه، تأیید LIVE و کنترل‌های سخت ریسک انجام می‌شود؛ سفارش دستی نیز تأیید نهایی جداگانه می‌خواهد."
                else
                    "وضعیت هوش مصنوعی: مدل عصبی اعتبارسنجی شده است؛ بااین‌حال هر سفارش دستی همچنان نیازمند تأیید صریح کاربر است.",
                color = Color(0xFFE2C477), fontSize = 9.sp, lineHeight = 14.sp
            )
            Surface(color = Color(0xFF201B12), shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFFE2C477).copy(alpha = 0.28f))) {
                Text(status, color = Color(0xFFE2C477), fontSize = 10.sp, lineHeight = 15.sp,
                    modifier = Modifier.fillMaxWidth().padding(10.dp))
            }
        }
    }
}
