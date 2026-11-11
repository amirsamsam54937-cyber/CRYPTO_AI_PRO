package com.cryptoaipro.monitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject

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
    private const val MINIMUM_SEED_ROWS = 1_000_000

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
            val manifestText = context.assets.open("wolf_corpus_manifest.json").bufferedReader().use { it.readText() }
            val manifest = JSONObject(manifestText)
            require(manifest.optString("status") == "verified_real_exchange_data") {
                "اعتبارسنجی منشأ داده تاریخی ناموفق بود."
            }
            val sources = manifest.optJSONArray("source_exchanges") ?: error("فهرست منابع داده موجود نیست.")
            require(sources.length() == 1 && sources.optString(0).equals("XT", ignoreCase = true) &&
                manifest.optString("data_source_policy") == "XT_USDT_M_ONLY" &&
                manifest.optInt("synthetic_rows", -1) == 0) {
                "فقط داده واقعی قراردادهای XT USDT-M مجاز است."
            }
            val expectedRows = manifest.optInt("rows", 0)
            require(expectedRows >= MINIMUM_SEED_ROWS) {
                "داده تاریخی باید حداقل یک میلیون کندل واقعی داشته باشد."
            }
            val existing = count(db, "market_candles", "source='seed'")
            if (existing == expectedRows) return@withContext stats(db, true)
            require(existing <= expectedRows) { "نسخه پایگاه داده از بذر جدید بزرگ‌تر است؛ واردسازی امن متوقف شد." }

            val input = BufferedReader(InputStreamReader(context.assets.open("market_seed.csv"), Charsets.UTF_8), 1024 * 1024)
            db.beginTransaction()
            var imported = 0
            try {
                // Atomic seed refresh: preserve live observations and roll back old seed if parsing fails.
                if (existing > 0) db.delete("market_candles", "source='seed'", null)
                val header = input.readLine() ?: error("فایل داده تاریخی خالی است.")
                require(header.trim() == "exchange,symbol,timeframe,timestamp,open,high,low,close,volume") {
                    "ساختار فایل داده تاریخی ناشناخته است."
                }
                var line: String?
                while (true) {
                    line = input.readLine() ?: break
                    val p = line!!.split(',')
                    if (p.size != 9) continue
                    require(p[0].equals("XT", ignoreCase = true)) { "ردیف غیر XT در فایل داده تاریخی شناسایی شد." }
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
                require(imported == expectedRows) {
                    "تعداد داده معتبر $imported است؛ انتظار $expectedRows ردیف داشتیم."
                }
                db.execSQL("INSERT OR REPLACE INTO store_meta(key,value) VALUES('seed_import_version','2')")
                db.execSQL("INSERT OR REPLACE INTO store_meta(key,value) VALUES('seed_expected_rows','$expectedRows')")
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
                stats(db, expectedSeedRows(db) > 0 && count(db, "market_candles", "source='seed'") == expectedSeedRows(db))
            } catch (e: Exception) {
                Stats(error = e.message ?: "خطای ذخیره داده زنده")
            } finally { helper.close() }
        }

    suspend fun currentStats(context: Context): Stats = withContext(Dispatchers.IO) {
        val helper = Helper(context.applicationContext)
        try {
            val db = helper.readableDatabase
            stats(db, expectedSeedRows(db) > 0 && count(db, "market_candles", "source='seed'") == expectedSeedRows(db))
        } finally { helper.close() }
    }

    private fun expectedSeedRows(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT value FROM store_meta WHERE key='seed_expected_rows'", null).use {
            if (it.moveToFirst()) it.getString(0).toIntOrNull() ?: 0 else 0
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
