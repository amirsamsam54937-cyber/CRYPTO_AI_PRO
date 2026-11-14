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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DATA, encoded).apply()
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
                "کلید ذخیره‌شده وجود دارد؛ برای بررسی، اتصال خواندنی را آزمایش کن."
            else "برای اتصال خواندنی، کلید API با مجوز Read-only بساز."
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
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("اتصال رسمی XT Futures • مرحله خواندنی", color = ivory,
                fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(
                "این مرحله فقط امضای درخواست و دسترسی خواندن حساب را بررسی می‌کند. برای Shadow/Paper هیچ مجوز Trade یا Withdrawal لازم نیست و هیچ سفارش، تغییر اهرم یا انتقال وجهی ارسال نمی‌شود.",
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
                    runCatching { XtCredentialVault.save(context, appKey, secret) }
                        .onSuccess {
                            appKey = ""
                            secret = ""
                            status = "کلیدها با AES-GCM و Android Keystore روی همین گوشی ذخیره شدند."
                        }
                        .onFailure { status = "ذخیره امن انجام نشد: ${it.message ?: "خطای رمزنگاری"}" }
                },
                enabled = !busy && appKey.isNotBlank() && secret.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("ذخیره امن کلید خواندنی") }
            Button(
                onClick = {
                    busy = true
                    status = "در حال بررسی امضای XT و دسترسی فقط‌خواندنی حساب..."
                    scope.launch {
                        try {
                            val account = XtAuthenticatedFuturesClient(context).accountSnapshot()
                            accountLine = "موجودی کیف پول: ${String.format(java.util.Locale.US, "%.4f", account.walletBalanceUsdt)} USDT • موجودی آزاد تخمینی: ${String.format(java.util.Locale.US, "%.4f", account.availableBalanceUsdt)} USDT"
                            status = "اتصال خصوصی خواندنی موفق شد. مجوز حساب برای معامله: ${if (account.canTrade) "مجاز" else "غیرفعال"} • مجوز بازکردن پوزیشن حساب: ${if (account.canOpenPosition) "مجاز" else "غیرفعال"}. این بررسی به‌هیچ‌وجه سفارش ارسال نکرد."
                        } catch (error: Exception) {
                            accountLine = ""
                            status = "بررسی اتصال ناموفق: ${error.message ?: error.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && XtCredentialVault.hasCredentials(context),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "در حال بررسی..." else "آزمایش اتصال خواندنی XT") }
            if (accountLine.isNotBlank()) Text(accountLine, color = ivory, fontSize = 10.sp)
            Text(status, color = gold, fontSize = 9.sp, lineHeight = 14.sp)
            Text(
                XtExecutionPolicy.label(
                    XtExecutionPolicy.State(
                        readOnlyConnectionVerified = accountLine.isNotBlank()
                    )
                ),
                color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "در پنل XT کلید را با مجوز Read-only بساز؛ مجوز Trade و Withdrawal را خاموش بگذار و IP whitelist را فعال کن. برنامه فعلاً این مجوزها را از روی رابط API به‌طور مستقل ممیزی نمی‌کند.",
                color = muted, fontSize = 9.sp, lineHeight = 14.sp
            )
            TextButton(
                onClick = {
                    XtLiveTradingEngine.setEnabled(context, false)
                    XtCredentialVault.clear(context)
                    accountLine = ""
                    status = "کلیدهای ذخیره‌شده و کلید رمزنگاری دستگاه پاک شدند."
                },
                modifier = Modifier.align(Alignment.End)
            ) { Text("پاک‌کردن کلیدهای ذخیره‌شده", color = gold, fontSize = 10.sp) }
            Text(
                "وضعیت اجرای واقعی: قفل سخت‌افزاری/نرم‌افزاری فعال؛ تا زمان فراهم‌شدن محیط آزمون معتبر و بازبینی مستقل، هیچ سفارش واقعی ارسال نمی‌شود.",
                color = muted, fontSize = 9.sp, lineHeight = 14.sp
            )
        }
    }
}
