package com.cryptoaipro.monitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * On-device durable market research store.
 * Imports the verified 500k real-candle seed once, then appends live 15m candles
 * and signal snapshots. This is a data store, not a claim that the seed itself
 * retrains the bundled neural ensemble.
 */
internal object WolfMarketDataStore {
    data class Stats(
        val corpusRows: Int = 0,
        val liveCandles: Int = 0,
        val signalSnapshots: Int = 0,
        val imported: Boolean = false,
        val error: String? = null
    )

    private const val DB_NAME = "hope_hunter_market_research.db"
    private const val DB_VERSION = 1
    private const val EXPECTED_SEED_ROWS = 500_000

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE market_candles(
                    exchange TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    timeframe TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    open REAL NOT NULL,
                    high REAL NOT NULL,
                    low REAL NOT NULL,
                    close REAL NOT NULL,
                    volume REAL NOT NULL,
                    source TEXT NOT NULL,
                    PRIMARY KEY(exchange,symbol,timeframe,timestamp)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_candles_symbol_time ON market_candles(symbol,timeframe,timestamp)")
            db.execSQL("""
                CREATE TABLE signal_snapshots(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at INTEGER NOT NULL,
                    exchange TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    side TEXT NOT NULL,
                    price REAL NOT NULL,
                    confidence INTEGER NOT NULL,
                    neural_score INTEGER NOT NULL,
                    score INTEGER NOT NULL,
                    regime TEXT NOT NULL,
                    rsi REAL NOT NULL,
                    adx REAL NOT NULL,
                    atr_pct REAL NOT NULL,
                    reason TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_signal_time ON signal_snapshots(created_at)")
            db.execSQL("CREATE INDEX idx_signal_symbol ON signal_snapshots(symbol,created_at)")
            db.execSQL("CREATE TABLE store_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Schema changes must be additive; preserve historical observations.
        }
    }

    suspend fun importVerifiedSeed(context: Context): Stats = withContext(Dispatchers.IO) {
        val helper = Helper(context.applicationContext)
        try {
            val db = helper.writableDatabase
            val existing = count(db, "market_candles", "source='seed'")
            if (existing == EXPECTED_SEED_ROWS) return@withContext stats(db, true)
            require(existing == 0) { "پایگاه داده بذر ناقص است؛ برای جلوگیری از داده تکراری واردسازی متوقف شد." }

            val manifest = context.assets.open("wolf_corpus_manifest.json").bufferedReader().use { it.readText() }
            require(manifest.contains("\"status\":\"verified_real_exchange_data\"") ||
                manifest.contains("\"status\": \"verified_real_exchange_data\"")) {
                "اعتبارسنجی منشأ داده تاریخی ناموفق بود."
            }
            val input = BufferedReader(InputStreamReader(context.assets.open("market_seed.csv"), Charsets.UTF_8), 1024 * 1024)
            db.beginTransaction()
            var imported = 0
            try {
                val header = input.readLine() ?: error("فایل داده تاریخی خالی است.")
                require(header.trim() == "exchange,symbol,timeframe,timestamp,open,high,low,close,volume") {
                    "ساختار فایل داده تاریخی ناشناخته است."
                }
                var line: String?
                while (true) {
                    line = input.readLine() ?: break
                    val p = line!!.split(',')
                    if (p.size != 9) continue
                    val ts = p[3].toLongOrNull() ?: continue
                    val o = p[4].toDoubleOrNull() ?: continue
                    val h = p[5].toDoubleOrNull() ?: continue
                    val l = p[6].toDoubleOrNull() ?: continue
                    val c = p[7].toDoubleOrNull() ?: continue
                    val v = p[8].toDoubleOrNull() ?: continue
                    if (ts <= 0L || !listOf(o,h,l,c,v).all { it.isFinite() } ||
                        o <= 0.0 || h < maxOf(o,c) || l > minOf(o,c) || h < l || v < 0.0) continue
                    val values = ContentValues().apply {
                        put("exchange", p[0]); put("symbol", p[1]); put("timeframe", p[2])
                        put("timestamp", ts); put("open", o); put("high", h); put("low", l)
                        put("close", c); put("volume", v); put("source", "seed")
                    }
                    db.insertOrThrow("market_candles", null, values)
                    imported++
                }
                require(imported == EXPECTED_SEED_ROWS) {
                    "تعداد داده معتبر $imported است؛ انتظار $EXPECTED_SEED_ROWS ردیف داشتیم."
                }
                db.execSQL("INSERT OR REPLACE INTO store_meta(key,value) VALUES('seed_import_version','1')")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                input.close()
            }
            stats(db, true)
        } catch (e: Exception) {
            Stats(error = e.message ?: "خطای واردسازی پایگاه داده")
        } finally {
            helper.close()
        }
    }

    suspend fun recordLiveScan(context: Context, exchange: String, signals: List<MarketSignal>): Stats =
        withContext(Dispatchers.IO) {
            val helper = Helper(context.applicationContext)
            try {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    val now = System.currentTimeMillis()
                    signals.forEach { s ->
                        s.candles.forEach { candle ->
                            if (candle.size >= 6 && candle.take(6).all { it.isFinite() } &&
                                candle[5] > 0.0 && candle[0] > 0.0 && candle[1] >= maxOf(candle[0], candle[3]) &&
                                candle[2] <= minOf(candle[0], candle[3]) && candle[1] >= candle[2] && candle[4] >= 0.0) {
                                val values = ContentValues().apply {
                                    put("exchange", exchange); put("symbol", s.symbol); put("timeframe", "15m")
                                    put("timestamp", candle[5].toLong()); put("open", candle[0]); put("high", candle[1])
                                    put("low", candle[2]); put("close", candle[3]); put("volume", candle[4])
                                    put("source", "live")
                                }
                                db.insertWithOnConflict("market_candles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                            }
                        }
                        val t = s.technical
                        val row = ContentValues().apply {
                            put("created_at", now); put("exchange", exchange); put("symbol", s.symbol)
                            put("side", s.direction); put("price", s.price); put("confidence", s.confidence)
                            put("neural_score", s.neuralScore); put("score", s.score); put("regime", t.regime)
                            put("rsi", s.rsi); put("adx", t.adx); put("atr_pct", t.atrPercent)
                            put("reason", s.reason.take(1800))
                        }
                        db.insert("signal_snapshots", null, row)
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                stats(db, count(db, "market_candles", "source='seed'") == EXPECTED_SEED_ROWS)
            } catch (e: Exception) {
                Stats(error = e.message ?: "خطای ذخیره داده زنده")
            } finally { helper.close() }
        }

    suspend fun currentStats(context: Context): Stats = withContext(Dispatchers.IO) {
        val helper = Helper(context.applicationContext)
        try {
            val db = helper.readableDatabase
            stats(db, count(db, "market_candles", "source='seed'") == EXPECTED_SEED_ROWS)
        } finally { helper.close() }
    }

    private fun stats(db: SQLiteDatabase, imported: Boolean): Stats = Stats(
        corpusRows = count(db, "market_candles", "source='seed'"),
        liveCandles = count(db, "market_candles", "source='live'"),
        signalSnapshots = count(db, "signal_snapshots"),
        imported = imported
    )

    private fun count(db: SQLiteDatabase, table: String, where: String = "1=1"): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
