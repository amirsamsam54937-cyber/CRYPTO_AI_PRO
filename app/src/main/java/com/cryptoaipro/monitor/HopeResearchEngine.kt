package com.cryptoaipro.monitor

import android.content.Context
import android.text.Html
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
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
 * Hidden, keyless-first research retrieval.
 * Primary free route: DuckDuckGo HTML metasearch; fallback: Wikipedia OpenSearch.
 * Optional encrypted Brave key is retained only as an additional provider, never required.
 * Retrieved pages are untrusted context and never directly alter signals or neural weights.
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
        val generator = KeyGenerator.getInstance(KeyProperties.ALGORITHM_AES, "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @Synchronized
    fun saveKey(context: Context, token: String) {
        require(token.isNotBlank()) { "کلید جست‌وجو خالی است." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(token.trim().toByteArray(StandardCharsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(DATA, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
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

    private fun request(url: String, headers: Map<String, String> = emptyMap()): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 9000
            connection.readTimeout = 12000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            connection.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP $status")
            return connection.inputStream.bufferedReader().use { it.readText() }.take(1_500_000)
        } finally { connection.disconnect() }
    }

    private fun decodeHtml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    private fun searchDuckDuckGo(query: String): List<HopeResearchSource> {
        val html = request("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
        val anchor = Regex("(?is)<a[^>]*class=[\"'][^\\"']*result__a[^\\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
        val snippetRegex = Regex("(?is)<a[^>]*class=[\"'][^\\"']*result__snippet[^\\"']*[\"'][^>]*>(.*?)</a>|<div[^>]*class=[\"'][^\\"']*result__snippet[^\\"']*[\"'][^>]*>(.*?)</div>")
        val snippets = snippetRegex.findAll(html).map { decodeHtml(it.groupValues[1].ifBlank { it.groupValues[2] }) }.toList()
        val found = mutableListOf<HopeResearchSource>()
        anchor.findAll(html).take(8).forEachIndexed { index, match ->
            val raw = decodeHtml(match.groupValues[1]).replace("&amp;", "&")
            val link = runCatching {
                if (raw.startsWith("//")) "https:$raw" else raw
            }.getOrDefault(raw)
            val finalUrl = runCatching {
                val uri = java.net.URI(link)
                val encoded = uri.rawQuery?.split("&")?.firstOrNull { it.startsWith("uddg=") }?.substringAfter("uddg=")
                if (encoded != null) URLDecoder.decode(encoded, "UTF-8") else link
            }.getOrDefault(link)
            val title = decodeHtml(match.groupValues[2]).take(180)
            val snippet = snippets.getOrNull(index).orEmpty()
            if (finalUrl.startsWith("http") && title.isNotBlank()) {
                found.add(HopeResearchSource(title, finalUrl, snippet.ifBlank { title }.take(1200)))
            }
        }
        return found
    }

    private fun searchWikipedia(query: String): List<HopeResearchSource> {
        val body = request("https://en.wikipedia.org/w/api.php?action=opensearch&search=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=6&namespace=0&format=json")
        val arr = JSONArray(body)
        val titles = arr.optJSONArray(1) ?: return emptyList()
        val descriptions = arr.optJSONArray(2)
        val urls = arr.optJSONArray(3)
        return (0 until titles.length()).mapNotNull { i ->
            val title = titles.optString(i)
            val link = urls?.optString(i).orEmpty()
            if (title.isBlank() || !link.startsWith("https://")) null
            else HopeResearchSource(title, link, descriptions?.optString(i).orEmpty().ifBlank { title })
        }
    }

    private fun searchBrave(context: Context, query: String): List<HopeResearchSource> {
        val token = loadKey(context) ?: return emptyList()
        val body = request("https://api.search.brave.com/res/v1/llm/context?q=" +
            URLEncoder.encode(query, "UTF-8") + "&maximum_number_of_tokens=2048&context_threshold_mode=balanced",
            mapOf("X-Subscription-Token" to token))
        val generic = JSONObject(body).optJSONObject("grounding")?.optJSONArray("generic") ?: return emptyList()
        return (0 until generic.length()).mapNotNull { i ->
            val item = generic.optJSONObject(i) ?: return@mapNotNull null
            val link = item.optString("url")
            val title = item.optString("title").ifBlank { "Web source" }
            val snippets = item.optJSONArray("snippets")
            val snippet = if (snippets == null) "" else
                (0 until snippets.length()).map { snippets.optString(it) }.filter { it.isNotBlank() }.joinToString(" ").take(1400)
            if (link.startsWith("https://") && snippet.isNotBlank()) HopeResearchSource(title, link, snippet) else null
        }.take(8)
    }

    suspend fun search(context: Context, query: String): List<HopeResearchSource> = withContext(Dispatchers.IO) {
        val q = query.trim().take(500)
        require(q.isNotBlank()) { "عبارت جست‌وجو خالی است." }
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[q.lowercase()]?.takeIf { now - it.first < TTL_MS }?.let { return@withContext it.second }
        }
        val results = mutableListOf<HopeResearchSource>()
        runCatching { searchBrave(context, q) }.onSuccess { results.addAll(it) }
        if (results.isEmpty()) runCatching { searchDuckDuckGo(q) }.onSuccess { results.addAll(it) }
        if (results.isEmpty()) runCatching { searchWikipedia(q) }.onSuccess { results.addAll(it) }
        val unique = results.distinctBy { it.url }.take(8)
        synchronized(cache) {
            cache[q.lowercase()] = now to unique
            while (cache.size > 32) cache.remove(cache.keys.first())
        }
        unique
    }

    suspend fun refreshCoreKnowledge(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_REFRESH, 0L) < REFRESH_MS) return 0
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
        prefs.edit().putLong(LAST_REFRESH, now).apply()
        return completed
    }

    suspend fun contextFor(context: Context, question: String): String {
        val direct = runCatching { search(context, question) }.getOrDefault(emptyList())
        val related = synchronized(cache) { cache.values.flatMap { it.second } }
        val tokens = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        val ranked = (direct + related).distinctBy { it.url }.sortedByDescending { source ->
            val words = (source.title + " " + source.snippet).lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+")).toSet()
            tokens.intersect(words).size
        }.take(6)
        return ranked.joinToString("\n\n") { "[${it.title}] ${it.snippet}\nSource: ${it.url}" }.take(9000)
    }
}
