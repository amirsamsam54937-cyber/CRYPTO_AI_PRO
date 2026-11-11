package com.cryptoaipro.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Read-only foreground market monitor; it never places or manages exchange orders. */
class HopeHunterMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "hope_hunter_market_monitor"
    private val notificationId = 2401
    private var monitorJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notificationId, notification("در حال آماده‌سازی پایش بازار XT"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val signals = XtFuturesMonitor.scan { _, completed, universe, failed ->
                        updateNotification("پایش XT • " + completed + " از " + universe + " قرارداد • خطا: " + failed)
                    }
                    updateNotification("پایش کامل شد • " + signals.size + " تحلیل")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    updateNotification("خطای دریافت داده XT • " + (error.message?.take(90) ?: "اتصال ناموفق"))
                }
                val prefs = getSharedPreferences("wolf_app_settings", Context.MODE_PRIVATE)
                val minutes = prefs.getInt("scan_interval_minutes", 3).coerceIn(1, 10)
                delay(minutes * 60_000L)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "پایش بازار HOPE HUNTER", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "وضعیت اسکن عمومی بازار قراردادهای XT"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("HOPE HUNTER • Market Watch")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(notificationId, notification(message))
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.cryptoaipro.monitor.action.START"
        const val ACTION_STOP = "com.cryptoaipro.monitor.action.STOP"
    }
}
