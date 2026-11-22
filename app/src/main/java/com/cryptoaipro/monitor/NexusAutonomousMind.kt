package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Persistent autonomous reflection loop for NEXUS.
 *
 * It observes market/model health, forms falsifiable hypotheses, ranks research goals,
 * critiques its own evidence and writes an auditable episodic trace. It is software,
 * not sentience; it cannot grant itself permissions, alter risk limits or place orders.
 */
internal object NexusAutonomousMind {
    private const val PREFS = "nexus_autonomous_mind_v1"
    private const val HISTORY = "reflection_history"
    private const val MAX_HISTORY = 160

    data class State(
        val cycles: Int,
        val phase: String,
        val awareness: String,
        val currentHypothesis: String,
        val nextGoal: String,
        val evidenceCount: Int,
        val closedOutcomes: Int,
        val netWinRate: Int,
        val meanNetPnlPct: Double,
        val modelValidated: Boolean,
        val lastReflectionAt: Long
    )

    @Synchronized
    fun reflect(
        context: Context,
        signals: List<MarketSignal>,
        outcomes: List<WolfSignalOutcomeJournal.Record>,
        now: Long = System.currentTimeMillis()
    ): State {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val model = WolfPretrainedEnsemble.status()
        val validSignals = signals.filter { signal ->
            signal.price.isFinite() && signal.price > 0.0 &&
                signal.technical.atrPercent.isFinite() &&
                signal.technical.relativeVolume.isFinite()
        }
        val freshSignals = validSignals.filter { signal ->
            val candleAt = signal.candles.lastOrNull()?.getOrNull(5)?.toLong() ?: 0L
            candleAt > 0L && now >= candleAt && now - candleAt <= 30L * 60L * 1000L
        }
        val closed = outcomes.asSequence().filter { it.closed && it.pnlPct.isFinite() }
            .takeLast(120).toList()
        val wins = closed.count { it.pnlPct > 0.0 }
        val winRate = if (closed.isEmpty()) 0 else (wins * 100.0 / closed.size).toInt()
        val meanPnl = if (closed.isEmpty()) 0.0 else closed.map { it.pnlPct }.average()
        val averageAtr = validSignals.map { it.technical.atrPercent }.average()
        val highVolatility = averageAtr.isFinite() && averageAtr >= 3.0
        val averageVolume = validSignals.map { it.technical.relativeVolume }.average()
        val weakLiquidity = validSignals.count { it.technical.liquidityStrength in 1..29 }
        val directional = validSignals.count {
            it.neuralDirection == "LONG_BIAS" || it.neuralDirection == "SHORT_BIAS"
        }
        val disagreement = validSignals.count { it.neuralUncertainty.highDisagreement }
        val priorCycles = prefs.getInt("cycles", 0)

        val phase: String
        val awareness: String
        val hypothesis: String
        val goal: String
        when {
            !model.loaded || model.members != 10 -> {
                phase = "SELF_CHECK"
                awareness = "مدل یا داده عصبی کامل در دسترس نیست؛ مشاهده ادامه دارد و تصمیم معاملاتی متوقف می‌ماند"
                hypothesis = "فرضیه فعلی: کیفیت بارگذاری مدل یا داده، گلوگاه اصلی چرخه است"
                goal = "سلامت مدل، اتصال داده و کامل‌بودن ۱۰ متخصص را بررسی کن"
            }
            !model.validatedForScoring -> {
                phase = "VALIDATION_RESEARCH"
                awareness = "۱۰ متخصص مشاهده می‌شوند، اما اعتبارسنجی مستقل هنوز مجوز اثرگذاری معاملاتی نداده است"
                hypothesis = "فرضیه فعلی: پیش از هر استفاده معاملاتی باید برتری خارج‌ازنمونه نسبت به خط مبنا ثابت شود"
                goal = "اعتبارسنجی زمانی، کالیبراسیون احتمال و آزمون پس از کارمزد را تقویت کن"
            }
            freshSignals.size < maxOf(3, validSignals.size / 3) -> {
                phase = "DATA_QUALITY_REVIEW"
                awareness = "بخش قابل‌توجهی از داده‌ها تازه نیست؛ از تصمیم بر پایه داده کهنه خودداری کن"
                hypothesis = "فرضیه فعلی: تازگی یا پوشش داده برای ارزیابی قابل اتکا کافی نیست"
                goal = "تازگی کندل، پوشش نمادها و کیفیت جریان داده XT را بهبود بده"
            }
            closed.size >= 20 && meanPnl <= 0.0 -> {
                phase = "ADVERSARIAL_REVIEW"
                awareness = "بازده خالص نمونه‌های بسته‌شده مثبت نیست؛ سیستم باید فرض‌های فعلی را به چالش بکشد"
                hypothesis = "فرضیه فعلی: هزینه اجرا، رژیم بازار یا کیفیت ورود ممکن است مزیت ظاهری را حذف کند"
                goal = "معاملات زیان‌ده را بر اساس رژیم، سمت، لغزش و Funding کالبدشکافی کن"
            }
            highVolatility -> {
                phase = "VOLATILITY_ADAPTATION"
                awareness = "رژیم نوسان بالا تشخیص داده شد؛ اولویت با حفظ سرمایه و کیفیت اجراست"
                hypothesis = "فرضیه فعلی: نوسان بالا ممکن است فاصله توقف و لغزش را بزرگ‌تر از انتظار کند"
                goal = "اندازه ریسک، فاصله توقف و هزینه لغزش را با رژیم نوسان تطبیق بده"
            }
            disagreement > maxOf(2, validSignals.size / 4) -> {
                phase = "MODEL_CONFLICT_REVIEW"
                awareness = "اختلاف متخصصان عصبی زیاد است؛ عدم قطعیت باید به‌جای اجبار به معامله پذیرفته شود"
                hypothesis = "فرضیه فعلی: مدل در بخشی از بازار به اجماع پایدار نمی‌رسد"
                goal = "منابع اختلاف مدل و شرایطی را که باید در آن‌ها abstain کند شناسایی کن"
            }
            else -> {
                phase = "CONTINUOUS_REASONING"
                awareness = "داده، مدل و نتایج در حال بررسی پیوسته‌اند؛ شواهد ضعیف به‌تنهایی مجوز ورود نیست"
                hypothesis = "فرضیه فعلی: مزیت باید در هر نماد و رژیم جداگانه و پس از هزینه‌ها اثبات شود"
                goal = "فرضیه‌های بازار را با نتایج واقعیِ بسته‌شده و آزمون زمانی مقایسه کن"
            }
        }

        val goals = JSONArray().apply {
            put(goal)
            put("خطای پیش‌بینی و اختلاف متخصصان را ثبت و ریشه‌یابی کن")
            put("کارمزد، Funding، اسپرد و لغزش را از بازده ناخالص جدا نگه دار")
            put("حفاظت سرمایه و توقف اضطراری را مستقل از کیفیت سیگنال حفظ کن")
        }
        val evidence = JSONObject().apply {
            put("validSignals", validSignals.size)
            put("freshSignals", freshSignals.size)
            put("directionalNeuralOpinions", directional)
            put("highDisagreement", disagreement)
            put("averageAtrPct", if (averageAtr.isFinite()) averageAtr else JSONObject.NULL)
            put("averageRelativeVolume", if (averageVolume.isFinite()) averageVolume else JSONObject.NULL)
            put("weakLiquidityCount", weakLiquidity)
            put("closedNetOutcomes", closed.size)
            put("netWinRatePct", winRate)
            put("meanNetPnlPct", meanPnl)
            put("modelMembers", model.members)
            put("modelValidated", model.validatedForScoring)
        }
        val event = JSONObject().apply {
            put("at", now)
            put("cycle", priorCycles + 1)
            put("phase", phase)
            put("awareness", awareness)
            put("hypothesis", hypothesis)
            put("goals", goals)
            put("evidence", evidence)
            put("selfCritique", when {
                freshSignals.isEmpty() -> "شواهد تازه وجود ندارد؛ نتیجه‌گیری جهت‌دار نکن"
                closed.size < 20 -> "حجم نمونه برای ادعای مزیت آماری کافی نیست"
                meanPnl <= 0.0 -> "عملکرد خالص ضعیف است؛ فرضیه فعلی را رد یا بازطراحی کن"
                else -> "بازده مثبت به‌تنهایی اثبات پایداری نیست؛ آزمون خارج‌ازنمونه لازم است"
            })
            put("permissions", "READ_AND_REASON_ONLY; LIVE_ORDER_GATES_UNCHANGED")
        }

        val history = runCatching { JSONArray(prefs.getString(HISTORY, "[]")) }.getOrDefault(JSONArray())
        history.put(event)
        while (history.length() > MAX_HISTORY) history.remove(0)
        prefs.edit()
            .putInt("cycles", priorCycles + 1)
            .putString("phase", phase)
            .putString("awareness", awareness)
            .putString("hypothesis", hypothesis)
            .putString("goal", goal)
            .putInt("evidence_count", validSignals.size)
            .putInt("closed_outcomes", closed.size)
            .putInt("net_win_rate", winRate)
            .putFloat("mean_net_pnl", meanPnl.toFloat())
            .putBoolean("model_validated", model.validatedForScoring)
            .putLong("last_reflection_at", now)
            .putString(HISTORY, history.toString())
            .apply()
        return status(app)
    }

