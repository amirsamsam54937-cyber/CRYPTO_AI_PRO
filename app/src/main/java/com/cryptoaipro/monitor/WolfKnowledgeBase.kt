package com.cryptoaipro.monitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

private data class WolfLesson(val group:String,val title:String,val body:String,val rule:String)
private val wolfLessons = listOf(
 WolfLesson("ساختار","BOS شکست ساختار","عبور و بسته‌شدن قیمت آن‌سوی swing مهم در جهت روند؛ حجم و displacement کیفیت شکست را بهتر می‌کنند.","سایه تنها را شکست قطعی ندان."),
 WolfLesson("ساختار","CHOCH تغییر کاراکتر","اولین شکست معتبر خلاف توالی روند قبلی؛ هشدار تغییر احتمالی است نه تضمین برگشت.","برای ورود retest و تأیید تایم بالاتر بخواه."),
 WolfLesson("ساختار","HH و HL / LH و LL","توالی سقف و کف بالاتر روند صعودی و توالی سقف و کف پایین‌تر روند نزولی را تعریف می‌کند.","در رنج، از تحمیل روند خودداری کن."),
 WolfLesson("ساختار","Premium و Discount","نیمه بالایی و پایینی swing مرجع برای مکان‌یابی قیمت؛ به‌تنهایی سیگنال ورود نیست.","swing مرجع را از تایم‌فریم بالاتر بگیر."),
 WolfLesson("نقدینگی","Liquidity Sweep","نفوذ از سقف/کف مهم و بازگشت و بسته‌شدن داخل محدوده؛ رد قیمت باید تأیید شود.","پس از sweep شکست ساختار کوچک‌تر را بررسی کن."),
 WolfLesson("نقدینگی","Equal Highs / Equal Lows","سقف‌ها و کف‌های نزدیک می‌توانند محل تجمع حدضررها باشند.","وجود سطح برابر، وقوع sweep را قطعی نمی‌کند."),
 WolfLesson("نقدینگی","Buy-side و Sell-side","نقدینگی بالقوه بالای سقف‌ها و پایین کف‌های مهم قرار می‌گیرد.","این نقشه جای داده واقعی دفتر سفارش نیست."),
 WolfLesson("پرایس اکشن","Order Block","آخرین کندل مخالف پیش از حرکت جابه‌جایی قوی؛ اعتبار آن به شکست ساختار و واکنش بعدی وابسته است.","هر کندل مخالف یک OB معتبر نیست."),
 WolfLesson("پرایس اکشن","Fair Value Gap","ناهمپوشانی سه کندل که عدم تعادل حرکت را نشان می‌دهد؛ الزاماً کامل پر نمی‌شود.","FVG خلاف روند یا در بازار کم‌حجم ضعیف‌تر است."),
 WolfLesson("پرایس اکشن","Displacement","حرکت جهت‌دار با بدنه‌های بزرگ و همپوشانی کم که می‌تواند نشانه فشار سفارش باشد.","حرکت شارپ را دور از حدضرر تعقیب نکن."),
 WolfLesson("پرایس اکشن","Breaker و Mitigation","ناحیه شکسته‌شده ممکن است نقش معکوس بگیرد؛ بازگشت برای معامله مجدد سفارش‌ها بررسی می‌شود.","صرف لمس ناحیه تأیید ورود نیست."),
 WolfLesson("کندل","Engulfing","بدنه کندل جاری بدنه قبلی را می‌پوشاند؛ در سطح کلیدی معنادارتر است.","بدون زمینه روند، قدرت مستقل ندارد."),
 WolfLesson("کندل","Pin Bar و Rejection","سایه بلند نشانه رد قیمت است؛ محل تشکیل از شکل ظاهری مهم‌تر است.","در میانه محدوده بی‌ساختار ضعیف است."),
 WolfLesson("کندل","Inside Bar و Doji","Inside bar فشردگی و Doji تردید را نشان می‌دهد؛ هیچ‌کدام جهت را تضمین نمی‌کنند.","جهت شکست را پس از بسته‌شدن ارزیابی کن."),
 WolfLesson("اندیکاتور","RSI","مومنتوم نسبی بین صفر و صد؛ در روند قوی می‌تواند طولانی اشباع بماند.","اشباع خرید دستور فروش نیست."),
 WolfLesson("اندیکاتور","MACD","اختلاف EMA سریع و کند و خط سیگنال؛ تقاطع و واگرایی مومنتوم را می‌سنجند.","در بازار رنج سیگنال کاذب بیشتر است."),
 WolfLesson("اندیکاتور","EMA 9/20/50/200","ترتیب و شیب میانگین‌ها جهت و پایداری روند را توصیف می‌کند.","کراس دیرهنگام است؛ تنها محرک ورود نباشد."),
 WolfLesson("اندیکاتور","ADX و DI","ADX قدرت روند و DI جهت فشار را نشان می‌دهد.","ADX به‌تنهایی جهت ندارد."),
 WolfLesson("اندیکاتور","Bollinger Bands","باندهای انحراف معیار؛ فشردگی می‌تواند پیش‌درآمد گسترش نوسان باشد.","برخورد باند الزاماً برگشت نیست."),
 WolfLesson("اندیکاتور","ATR","میانگین دامنه واقعی برای مقیاس‌کردن نوسان و حدضرر.","ATR جهت قیمت را پیش‌بینی نمی‌کند."),
 WolfLesson("اندیکاتور","VWAP","میانگین قیمت وزنی حجم برای سنجش قیمت نسبت به تعادل جلسه.","داده ناقص یا session نامناسب گمراه‌کننده است."),
 WolfLesson("اندیکاتور","OBV و MFI","حجم تجمعی و جریان پول برای بررسی تأیید یا واگرایی حرکت.","کیفیت حجم صرافی را لحاظ کن."),
 WolfLesson("اندیکاتور","Stochastic RSI و CCI","اسیلاتورهای حساس برای سنجش موقعیت مومنتوم و انحراف از میانگین.","آستانه ثابت برای همه رژیم‌ها مناسب نیست."),
 WolfLesson("اندیکاتور","Ichimoku","ابر، تنکان و کیجون روند و تعادل پویا را نشان می‌دهند.","اجزای همبسته را دوباره‌شماری نکن."),
 WolfLesson("اندیکاتور","Fibonacci","سطوح اصلاحی از swing مشخص برای نقشه واکنش احتمالی.","انتخاب swing سلیقه‌ای نتیجه را تغییر می‌دهد."),
 WolfLesson("واگرایی","واگرایی معمولی","قیمت سقف/کف جدید می‌سازد اما اسیلاتور آن را تأیید نمی‌کند.","برای زمان‌بندی ورود تأیید قیمت لازم است."),
 WolfLesson("واگرایی","واگرایی مخفی","تفاوت pivot قیمت و اسیلاتور در اصلاح می‌تواند ادامه روند را پشتیبانی کند.","تعریف pivot و فاصله زمانی را ثابت نگه دار."),
 WolfLesson("ریسک","Position Sizing","حجم موقعیت = مقدار ریسک مجاز تقسیم بر فاصله ورود تا حدضرر؛ هزینه اجرا نیز افزوده شود.","اهرم، ریسک دلاری را حذف نمی‌کند."),
 WolfLesson("ریسک","Risk Reward و Expectancy","نسبت سود به زیان را همراه نرخ برد و میانگین برد/باخت بسنج.","R:R بالا بدون احتمال موفقیت کافی نیست."),
 WolfLesson("ریسک","Drawdown و Kelly","افت سرمایه و سهم نظری Kelly باید با محدودیت محافظه‌کارانه کنترل شوند.","تخمین احتمال خطا دارد؛ از Kelly کامل پرهیز کن."),
 WolfLesson("ریسک","Funding و Open Interest","فاندینگ هزینه/ازدحام سمت قرارداد و OI تعداد موقعیت‌های باز را نشان می‌دهد.","افزایش OI یا فاندینگ به‌تنهایی جهت نیست."),
 WolfLesson("ریسک","همبستگی و Portfolio Heat","چند آلت‌کوین ممکن است در بحران یک ریسک مشترک داشته باشند.","ریسک همبسته را جمع تعداد معاملات فرض نکن."),
 WolfLesson("رژیم","Trend / Range / Volatility","رفتار روندی، رنج و نوسان بالا به قواعد متفاوت نیاز دارند.","یک استراتژی را به همه رژیم‌ها تحمیل نکن."),
 WolfLesson("چندتایم‌فریم","Top-down Analysis","ابتدا روند و سطوح تایم بالا، سپس ساختار و ورود در تایم پایین.","تعارض تایم‌فریم‌ها باید امتیاز را کم کند."),
 WolfLesson("چندتایم‌فریم","Confluence","هم‌جهتی ساختار، مومنتوم، حجم و سطح شواهد را تقویت می‌کند.","اندیکاتورهای همبسته را مستقل نشمار."),
 WolfLesson("بک‌تست","Look-ahead و Leakage","استفاده از آینده در ویژگی یا برچسب، نتیجه تاریخی را غیرواقعی می‌کند.","هر ویژگی فقط باید از اطلاعات زمان تصمیم استفاده کند."),
 WolfLesson("بک‌تست","Survivorship Bias","حذف نمادهای حذف‌شده از تاریخچه نتیجه را خوش‌بینانه می‌کند.","فهرست تاریخی قراردادها را نگه دار."),
 WolfLesson("بک‌تست","Walk-forward","آموزش روی گذشته و آزمون روی پنجره زمانی بعدی؛ مناسب‌تر از تقسیم تصادفی سری زمانی.","پنجره آزمون را در تنظیم مدل دخالت نده."),
 WolfLesson("بک‌تست","Fees و Slippage و Funding","هزینه معامله و فاندینگ می‌تواند مزیت ظاهری را از بین ببرد.","هزینه واقعی هر صرافی را مدل کن."),
 WolfLesson("هوش مصنوعی","Ensemble","ترکیب مدل‌های متفاوت با وزن مبتنی بر عملکرد خارج از نمونه می‌تواند پایداری را بهتر کند.","مدل‌های مشابه را مستقل فرض نکن."),
 WolfLesson("هوش مصنوعی","Calibration","احتمال اعلامی باید با فراوانی واقعی رخدادها همخوان باشد.","Accuracy به‌تنهایی کیفیت احتمال را نمی‌سنجد."),
 WolfLesson("هوش مصنوعی","Concept Drift","رابطه ویژگی‌ها و نتیجه با تغییر رژیم بازار عوض می‌شود.","عملکرد را پنجره‌ای پایش کن."),
 WolfLesson("هوش مصنوعی","No-trade","وقتی اجماع، نقدشوندگی یا نسبت سود به زیان کافی نیست، عدم سیگنال تصمیم معتبر است.","سیستم را مجبور به LONG یا SHORT نکن.")
)

