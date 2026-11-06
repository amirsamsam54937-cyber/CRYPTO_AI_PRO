package com.cryptoaipro.monitor.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts XT API credentials at rest with a non-exportable Android Keystore AES key.
 *
 * This protects locally stored credentials at rest; it does not make a rooted or
 * compromised device safe, and it does not replace server-side secret management.
 * Never log credential values or include them in crash reports.
 */
class ExchangeCredentialVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    data class Credentials(val apiKey: String, val secretKey: String)

    @Synchronized
    fun save(credentials: Credentials) {
        require(credentials.apiKey.isNotBlank()) { "API Key is required" }
        require(credentials.secretKey.isNotBlank()) { "Secret Key is required" }

        val clearText = JSONObject()
            .put("apiKey", credentials.apiKey)
            .put("secretKey", credentials.secretKey)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clearText)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(encrypted, Base64.NO_WRAP)

        check(prefs.edit()
            .putString(KEY_IV, iv)
            .putString(KEY_PAYLOAD, payload)
            .commit()) { "Could not persist encrypted credentials" }
    }

    @Synchronized
    fun load(): Credentials? {
        val ivText = prefs.getString(KEY_IV, null) ?: return null
        val payloadText = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivText, Base64.NO_WRAP))
            )
            val clear = cipher.doFinal(Base64.decode(payloadText, Base64.NO_WRAP))
            val json = JSONObject(String(clear, Charsets.UTF_8))
            Credentials(
                apiKey = json.getString("apiKey"),
                secretKey = json.getString("secretKey")
            )
        } catch (error: Exception) {
            // Do not silently replace a corrupted/invalidated key with empty credentials.
            throw IllegalStateException(
                "Stored XT credentials could not be decrypted. Re-enter the API keys.",
                error
            )
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().commit()
        // The Keystore key is retained so a later save can reuse it safely.
    }

    fun hasCredentials(): Boolean =
        prefs.contains(KEY_IV) && prefs.contains(KEY_PAYLOAD)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "crypto_ai_secure_exchange"
        const val KEY_IV = "credentials_iv"
        const val KEY_PAYLOAD = "credentials_ciphertext"
        const val KEY_ALIAS = "crypto_ai_xt_credentials_aes"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
