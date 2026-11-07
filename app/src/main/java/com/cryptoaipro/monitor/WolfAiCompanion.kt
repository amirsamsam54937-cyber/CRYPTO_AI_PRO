package com.cryptoaipro.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class WolfChatMessage(val text: String, val fromUser: Boolean, val source: String = "")

private fun localPersianReply(question: String): String? {
    val normalized = question.lowercase()
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[\\u200c\\s،,.!؟?]+"), " ")
        .trim()
    val words = normalized.split(" ").filter { it.isNotBlank() }
    if (words.isEmpty()) return "پیامت خالی است؛ هر سؤالی داری بنویس."
    val greetings = setOf("سلام", "درود", "hi", "hello", "hey")
    if (words.first() in greetings || normalized.startsWith("سلام علیکم") || normalized.startsWith("علیک سلام")) {
        return "سلام رفیق! خوش اومدی 🐺 من گرگ، دستیار برنامه‌ات هستم. می‌تونم درباره بازار و تحلیل تکنیکال کمکت کنم. چه چیزی می‌خوای بدونی؟"
    }
    if (words.any { it in setOf("ممنون", "مرسی", "سپاس", "متشکرم") }) return "خواهش می‌کنم! هر وقت خواستی من اینجام. 🐺"
    if (normalized.contains("اسم") && (normalized.contains("تو") || normalized.contains("شما"))) return "من دستیار گرگ در برنامه AMIR ARSALAN TRADER WOLF هستم."
    if (normalized in setOf("خوبی", "حالت چطوره", "چطوری")) return "ممنون، آماده‌ام کمکت کنم! تو چه کمکی ازم می‌خوای؟"
    return null
}

/**
 * Retrieval-first companion: searches public web knowledge endpoints and explains retrieved
 * material. This is not represented as a pretrained generative LLM. No exchange credentials
 * or private account data are sent to search providers.
 */
internal object WolfKnowledgeSearch {
    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 9000
            readTimeout = 12000
            setRequestProperty("User-Agent", "AmirArsalanTraderWolf/2.14 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("سرویس جست‌وجو پاسخ HTTP $code داد")
            body
        } finally { connection.disconnect() }
    }

    suspend fun answer(question: String): Pair<String, String> = withContext(Dispatchers.IO) {
        // Prefer the curated offline Persian knowledge pack; use web retrieval for uncovered topics.
        WolfMarketKnowledge.answer(question)?.let { return@withContext it }
        val encoded = URLEncoder.encode(question.take(180), "UTF-8")
        val findings = mutableListOf<String>()
        var source = ""
        runCatching {
            val json = JSONObject(get("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"))
            val abstractText = json.optString("AbstractText").trim()
            if (abstractText.isNotBlank()) {
                findings.add(abstractText)
                source = json.optString("AbstractURL", "DuckDuckGo")
            }
            val answer = json.optString("Answer").trim()
            if (answer.isNotBlank()) findings.add(answer)
            val related = json.optJSONArray("RelatedTopics")
            if (related != null) {
                for (i in 0 until minOf(related.length(), 5)) {
                    val item = related.optJSONObject(i) ?: continue
                    val text = item.optString("Text").trim()
                    if (text.isNotBlank()) findings.add(text)
                }
            }
        }
        if (findings.isEmpty()) runCatching {
            val wiki = JSONArray(get("https://en.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=5&namespace=0&format=json"))
            val titles = wiki.optJSONArray(1)
            val descriptions = wiki.optJSONArray(2)
            val links = wiki.optJSONArray(3)
            if (titles != null) {
                for (i in 0 until minOf(titles.length(), 5)) {
                    val title = titles.optString(i)
                    val description = descriptions?.optString(i).orEmpty()
                    if (title.isNotBlank()) findings.add(if (description.isBlank()) title else "$title: $description")
                    if (source.isBlank()) source = links?.optString(i).orEmpty()
                }
            }
        }
        if (findings.isEmpty()) {
            "برای این پرسش نتیجه قابل اتکایی از جست‌وجوی عمومی دریافت نکردم. عبارت را دقیق‌تر کن یا نام موضوع را مشخص کن. اتصال اینترنت و در دسترس بودن سرویس جست‌وجو را هم بررسی کن." to ""
        } else {
            "نتایج بازیابی‌شده از وب:\n\n" + findings.distinct().take(5).joinToString("\n\n") +
                "\n\nاین پاسخ بر پایه منابع بازیابی‌شده است، نه آموزش لحظه‌ای وزن‌های یک مدل زبانی. برای موضوعات حساس، اطلاعات را با منبع معتبر دیگری هم بررسی کن." to source
        }
    }
}