@Composable
internal fun WolfKnowledgeBaseScreen() {
 var query by remember { mutableStateOf("") }
 var category by remember { mutableStateOf("همه") }
 val categories = listOf("همه") + wolfLessons.map { it.group }.distinct()
 val filtered = wolfLessons.filter { (category=="همه" || it.group==category) &&
  (query.isBlank() || it.title.contains(query,true) || it.body.contains(query,true) || it.rule.contains(query,true) || it.group.contains(query,true)) }
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=14.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
  item {
   Surface(color=Color(0xFF20272B),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()) {
    Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
     Text("کتابخانه دانش تحلیلی گرگ",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
     Text("دانش ساختاریافته آفلاین • ۴۰+ مبحث تخصصی",color=Color(0xFFE1B86B),fontSize=10.sp)
     Text("این دانش برای توضیح و فیلتر تحلیل است؛ جای داده زنده، بک‌تست و اعتبارسنجی را نمی‌گیرد.",color=Color(0xFFAEB7BC),fontSize=10.sp)
     BasicTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,decorationBox={inner->
      Surface(color=Color(0xFF101518),shape=RoundedCornerShape(10.dp)){Box(Modifier.padding(12.dp)){if(query.isEmpty())Text("جست‌وجوی اندیکاتور، الگو یا مفهوم...",color=Color.Gray,fontSize=11.sp);inner()}}
     })
     categories.chunked(3).forEach { group -> Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
      group.forEach { c -> FilterChip(selected=category==c,onClick={category=c},label={Text(c,fontSize=9.sp)}) }
     } }
     Text(filtered.size.toString()+" نتیجه از "+wolfLessons.size+" درس",color=Color(0xFFB9D69A),fontSize=10.sp)
    }
   }
  }
  items(filtered) { lesson ->
   Surface(color=Color(0xFF171B1E),shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
     Text(lesson.group,color=Color(0xFFE1B86B),fontSize=9.sp)
     Text(lesson.title,color=Color.White,fontSize=14.sp,fontWeight=FontWeight.Bold)
     Text(lesson.body,color=Color(0xFFD0D6D9),fontSize=11.sp,lineHeight=17.sp)
     Text("قاعده کاربردی: "+lesson.rule,color=Color(0xFFB9D69A),fontSize=10.sp,lineHeight=15.sp)
    }
   }
  }
 }
}
