package com.cryptoaipro.monitor

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Offline-first voice announcements for newly published signals.
 * Speech uses Android TextToSpeech; it does not require microphone permission or a cloud API.
 */
internal class NexusVoiceAnnouncer(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val queue = ConcurrentLinkedQueue<String>()
    @Volatile private var ready = false
    @Volatile private var enabled = appContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, true)
    private var engine: TextToSpeech? = TextToSpeech(appContext, this)

    override fun onInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) return
        val languageResult = tts.setLanguage(Locale("fa", "IR"))
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.language = Locale.getDefault()
        }
        tts.setSpeechRate(0.96f)
        tts.setPitch(1.0f)
        ready = true
        drain()
    }

    fun announceSignal(
        symbol: String,
        direction: String,
        timeframe: String,
        confidence: Int,
        entry: Double,
        stop: Double,
        target1: Double
    ) {
        if (!enabled) return
        val side = if (direction == "LONG_BIAS") "لانگ، موقعیت صعودی" else "شورت، موقعیت نزولی"
        val message = buildString {
            append("توجه. سیگنال جدید نکسوس کوانت. ")
            append(symbol).append(". ").append(side).append(". ")
            append("تایم فریم ").append(timeframe).append(". ")
            append("قدرت سیگنال ").append(confidence.coerceIn(0, 100)).append(" درصد. ")
            append("قیمت ورود ").append(price(entry)).append(". ")
            append("حد ضرر ").append(price(stop)).append(". ")
            append("هدف اول ").append(price(target1)).append(".")
        }
        queue.add(message)
        drain()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) {
            queue.clear()
            engine?.stop()
        }
    }

    fun isEnabled(): Boolean = enabled

    private fun drain() {
        if (!ready || !enabled) return
        val tts = engine ?: return
        while (true) {
            val next = queue.poll() ?: break
            tts.speak(next, TextToSpeech.QUEUE_ADD, null, "nexus_signal_" + System.nanoTime())
        }
    }

    fun shutdown() {
        ready = false
        queue.clear()
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private fun price(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.6f", value) else "نامشخص"

    companion object {
        private const val PREFS = "nexus_voice_preferences"
        private const val KEY_ENABLED = "signal_voice_enabled"
    }
}
