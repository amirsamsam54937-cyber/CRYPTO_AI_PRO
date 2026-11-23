package com.cryptoaipro.monitor

/**
 * PROJECT MIRROR strategy knowledge base.
 *
 * These are research hypotheses distilled from public trading concepts and trader case studies,
 * not proven profitable strategies. This catalogue has NO order-execution authority and is
 * deliberately not wired into XtLiveTradingEngine. Each pattern must pass StrategyLab
 * out-of-sample tests with XT fees, funding, spread and slippage before promotion.
 */
internal object ZoroStrategyLibrary {
    enum class Family { TREND, BREAKOUT, RETEST, MEAN_REVERSION, PRICE_ACTION, LIQUIDITY, VOLATILITY, EVENT }
    enum class Status { RESEARCH_ONLY, NEEDS_DATA, READY_FOR_BACKTEST }

    data class Pattern(
        val id: String,
        val name: String,
        val family: Family,
        val thesis: String,
        val requiredEvidence: List<String>,
        val invalidation: List<String>,
        val hiddenFailureModes: List<String>,
        val status: Status = Status.RESEARCH_ONLY
    )

    val patterns: List<Pattern> = listOf(
        Pattern(
            "HTF_TREND_PULLBACK", "روند چندتایم‌فریمی و پولبک", Family.TREND,
            "جهت 1h/4h تعیین‌کننده سوگیری است؛ ورود فقط پس از پولبک و تأیید ساختار در تایم پایین‌تر.",
            listOf("جهت ساختار 4h و 1h", "شیب/تداوم روند", "پولبک به سطح شکسته‌شده یا ناحیه ساختاری", "تأیید بسته‌شدن کندل"),
            listOf("تغییر ساختار تایم بالاتر", "ضعف مومنتوم", "نسبت سود به زیان زیر حداقل"),
            listOf("تاخیر ورود و تعقیب قیمت", "رنج طولانی که سیگنال روند را فرسوده می‌کند", "همبستگی پوزیشن‌های هم‌جهت")
        ),
        Pattern(
            "CHANNEL_BREAKOUT", "شکست کانال/سقف‌کف", Family.BREAKOUT,
            "شکست محدوده چنددوره‌ای فقط با تثبیت یا تأیید مشارکت بازار بررسی می‌شود.",
            listOf("سطح کانال از داده گذشته", "بسته‌شدن بیرون محدوده", "حجم نسبی یا مومنتوم", "نقدشوندگی کافی"),
            listOf("بازگشت سریع به داخل کانال", "شکست بدون مشارکت", "اسپرد/اسلیپیج غیرعادی"),
            listOf("شکست جعلی", "look-ahead در تعیین سقف/کف", "ورود روی کندل توسعه‌یافته")
        ),
        Pattern(
            "FAILED_BREAKOUT", "شکست ناموفق و بازپس‌گیری سطح", Family.PRICE_ACTION,
            "وقتی قیمت از سطح عبور می‌کند اما دوباره آن را پس می‌گیرد، برگشت فقط پس از تأیید بررسی می‌شود.",
            listOf("سطح از پیش ثبت‌شده", "نفوذ و بازگشت قیمت", "کلوز دوباره در محدوده", "حد ابطال روشن"),
            listOf("تثبیت واقعی بیرون سطح", "خبر/شوک نوسانی", "ریسک نامتناسب"),
            listOf("تعریف مبهم سطح", "ورود زودهنگام قبل از بازپس‌گیری", "سوگیری انتخاب نمونه")
        ),
        Pattern(
            "RETEST_CONTINUATION", "شکست، تثبیت و Retest", Family.RETEST,
            "به‌جای تعقیب شکست، بازآزمایی سطح و واکنش قیمت برای ورود ارزیابی می‌شود.",
            listOf("شکست معتبر", "تثبیت بیرون سطح", "بازآزمایی و رد سطح", "ساختار همسو"),
            listOf("بازگشت و تثبیت داخل محدوده", "تأیید نشدن واکنش", "ورود دیرهنگام"),
            listOf("از دست رفتن حرکت بدون retest", "سطح بیش از حد بهینه‌سازی‌شده", "fill بد در بازار سریع")
        ),
        Pattern(
            "RANGE_MEAN_REVERSION", "بازگشت به میانگین در بازار رنج", Family.MEAN_REVERSION,
            "در رژیم خنثی، افراط قیمت نسبت به محدوده/میانگین فقط با نشانه برگشت بررسی می‌شود.",
            listOf("تشخیص رنج بدون نگاه به آینده", "موقعیت نسبت به مرز/میانگین", "تأیید برگشت", "هدف داخل محدوده"),
            listOf("شروع روند جدید", "گسترش دامنه و افزایش ADX", "شکست مرز رنج"),
            listOf("گرفتن چاقوی در حال سقوط", "رنج کاذب", "هزینه معامله در هدف‌های کوچک")
        ),
        Pattern(
            "SUPPLY_DEMAND_REACTION", "واکنش ناحیه عرضه/تقاضا", Family.PRICE_ACTION,
            "ناحیه فقط وقتی معتبر است که قبل از واکنش تعریف شده و واکنش بعدی قابل اندازه‌گیری باشد.",
            listOf("ناحیه از داده پیشین", "تازگی/تعداد برخورد", "واکنش و جابه‌جایی قیمت", "نقطه ابطال"),
            listOf("مصرف‌شدن ناحیه", "عبور و تثبیت", "نبود فضای کافی تا هدف"),
            listOf("رسم ناحیه پس از حرکت", "ذهنی بودن مرزها", "ابهام Order Block/FVG")
        ),
        Pattern(
            "LIQUIDITY_SWEEP", "جمع‌آوری نقدینگی و بازگشت", Family.LIQUIDITY,
            "عبور کوتاه از سقف/کف قابل مشاهده و بازگشت سریع، فرضیه برگشت است؛ نه اثبات سفارشات پنهان.",
            listOf("سقف/کف قبلی ثبت‌شده", "نفوذ و بازگشت", "تأیید کندلی", "داده حجم/دفتر سفارش در صورت دسترسی"),
            listOf("تثبیت آن‌سوی سطح", "حرکت خبری", "نبود نقدشوندگی"),
            listOf("ادعای order flow از روی OHLC", "اشتباه گرفتن شکست واقعی با sweep", "اسلیپیج")
        ),
        Pattern(
            "VOLUME_IMBALANCE", "عدم‌تعادل حجم/جریان سفارش", Family.LIQUIDITY,
            "فشار خرید/فروش فقط با داده واقعی معاملات یا دفتر سفارش سنجیده می‌شود.",
            listOf("معاملات تیک یا order book معتبر", "هم‌زمانی داده‌ها", "جهت و شدت عدم‌تعادل", "کیفیت و پوشش داده"),
            listOf("داده ناقص/تأخیری", "لغو سفارشات نمایشی", "تغییر رژیم"),
            listOf("استنتاج جریان سفارش از کندل", "spoofing و liquidity mirage", "اختلاف داده بین صرافی‌ها")
        ),
        Pattern(
            "VOLATILITY_EXPANSION", "انبساط نوسان", Family.VOLATILITY,
            "افزایش دامنه پس از فشردگی فقط با جهت و حد ابطال مشخص ارزیابی می‌شود.",
            listOf("فشردگی نوسان گذشته", "شکست محدوده", "ATR و حجم نسبی", "هزینه اجرا"),
            listOf("بازگشت به فشردگی", "جهت نامشخص", "نوسان بیش از ظرفیت حدضرر"),
            listOf("ورود در انتهای جهش", "گسترش اسپرد", "لیکوییدشدن در نوسان ناگهانی")
        ),
        Pattern(
            "SESSION_OPENING_RANGE", "محدوده آغاز نشست", Family.BREAKOUT,
            "شکست محدوده آغاز نشست در زمان و بازار مشخص آزموده می‌شود.",
            listOf("تعریف timezone و نشست", "سقف/کف بازه آغازین", "کلوز و تأیید شکست", "تقویم رویدادها"),
            listOf("بازگشت به محدوده", "خبر مهم", "حجم ناکافی"),
            listOf("خطای timezone/DST", "تفاوت سشن فارکس و کریپتو 24/7", "نمونه‌گیری انتخابی")
        ),
        Pattern(
            "EVENT_VOLATILITY_FILTER", "فیلتر رویداد و خبر", Family.EVENT,
            "در زمان خبرهای پرریسک، ورود محدود یا متوقف می‌شود مگر اینکه استراتژی خبر جداگانه آزموده شده باشد.",
            listOf("تقویم زمان‌دار و منبع معتبر", "زمان انتشار و timezone", "برآورد spread/volatility"),
            listOf("خبر ناگهانی", "داده تقویم ناقص", "لغزش شدید"),
            listOf("تأخیر خبر", "تغییرات لحظه‌ای اسپرد", "پرشدن بد حدضرر")
        ),
        Pattern(
            "CROSS_MARKET_CONFIRMATION", "تأیید بین‌بازاری و همبستگی", Family.TREND,
            "همبستگی BTC، شاخص دلار، طلا و دارایی‌های مرتبط فقط به‌عنوان فیلتر رژیم ارزیابی می‌شود.",
            listOf("سری زمانی هم‌زمان", "همبستگی rolling", "آزمون پایداری رابطه", "کنترل exposure مشترک"),
            listOf("شکست رابطه", "تغییر رژیم", "داده ناهم‌زمان"),
            listOf("همبستگی کاذب", "نگاه به آینده در هم‌ترازی", "شمارش دوباره ریسک هم‌جهت")
        )
    )

    /** A pattern is not eligible for any live order through this library. */
    fun researchSummary(): String =
        "${patterns.size} فرضیه تحقیقاتی؛ همه فاقد اختیار اجرای زنده و نیازمند بک‌تست خارج از نمونه با هزینه XT هستند."
}
