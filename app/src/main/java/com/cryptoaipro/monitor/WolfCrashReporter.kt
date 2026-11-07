package com.cryptoaipro.monitor

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal on-device crash journal for diagnosing startup/runtime failures.
 * Stores only the latest uncaught exception locally; no data is transmitted.
 */
internal object WolfCrashReporter {
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { writer ->
                    error.printStackTrace(PrintWriter(writer))
                }.toString().take(24000)
                app.getSharedPreferences("wolf_diagnostics", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", trace)
                    .putString("last_crash_thread", thread.name.take(120))
                    .putLong("last_crash_at", System.currentTimeMillis())
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