@Composable
internal fun WolfAiCompanionScreen(marketSignal: MarketSignal? = null) {
    val messages = remember {
        mutableStateListOf(
            WolfChatMessage(
                "سلام! من دستیار دانشی گرگ هستم. می‌توانی درباره تحلیل تکنیکال، مفاهیم برنامه‌نویسی، علم، تاریخ و موضوعات عمومی سؤال کنی. برای پرسش‌های دانشی، وب را جست‌وجو می‌کنم و منبع را نشان می‌دهم. فعلاً موتور من بازیابی و جمع‌بندی نتایج است، نه یک مدل زبانی عمومیِ ازپیش‌آموزش‌دیده.",
                false
            )
        )
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val llmPrefs = remember { context.getSharedPreferences("wolf_llm_settings", android.content.Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(llmPrefs.getString("api_key", "").orEmpty()) }
    var model by remember { mutableStateOf(llmPrefs.getString("model", "openai/gpt-4o-mini") ?: "openai/gpt-4o-mini") }
    var endpoint by remember { mutableStateOf(llmPrefs.getString("endpoint", "https://openrouter.ai/api/v1/chat/completions") ?: "https://openrouter.ai/api/v1/chat/completions") }
    var configStatus by remember { mutableStateOf(if (apiKey.isBlank()) "برای پاسخ مولد، کلید API خودت را وارد کن." else "مدل زبانی پیکربندی شده است.") }
    val scope = rememberCoroutineScope()
    val navy = Color(0xFF090B0D)
    val panel = Color(0xFF191E21)
    val blue = Color(0xFFE1B86B)
    val muted = Color(0xFFAEB7BC)

    Column(Modifier.fillMaxSize().background(navy).padding(12.dp)) {
        Surface(color = Color(0xFF14243C), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("WOLF AI • دستیار زبانی و تحلیلی", color = Color.White, fontSize = 18.sp)
                Text("دانشنامه تخصصی آفلاین + جست‌وجوی وب + مدل زبانی قابل اتصال", color = muted, fontSize = 10.sp)
                Text("بدون اتصال به حساب صرافی یا امکان ثبت سفارش", color = Color(0xFF27D6A0), fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(9.dp))
        Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("اتصال مدل زبانی (OpenAI-compatible)", color = Color.White, fontSize = 12.sp)
                Text("برای پاسخ مولد، کلید API شخصی لازم است؛ کلید داخل APK قرار نگرفته است.", color = muted, fontSize = 9.sp)
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("نشانی API") })
                OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("شناسه مدل") })
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("کلید API") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(configStatus, color = muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    Button(onClick = { llmPrefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim()).putString("api_key", apiKey.trim()).apply(); configStatus = if (apiKey.isBlank()) "کلید API وارد نشده؛ دانشنامه فعال است." else "تنظیمات مدل ذخیره شد." }) { Text("ذخیره مدل", fontSize = 10.sp) }
                }
                marketSignal?.let { s -> Text("زمینه بازار: " + s.symbol + " • " + s.direction + " • اطمینان " + s.confidence + "% • RSI " + String.format(java.util.Locale.US, "%.1f", s.rsi), color = blue, fontSize = 9.sp) }
            }
        }
        Spacer(Modifier.height(9.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(messages) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (message.fromUser) Color(0xFF173A5C) else panel,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(0.92f)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (message.fromUser) "تو" else "گرگ", color = blue, fontSize = 10.sp)
                            Text(message.text, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
                            if (message.source.isNotBlank()) Text("منبع: " + message.source, color = muted, fontSize = 9.sp)
                        }
                    }
                }
            }
            if (busy) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = blue, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("در حال آماده‌سازی پاسخ...", color = muted, fontSize = 11.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("هر چیزی می‌خواهی بپرس...", color = muted, fontSize = 12.sp) },
                singleLine = false,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val q = input.trim()
                    if (q.isNotBlank() && !busy) {
                        input = ""
                        messages.add(WolfChatMessage(q, true))
                        busy = true
                        scope.launch {
                            val response = if (apiKey.isNotBlank()) {
                                runCatching { WolfLanguageModel.answer(q, apiKey, endpoint, model, marketSignal) }
                                    .getOrElse { ("اتصال مدل زبانی ناموفق بود: " + (it.message ?: "خطای ناشناخته")) to "" }
                            } else {
                                localPersianReply(q)?.let { it to "" }
                                    ?: WolfCryptoKnowledgeBase.answer(q)
                                    ?: runCatching { WolfKnowledgeSearch.answer(q) }
                                        .getOrElse { ("جست‌وجوی اینترنتی ناموفق بود: " + (it.message ?: "خطای ناشناخته")) to "" }
                            }
                            messages.add(WolfChatMessage(response.first, false, response.second))
                            busy = false
                        }
                    }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = blue,
                    unfocusedBorderColor = muted,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Button(
                enabled = input.isNotBlank() && !busy,
                onClick = {
                    val q = input.trim()
                    input = ""
                    messages.add(WolfChatMessage(q, true))
                    busy = true
                    scope.launch {
                        val response = if (apiKey.isNotBlank()) {
                            runCatching { WolfLanguageModel.answer(q, apiKey, endpoint, model, marketSignal) }
                                .getOrElse { ("اتصال مدل زبانی ناموفق بود: " + (it.message ?: "خطای ناشناخته")) to "" }
                        } else {
                            localPersianReply(q)?.let { it to "" }
                                ?: WolfCryptoKnowledgeBase.answer(q)
                                ?: runCatching { WolfKnowledgeSearch.answer(q) }
                                    .getOrElse { ("جست‌وجوی اینترنتی ناموفق بود: " + (it.message ?: "خطای ناشناخته")) to "" }
                        }
                        messages.add(WolfChatMessage(response.first, false, response.second))
                        busy = false
                    }
                }
            ) { Text("بپرس") }
        }
        Text("نتایج وب ممکن است ناقص یا نادرست باشند؛ اطلاعات مهم را راستی‌آزمایی کن.", color = muted, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
    }
}
