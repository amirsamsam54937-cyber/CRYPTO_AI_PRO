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
import kotlinx.coroutines.withContext

/** Read-only foreground market monitor; it never places or manages exchange orders. */
class HopeHunterMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "hope_hunter_market_monitor"
    private val notificationId = 2401
    private val signalChannelId = "hope_hunter_new_signals"
    private var monitorJob: kotlinx.coroutines.Job? = null
    private var livePositionJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notificationId, notification("در حال آماده‌سازی پایش بازار XT"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (XtLiveTradingEngine.positionCount(this) > 0) {
                    updateNotification("پوزیشن واقعی باز است؛ سرویس برای نظارت و حفاظت آن متوقف نمی‌شود.")
                    startMonitoring()
                    return START_STICKY
                }
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
        if (livePositionJob?.isActive != true) {
            livePositionJob = serviceScope.launch {
                while (isActive) {
                    if (XtCredentialVault.hasCredentials(this@HopeHunterMonitorService)) {
                        XtLiveTradingEngine.monitorOpenPositions(this@HopeHunterMonitorService)
                    }
                    delay(2_000L)
                }
            }
        }
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val signals = XtFuturesMonitor.scan { _, completed, universe, failed ->
                        updateNotification("پایش XT • " + completed + " از " + universe + " قرارداد • خطا: " + failed)
                    }
                    WolfMarketDataStore.recordLiveScan(this@HopeHunterMonitorService, "XT", signals)
                    // Background intelligence refresh is throttled internally to 15 minutes.
                    WolfFlowNewsRepository.refreshForEngine(this@HopeHunterMonitorService, signals, "XT")
                    // Keep background scans behaviorally identical to foreground scans.
                    val enrichedSignals = WolfFlowNewsRepository.enrichSignals(this@HopeHunterMonitorService, signals)
                    val scoredSignals = applyWolfNeuralScoring(this@HopeHunterMonitorService, enrichedSignals)
                    // Live execution remains disabled until explicitly enabled in settings.
                    // When enabled, only Commander-approved 5m scalps with fresh liquidity
                    // flow and exchange-native TP/SL are eligible for an XT order.
                    XtLiveTradingEngine.processScan(this@HopeHunterMonitorService, scoredSignals)
                    notifyNewPublishedSignals(scoredSignals)
                    withContext(Dispatchers.Default) {
                        HopeScenarioLab.recordAndEvaluate(this@HopeHunterMonitorService, scoredSignals)
                    }
                    val outcomeSnapshot = WolfSignalOutcomeJournal.processScan(this@HopeHunterMonitorService, scoredSignals)
                    val learningStats = WolfLearningMemory.processScan(this@HopeHunterMonitorService, scoredSignals, outcomeSnapshot.records)
                    HopeLivingBrain.runCycle(this@HopeHunterMonitorService, scoredSignals, learningStats, outcomeSnapshot.records)
                    updateNotification(
                        "پایش کامل • " + scoredSignals.size + " تحلیل • پوزیشن واقعی XT: " +
                            XtLiveTradingEngine.positionCount(this@HopeHunterMonitorService)
                    )
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

    private fun notifyNewPublishedSignals(signals: List<MarketSignal>) {
        val active = signals.asSequence()
            .filter { it.direction == "LONG_BIAS" || it.direction == "SHORT_BIAS" }
            .filter { it.signalTier != "بدون رتبه" }
            .map { signal ->
                listOf(signal.symbol, signal.direction, signal.tradeTimeframe, signal.entry.toString()).joinToString("|")
            }
            .toSet()
        val prefs = getSharedPreferences("hope_signal_notifications", Context.MODE_PRIVATE)
        val previous = prefs.getStringSet("active_signal_keys", emptySet())?.toSet() ?: emptySet()
        val fresh = active - previous
        fresh.forEach { key ->
            val signal = signals.firstOrNull {
                listOf(it.symbol, it.direction, it.tradeTimeframe, it.entry.toString()).joinToString("|") == key
            } ?: return@forEach
            val side = if (signal.direction == "LONG_BIAS") "LONG صعودی" else "SHORT نزولی"
            val details = "ورود: " + String.format(java.util.Locale.US, "%.8f", signal.entry) +
                " • حدضرر: " + String.format(java.util.Locale.US, "%.8f", signal.stop) +
                " • TP1: " + String.format(java.util.Locale.US, "%.8f", signal.tp1)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, signalChannelId) else Notification.Builder(this)
            val notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("HOPE HUNTER • سیگنال جدید " + signal.symbol + " • " + side)
                .setContentText(details)
                .setStyle(Notification.BigTextStyle().bigText(details + " • قدرت: " + signal.confidence + "٪ • تایم‌فریم: " + signal.tradeTimeframe))
                .setAutoCancel(true)
                .build()
            val id = 3000 + (signal.symbol.hashCode() and 0x00FFFFFF)
            getSystemService(NotificationManager::class.java).notify(id, notification)
        }
        prefs.edit().putStringSet("active_signal_keys", active).apply()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "پایش بازار HOPE HUNTER", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "وضعیت اسکن عمومی بازار قراردادهای XT"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(signalChannelId, "سیگنال‌های جدید HOPE HUNTER", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "اعلان فرصت‌های تازه و تأییدشده تحلیل بازار XT"
                    setShowBadge(true)
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
