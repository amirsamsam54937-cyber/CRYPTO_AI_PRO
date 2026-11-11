package com.cryptoaipro.monitor

import android.content.Context
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class HopeResearchSource(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Private, on-device search/retrieval layer. Brave LLM Context is used only for grounded
 * reference retrieval; results are volatile, short-lived context and are never used to
 * train or alter model weights or directly change trade signals.
 */
internal object HopeResearchEngine {
    private const val PREFS = "hope_research_secure"
    private const val DATA = "brave_key_ciphertext"
    private const val ALIAS = "hope_hunter_research_aes_v1"
    private const val LAST_REFRESH = "core_refresh_at"
    private const val TTL_MS = 20 * 60 * 1000L
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    private val cache = LinkedHashMap<String, Pair<Long, List<HopeResearchSource>>>()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @Synchronized
    fun saveKey(context: Context, token: String) {
        require(token.isNotBlank()) { "کلید جست‌وجو خالی است." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.trim().toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + encrypted
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DATA, encoded).apply()
    }

    @Synchronized
    private fun loadKey(context: Context): String? = runCatching {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DATA, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        String(cipher.doFinal(packed.copyOfRange(12, packed.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(DATA)

    @Synchronized
    fun clearKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(DATA).apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        synchronized(cache) { cache.clear() }
    }

    suspend fun search(context: Context, query: String): List<HopeResearchSource> = withContext(Dispatchers.IO) {
        val q = query.trim().take(500)
        require(q.isNotBlank()) { "عبارت جست‌وجو خالی است." }
        val token = loadKey(context) ?: return@withContext emptyList()
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[q.lowercase()]?.takeIf { now - it.first < TTL_MS }?.let { return@withContext it.second }
        }
        val url = URL("https://api.search.brave.com/res/v1/llm/context?q=" +
            URLEncoder.encode(q, "UTF-8") + "&maximum_number_of_tokens=2048&context_threshold_mode=balanced")
        val connection = url.openConnection() as HttpURLConnection
        val body: String
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 16000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Subscription-Token", token)
            val status = connection.responseCode
            body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("detail").orEmpty() }.getOrDefault("")
                error("سرویس جست‌وجو HTTP $status: ${detail.take(160)}")
            }
        } finally { connection.disconnect() }

        val root = JSONObject(body)
        val grounding = root.optJSONObject("grounding")
        val generic = grounding?.optJSONArray("generic")
        val found = mutableListOf<HopeResearchSource>()
        if (generic != null) for (i in 0 until generic.length()) {
            val item = generic.optJSONObject(i) ?: continue
            val title = item.optString("title").ifBlank { "منبع وب" }
            val link = item.optString("url")
            val snippets = item.optJSONArray("snippets")
            val snippet = if (snippets != null) (0 until snippets.length()).map { snippets.optString(it) }
                .filter { it.isNotBlank() }.joinToString(" ").take(1400) else ""
            if (link.startsWith("https://") && snippet.isNotBlank()) found.add(HopeResearchSource(title, link, snippet))
        }
        synchronized(cache) {
            cache[q.lowercase()] = now to found.take(8)
            while (cache.size > 24) cache.remove(cache.keys.first())
        }
        found.take(8)
    }

    suspend fun refreshCoreKnowledge(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!isConfigured(context) || now - prefs.getLong(LAST_REFRESH, 0L) < REFRESH_MS) return 0
        val topics = listOf(
            "official XT USDT-M perpetual futures contract specifications order quantity tick size funding API documentation",
            "cryptocurrency futures market structure liquidity order book open interest funding rate research",
            "quantitative trading walk forward validation data leakage transaction costs slippage risk management",
            "cryptocurrency global market capitalization altcoin breadth bitcoin dominance stablecoin dominance methodology"
        )
        var completed = 0
        for (topic in topics) {
            runCatching { search(context, topic) }.onSuccess { if (it.isNotEmpty()) completed++ }
        }
        // Record attempts too, so a broken key/network cannot trigger four searches on every market scan.
        prefs.edit().putLong(LAST_REFRESH, now).apply()
        return completed
    }

    suspend fun contextFor(context: Context, question: String): String {
        val direct = runCatching { search(context, question) }.getOrDefault(emptyList())
        val related = synchronized(cache) {
            cache.values.flatMap { it.second }
        }
        val tokens = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        val ranked = (direct + related).distinctBy { it.url }.sortedByDescending { source ->
            val words = (source.title + " " + source.snippet).lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+")).toSet()
            tokens.intersect(words).size
        }.take(6)
        return ranked.joinToString("\n\n") { "[${it.title}] ${it.snippet}\nSource: ${it.url}" }
            .take(9000)
    }
}

@Composable
internal fun HopeResearchSettingsPanel(context: Context) {
    var token by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(if (HopeResearchEngine.isConfigured(context))
            "موتور جست‌وجو پیکربندی شده؛ کلید در حافظه امن Android رمزنگاری می‌شود."
        else "برای جست‌وجوی وب و بازیابی منابع، کلید Brave Search API لازم است.") 
    }
    val gold = Color(0xFFFFD45A)
    val muted = Color(0xFFB8B0A0)
    Surface(
        color = Color(0xFF111111), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, gold.copy(alpha = 0.30f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("موتور جست‌وجو و بازیابی دانش", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "جست‌وجوی وب برای دستیار هوشمند، مستندات رسمی XT، پژوهش‌های کمی و دانش بازار. نتایج با منبع بازیابی می‌شوند؛ وزن شبکه عصبی خودکار با متن وب تغییر نمی‌کند.",
                color = muted, fontSize = 10.sp, lineHeight = 15.sp
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Brave Search API Key") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        runCatching { HopeResearchEngine.saveKey(context, token) }
                            .onSuccess { token = ""; status = "کلید جست‌وجو روی همین گوشی رمزنگاری و ذخیره شد." }
                            .onFailure { status = "ذخیره کلید انجام نشد: ${it.message}" }
                    },
                    enabled = token.isNotBlank(), modifier = Modifier.weight(1f)
                ) { Text("ذخیره امن") }
                TextButton(onClick = {
                    HopeResearchEngine.clearKey(context)
                    token = ""
                    status = "کلید جست‌وجو و حافظه موقت بازیابی پاک شد."
                }) { Text("پاک‌کردن", color = gold) }
            }
            Text(status, color = gold, fontSize = 9.sp, lineHeight = 14.sp)
            Text("ساخت کلید: https://api.search.brave.com • سرویس بر اساس تعداد درخواست هزینه دارد؛ سقف مصرف را در حساب ارائه‌دهنده کنترل کن. کلید را در چت نفرست.",
                color = muted, fontSize = 9.sp, lineHeight = 14.sp)
        }
    }
}
