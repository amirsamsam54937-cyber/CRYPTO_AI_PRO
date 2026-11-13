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
internal fun XtApiCredentialPanel(context: Context) {
    var appKey by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var permissionConfirmed by remember { mutableStateOf(false) }
    var liveEnabled by remember { mutableStateOf(XtLiveTradingEngine.isEnabled(context)) }
    var status by remember {
        mutableStateOf(if (XtCredentialVault.hasCredentials(context)) "کلیدها به‌صورت رمزنگاری‌شده روی دستگاه ذخیره شده‌اند." else "هنوز کلیدی ذخیره نشده است.")
    }
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
            Text("اتصال امن API صرافی XT", color = ivory, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(
                "کلید فقط در Android Keystore رمزنگاری می‌شود. با فعال‌کردن اجرای واقعی، ZORO از آن برای خواندن حساب، بازیابی پوزیشن‌ها و ثبت سفارش فیوچرز استفاده می‌کند.",
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = permissionConfirmed, onCheckedChange = { permissionConfirmed = it })
                Text(
                    "تأیید می‌کنم مجوز برداشت (Withdrawal) خاموش است؛ فقط Read و Trade فعال‌اند.",
                    color = ivory, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
            Button(
                onClick = {
                    runCatching { XtCredentialVault.save(context, appKey, secret) }
                        .onSuccess {
                            appKey = ""
                            secret = ""
                            status = "کلیدها رمزنگاری و فقط روی همین دستگاه ذخیره شدند."
                        }
                        .onFailure { status = "ذخیره امن انجام نشد: ${it.message ?: "خطای رمزنگاری"}" }
                },
                enabled = permissionConfirmed && appKey.isNotBlank() && secret.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("ذخیره امن روی گوشی") }
            TextButton(
                onClick = {
                    XtLiveTradingEngine.setEnabled(context, false)
                    XtCredentialVault.clear(context)
                    liveEnabled = false
                    appKey = ""
                    secret = ""
                    permissionConfirmed = false
                    status = "کلیدهای ذخیره‌شده و کلید رمزنگاری دستگاه پاک شدند."
                },
                modifier = Modifier.align(Alignment.End)
            ) { Text("پاک‌کردن کلیدهای ذخیره‌شده", color = gold, fontSize = 10.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("اجرای خودکار واقعی در XT", color = ivory, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("با فعال‌سازی، سرویس پس‌زمینه فرمانده را به موتور سفارش واقعی متصل می‌کند.", color = muted, fontSize = 9.sp, lineHeight = 13.sp)
                }
                Switch(
                    checked = liveEnabled,
                    enabled = XtCredentialVault.hasCredentials(context) && permissionConfirmed,
                    onCheckedChange = { enabled ->
                        XtLiveTradingEngine.setEnabled(context, enabled)
                        liveEnabled = enabled
                        if (enabled) {
                            context.getSharedPreferences("wolf_app_settings", Context.MODE_PRIVATE)
                                .edit().putBoolean("background_monitoring", true).apply()
                            val serviceIntent = Intent(context, HopeHunterMonitorService::class.java).apply {
                                action = HopeHunterMonitorService.ACTION_START
                            }
                            runCatching { context.startForegroundService(serviceIntent) }
                                .onSuccess { status = "اجرای واقعی فعال شد؛ سرویس پایش و بازیابی پوزیشن XT شروع شد." }
                                .onFailure { status = "سرویس شروع نشد؛ اجرای واقعی تا فعال‌شدن سرویس متوقف می‌ماند: ${it.message}" }
                        } else {
                            status = "ورودهای جدید متوقف شد؛ پایش و حفاظت پوزیشن‌های باز تا زمان توقف سرویس ادامه دارد."
                        }
                    }
                )
            }
            Text(status, color = gold, fontSize = 9.sp, lineHeight = 14.sp)
            Text(
                "کلید را در چت ارسال نکن. مجوز برداشت را خاموش نگه دار و IP whitelist را در پنل XT فعال کن. اجرای واقعی فقط با کلید معتبر، مجوز Trade و روشن‌بودن کلید اجرای خودکار کار می‌کند.",
                color = muted, fontSize = 9.sp, lineHeight = 14.sp
            )
        }
    }
}
