package com.cryptoaipro.monitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts user-supplied provider credentials with a non-exportable Android Keystore AES key. */
internal object WolfSecretStore {
    private const val PREFS = "zoro_secure_secrets"
    private const val ALIAS = "zoro_llm_api_key_aes_gcm"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Synchronized
    fun get(context: Context, name: String): String {
        val app = context.applicationContext
        val secure = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = secure.getString(name, null)
        if (!encoded.isNullOrBlank()) return runCatching { decrypt(encoded) }.getOrDefault("")
        val legacyPrefs = app.getSharedPreferences("wolf_llm_settings", Context.MODE_PRIVATE)
        val legacy = legacyPrefs.getString(name, "").orEmpty()
        if (legacy.isNotBlank()) {
            runCatching { put(context, name, legacy) }.onSuccess {
                legacyPrefs.edit().remove(name).apply()
            }.onFailure {
                return ""
            }
        }
        return legacy
    }

    @Synchronized
    fun put(context: Context, name: String, value: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > IV_BYTES) { "داده رمزگذاری‌شده ناقص است" }
        val iv = combined.copyOfRange(0, IV_BYTES)
        val payload = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
