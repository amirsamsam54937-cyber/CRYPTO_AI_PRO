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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground XT futures intelligence loop for TENAXIS.
 * Analysis, signal publication, paper simulation, outcome learning and explicitly armed automatic execution.
 */
class HopeHunterMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "hope_hunter_market_monitor"
    private val notificationId = 2401
    private val signalChannelId = "hope_hunter_new_signals"
    private var monitorJob: kotlinx.coroutines.Job? = null
    private var positionMonitorJob: kotlinx.coroutines.Job? = null
    private var voiceAnnouncer: NexusVoiceAnnouncer? = null

    override fun onCreate() {
        super.onCreate()
        voiceAnnouncer = NexusVoiceAnnouncer(this)
        createChannel()
        startForeground(notificationId, notification("در حال آماده‌سازی موتور تحلیل بازار TENAXIS"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (XtLiveTradingEngine.hasManagedSymbols(this)) {
                    updateNotification("توقف سرویس مجاز نیست؛ NEXUS تا تعیین تکلیف پوزیشن‌های ثبت‌شده باید حفاظت XT را پایش کند.")
                    return START_STICKY
                }
                monitorJob?.cancel()
                monitorJob = null
                positionMonitorJob?.cancel()
                positionMonitorJob = null
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
        if (positionMonitorJob?.isActive != true) {
            positionMonitorJob = serviceScope.launch {
                while (isActive) {
                    runCatching {
                        XtLiveTradingEngine.monitorOpenPositions(this@HopeHunterMonitorService)
                    }.onFailure { monitorError ->
                        ZoroDiagnostics.record(
                            this@HopeHunterMonitorService,
                            "ناظر مستقل حفاظت پوزیشن",
                            "ERROR",
                            monitorError.message ?: monitorError.javaClass.simpleName
                        )
                    }
                    delay(10_000L)
                }
            }
        }
        monitorJob = serviceScope.launch {
            val neuralStatus = WolfPretrainedEnsemble.initialize(this@HopeHunterMonitorService)
            ZoroDiagnostics.record(
                this@HopeHunterMonitorService,
                "شبکه عصبی",
                if (neuralStatus.loaded && neuralStatus.members == 10) "INFO" else "ERROR",
                if (neuralStatus.loaded) {
                    "بارگذاری " + neuralStatus.members + "/10 مدل؛ اعتبارسنجی امتیازدهی=" +
                        neuralStatus.validatedForScoring + "؛ نمونه‌های آموزش=" + neuralStatus.samples +
                        "؛ داده XT=" + neuralStatus.corpusRows
                } else {
                    "بارگذاری مدل ناموفق: " + neuralStatus.loadError
                }
            )
            // Independent knowledge refresh runs off the market-scan path. Public web results
            // are cached with source URLs and remain research-only; they cannot alter orders or model weights.
            serviceScope.launch {
                runCatching {
                    val refreshed = HopeResearchEngine.refreshCoreKnowledge(this@HopeHunterMonitorService)
                    ZoroDiagnostics.record(
                        this@HopeHunterMonitorService,
                        "NEXUS Knowledge Research",
                        "INFO",
                        "refreshed=$refreshed; durableSources=${HopeResearchEngine.memoryCount(this@HopeHunterMonitorService)}; sourceContent=untrusted research context only"
                    )
                }.onFailure { researchError ->
                    ZoroDiagnostics.record(
                        this@HopeHunterMonitorService,
                        "NEXUS Knowledge Research",
                        "WARN",
                        researchError.message ?: researchError.javaClass.simpleName
                    )
                }
            }
            while (isActive) {
                try {
                    // Position protection runs in its own 10-second coroutine, independent of scan latency.
                    val signals = scanWithReconnect()
                    WolfMarketDataStore.recordLiveScan(this@HopeHunterMonitorService, "XT", signals)
                    WolfFlowNewsRepository.refreshForEngine(this@HopeHunterMonitorService, signals, "XT")
                    refreshHiddenSentiment(signals)
                    val enrichedSignals = WolfFlowNewsRepository.enrichSignals(this@HopeHunterMonitorService, signals)
                    val scoredSignals = applyWolfNeuralScoring(this@HopeHunterMonitorService, enrichedSignals)
                    runCatching {
                        // Feed completed 6h outcomes into each specialist's journal during background scans.
                        // Updated reliability weights are applied on the next scoring cycle.
                        WolfNeuralForecastJournal.processScan(this@HopeHunterMonitorService, scoredSignals)
                    }.onFailure { journalError ->
                        ZoroDiagnostics.record(
                            this@HopeHunterMonitorService,
                            "یادگیری شبکه‌های عصبی",
                            "ERROR",
                            journalError.message ?: journalError.javaClass.simpleName
                        )
                    }
                    val paperSnapshot = HopePaperTradingEngine.processScan(this@HopeHunterMonitorService, scoredSignals)
                    val automaticExecution = runCatching {
                        XtLiveTradingEngine.processScan(this@HopeHunterMonitorService, scoredSignals)
                    }.onFailure { autoError ->
                        ZoroDiagnostics.record(
                            this@HopeHunterMonitorService,
                            "معامله‌گر خودکار",
                            "ERROR",
                            autoError.message ?: autoError.javaClass.simpleName
                        )
                    }.getOrNull()
                    notifyNewPublishedSignals(scoredSignals)
                    withContext(Dispatchers.Default) {
                        HopeScenarioLab.recordAndEvaluate(this@HopeHunterMonitorService, scoredSignals)
                    }
                    val outcomeSnapshot = WolfSignalOutcomeJournal.processScan(this@HopeHunterMonitorService, scoredSignals)
                    val learningStats = WolfLearningMemory.processScan(this@HopeHunterMonitorService, scoredSignals, outcomeSnapshot.records)
                    HopeLivingBrain.runCycle(this@HopeHunterMonitorService, scoredSignals, learningStats, outcomeSnapshot.records)
                    runCatching {
                        val mind = NexusAutonomousMind.reflect(
                            this@HopeHunterMonitorService, scoredSignals, outcomeSnapshot.records
                        )
                        ZoroDiagnostics.record(
                            this@HopeHunterMonitorService,
                            "NEXUS Autonomous Mind",
                            "INFO",
                            "cycle=${mind.cycles}; phase=${mind.phase}; evidence=${mind.evidenceCount}; closed=${mind.closedOutcomes}; hypothesis=${mind.currentHypothesis}"
                        )
                    }.onFailure { mindError ->
                        ZoroDiagnostics.record(
                            this@HopeHunterMonitorService,
                            "NEXUS Autonomous Mind",
                            "ERROR",
                            mindError.message ?: mindError.javaClass.simpleName
                        )
                    }
                    val liveStatus = when {
                        XtLiveTradingEngine.isEmergencyStopped(this@HopeHunterMonitorService) ->
                            " • توقف اضطراری: " + XtLiveTradingEngine.lastStatus(this@HopeHunterMonitorService)
                        XtLiveTradingEngine.isAutoEnabled(this@HopeHunterMonitorService) ->
                            " • معامله‌گر خودکار فعال" + (automaticExecution?.let { " • " + it.message } ?: "")
                        XtLiveTradingEngine.isEnabled(this@HopeHunterMonitorService) ->
                            " • LIVE مسلح؛ اجرای خودکار خاموش"
                        else -> " • فقط تحلیل و شبیه‌سازی"
                    }
                    updateNotification("پایش کامل • " + scoredSignals.size + " تحلیل • شبیه‌سازی: " + paperSnapshot.positions.size + " • پوزیشن واقعی تحت نظارت: " + XtLiveTradingEngine.positionCount(this@HopeHunterMonitorService) + liveStatus)
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

    /**
     * Refresh internal sentiment at a bounded cadence. It never renders a news panel
     * or creates a direction; the trading brain uses only fresh, symbol-specific evidence.
     */
    private suspend fun refreshHiddenSentiment(signals: List<MarketSignal>) {
        val prefs = getSharedPreferences("zoro_sentiment_refresh", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_refresh_at", 0L)
        if (now - last < 20L * 60L * 1000L) return
        try {
            val snapshot = ZoroSentimentNetwork.refresh(
                this,
                signals.map { it.symbol }.distinct()
            )
            prefs.edit().putLong("last_refresh_at", snapshot.updatedAtMs).apply()
            ZoroDiagnostics.record(
                this,
                "تحلیل پنهان احساسات بازار",
                "INFO",
                "marketScore=${snapshot.marketScore}; articles=${snapshot.articleCount}; sources=${snapshot.sourceCount}; symbols=${snapshot.nodes.size}"
            )
        } catch (error: Exception) {
            ZoroDiagnostics.record(
                this,
                "تحلیل پنهان احساسات بازار",
                "WARN",
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    /**
     * Retries read-only market discovery after transient network failures.
     * Never retries order submission here: ambiguous trading requests must be reconciled,
     * not blindly replayed. Backoff is bounded and cancellation-aware.
     */
    private suspend fun scanWithReconnect(): List<MarketSignal> {
        var lastError: Throwable? = null
        val delaysMs = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        for (attempt in 0..delaysMs.size) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                updateNotification(
                    if (attempt == 0) "اتصال XT • شروع اسکن بازار"
                    else "اتصال XT قطع شد • تلاش مجدد " + attempt + "/" + delaysMs.size
                )
                return XtFuturesMonitor.scan { _, completed, universe, failed ->
                    updateNotification("پایش XT • " + completed + " از " + universe + " قرارداد • خطا: " + failed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                ZoroDiagnostics.record(
                    this,
                    "بازیابی اتصال XT",
                    "WARN",
                    "attempt=" + (attempt + 1) + "/" + (delaysMs.size + 1) + "; " +
                        (error.message ?: error.javaClass.simpleName)
                )
                if (attempt < delaysMs.size) delay(delaysMs[attempt])
            }
        }
        throw IllegalStateException(
            "پس از " + (delaysMs.size + 1) +
                " تلاش، اسکن XT برقرار نشد؛ سرویس زنده می‌ماند و چرخه بعدی دوباره تلاش می‌کند.",
            lastError
        )
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
                .setContentTitle("TENAXIS • سیگنال جدید " + signal.symbol + " • " + side)
                .setContentText(details)
                .setStyle(Notification.BigTextStyle().bigText(details + " • قدرت: " + signal.confidence + "٪ • تایم‌فریم: " + signal.tradeTimeframe))
                .setAutoCancel(true)
                .build()
            val id = 3000 + (signal.symbol.hashCode() and 0x00FFFFFF)
            getSystemService(NotificationManager::class.java).notify(id, notification)
            voiceAnnouncer?.announceSignal(
                signal.symbol,
                signal.direction,
                signal.tradeTimeframe,
                signal.confidence,
                signal.entry,
                signal.stop,
                signal.tp1
            )
        }
        prefs.edit().putStringSet("active_signal_keys", active).apply()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "پایش بازار TENAXIS", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "وضعیت پایش پیوسته و تحلیل قراردادهای XT"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(signalChannelId, "سیگنال‌های جدید TENAXIS", NotificationManager.IMPORTANCE_DEFAULT).apply {
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
            .setContentTitle("TENAXIS • XT Futures Intelligence")
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
        positionMonitorJob?.cancel()
        serviceScope.cancel()
        voiceAnnouncer?.shutdown()
        voiceAnnouncer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.cryptoaipro.monitor.action.START"
        const val ACTION_STOP = "com.cryptoaipro.monitor.action.STOP"
    }
}