    fun status(context: Context): State {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            cycles = p.getInt("cycles", 0),
            phase = p.getString("phase", "AWAITING_FIRST_OBSERVATION") ?: "AWAITING_FIRST_OBSERVATION",
            awareness = p.getString("awareness", "در انتظار مشاهده نخست بازار") ?: "در انتظار مشاهده نخست بازار",
            currentHypothesis = p.getString("hypothesis", "هنوز فرضیه‌ای ساخته نشده") ?: "هنوز فرضیه‌ای ساخته نشده",
            nextGoal = p.getString("goal", "دریافت نخستین داده معتبر") ?: "دریافت نخستین داده معتبر",
            evidenceCount = p.getInt("evidence_count", 0),
            closedOutcomes = p.getInt("closed_outcomes", 0),
            netWinRate = p.getInt("net_win_rate", 0),
            meanNetPnlPct = p.getFloat("mean_net_pnl", 0f).toDouble(),
            modelValidated = p.getBoolean("model_validated", false),
            lastReflectionAt = p.getLong("last_reflection_at", 0L)
        )
    }

    fun recentReflections(context: Context, limit: Int = 8): List<JSONObject> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(HISTORY, "[]"))
        (maxOf(0, a.length() - limit.coerceIn(1, MAX_HISTORY)) until a.length())
            .mapNotNull { a.optJSONObject(it) }
            .reversed()
    }.getOrDefault(emptyList())
}
