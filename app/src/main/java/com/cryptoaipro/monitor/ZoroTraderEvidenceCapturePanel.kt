package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Local persistent evidence journal. No invented trades and no exchange execution. */
internal object ZoroTraderEvidenceStore {
    private const val PREFS = "zoro_trader_evidence_v1"
    private const val KEY = "calls_json"

    fun load(context: Context): List<ZoroTraderEvidenceLedger.Call> {
        val json = runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }.getOrElse { JSONArray() }
        return (0 until json.length()).mapNotNull { i -> runCatching {
            val o = json.getJSONObject(i)
            ZoroTraderEvidenceLedger.Call(
                id=o.getString("id"), traderId=o.getString("traderId"), sourceUrl=o.getString("sourceUrl"),
                publishedAtEpochMs=o.getLong("publishedAtEpochMs"), capturedAtEpochMs=o.getLong("capturedAtEpochMs"),
                symbol=o.optString("symbol").takeIf { it.isNotBlank() }, venue=o.optString("venue").takeIf { it.isNotBlank() },
                timeframe=o.optString("timeframe").takeIf { it.isNotBlank() },
                side=ZoroTraderEvidenceLedger.Side.valueOf(o.getString("side")),
                entry=if(o.isNull("entry")) null else o.getDouble("entry"),
                stop=if(o.isNull("stop")) null else o.getDouble("stop"),
                target=if(o.isNull("target")) null else o.getDouble("target"),
                status=ZoroTraderEvidenceLedger.CallStatus.valueOf(o.getString("status")),
                netR=if(o.isNull("netR")) null else o.getDouble("netR"),
                evidence=ZoroTraderEvidenceLedger.EvidenceGrade.valueOf(o.getString("evidence")),
                notes=o.optString("notes").takeIf { it.isNotBlank() }
            )
        }.getOrNull() }
    }

    fun add(context: Context, call: ZoroTraderEvidenceLedger.Call) {
        val audit=ZoroTraderEvidenceLedger.audit(call)
        require(audit.valid) { audit.errors.joinToString("؛ ") }
        val all=load(context).filterNot { it.id==call.id } + call
        val json=JSONArray()
        all.takeLast(1000).forEach { c -> json.put(JSONObject().apply {
            put("id",c.id); put("traderId",c.traderId); put("sourceUrl",c.sourceUrl)
            put("publishedAtEpochMs",c.publishedAtEpochMs); put("capturedAtEpochMs",c.capturedAtEpochMs)
            put("symbol",c.symbol); put("venue",c.venue); put("timeframe",c.timeframe)
            put("side",c.side.name); put("entry",c.entry); put("stop",c.stop); put("target",c.target)
            put("status",c.status.name); put("netR",c.netR); put("evidence",c.evidence.name); put("notes",c.notes)
        }) }
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,json.toString()).apply()
    }
}

@Composable
internal fun ZoroTraderEvidenceCapturePanel(context: Context) {
    var trader by remember { mutableStateOf("TRH") }
    var url by remember { mutableStateOf("") }
    var published by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("XAUUSD") }
    var timeframe by remember { mutableStateOf("15m") }
    var entry by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var netR by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("LONG") }
    var status by remember { mutableStateOf("OPEN") }
    var grade by remember { mutableStateOf("C") }
    var feedback by remember { mutableStateOf("هر نمونه را با لینک و زمان انتشار ثبت کن؛ نتیجه نامعلوم را حدس نزن.") }
    var refresh by remember { mutableIntStateOf(0) }
    val calls=remember(refresh) { ZoroTraderEvidenceStore.load(context) }
    val metrics=remember(calls) { ZoroTraderEvidenceLedger.metrics(calls) }
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF15130F))) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text("دفتر شواهد معاملات • ثبت و ارزیابی",color=Color(0xFFFFD45A),fontWeight=FontWeight.Bold,fontSize=15.sp)
            Text("اطلاعات روی همین دستگاه ذخیره می‌شود. پست عمومی را با معامله اجراشده در حساب اشتباه نگیر.",color=Color(0xFFB8B0A0),fontSize=10.sp)
            @Composable fun field(value:String,onValue:(String)->Unit,label:String) {
                OutlinedTextField(value,onValue,Modifier.fillMaxWidth(),label={ Text(label) },singleLine=true)
            }
            field(trader,{trader=it},"شناسه معامله‌گر")
            field(url,{url=it},"لینک دقیق پست یا ویدئو")
            field(published,{published=it},"زمان انتشار Unix میلی‌ثانیه")
            field(symbol,{symbol=it},"نماد")
            field(timeframe,{timeframe=it},"تایم‌فریم")
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf("LONG","SHORT","NEUTRAL").forEach { Button(onClick={side=it}) { Text(if(side==it) "✓$it" else it,fontSize=9.sp) } } }
            Row(horizontalArrangement=Arrangement.spacedBy(3.dp),modifier=Modifier.fillMaxWidth()) { listOf("OPEN","TARGET","STOP","MANUAL_EXIT").forEach { Button(onClick={status=it},modifier=Modifier.weight(1f)) { Text(if(status==it) "✓$it" else it,fontSize=8.sp) } } }
            Row(horizontalArrangement=Arrangement.spacedBy(3.dp),modifier=Modifier.fillMaxWidth()) { listOf("CANCELLED","INVALIDATED","NO_FILL","UNKNOWN").forEach { Button(onClick={status=it},modifier=Modifier.weight(1f)) { Text(if(status==it) "✓$it" else it,fontSize=7.sp) } } }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf("A","B","C","D").forEach { Button(onClick={grade=it}) { Text(if(grade==it) "✓$it" else it) } } }
            field(entry,{entry=it},"قیمت ورود (اختیاری)")
            field(stop,{stop=it},"حدضرر (اختیاری)")
            field(target,{target=it},"هدف (اختیاری)")
            field(netR,{netR=it},"نتیجه خالص برحسب R؛ فقط اگر قابل محاسبه است")
            OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("یادداشت شواهد، تأیید، ابطال، هزینه‌ها")})
            Button(onClick={
                val now=System.currentTimeMillis()
                val item=runCatching { ZoroTraderEvidenceLedger.Call(
                    id="$trader-$now",traderId=trader.trim(),sourceUrl=url.trim(),
                    publishedAtEpochMs=published.trim().toLong(),capturedAtEpochMs=now,
                    symbol=symbol.trim().ifBlank { null },venue="PUBLIC_SOURCE",timeframe=timeframe.trim().ifBlank { null },
                    side=ZoroTraderEvidenceLedger.Side.valueOf(side),entry=entry.toDoubleOrNull(),stop=stop.toDoubleOrNull(),target=target.toDoubleOrNull(),
                    status=ZoroTraderEvidenceLedger.CallStatus.valueOf(status),netR=netR.toDoubleOrNull(),
                    evidence=ZoroTraderEvidenceLedger.EvidenceGrade.valueOf(grade),notes=notes.trim().ifBlank { null }
                ) }
                if(item.isFailure) feedback="خطا: زمان انتشار باید Unix میلی‌ثانیه باشد و داده‌ها معتبر باشند."
                else {
                    val saved=runCatching { ZoroTraderEvidenceStore.add(context,item.getOrThrow()) }
                    feedback=if(saved.isSuccess) "نمونه ثبت شد." else "ثبت نشد: ${saved.exceptionOrNull()?.message}"
                    if(saved.isSuccess) { url="";published="";entry="";stop="";target="";netR="";notes="";refresh++ }
                }
            },Modifier.fillMaxWidth()) { Text("ثبت نمونه در دفتر شواهد") }
            Text(feedback,color=Color(0xFFFFD45A),fontSize=10.sp)
            Text("کل ${calls.size} | نتیجه‌دار ${metrics.resolvedCount} | برد ${metrics.wins} | باخت ${metrics.losses} | نامعلوم ${metrics.missingResultCount}",color=Color.White,fontWeight=FontWeight.Bold,fontSize=11.sp)
            Text("Win rate ${metrics.winRatePercent?.let { String.format(Locale.US,"%.1f%%",it) } ?: "—"} • Expectancy ${metrics.expectancyR?.let { String.format(Locale.US,"%.3f R",it) } ?: "—"} • PF ${metrics.profitFactor?.let { if(it.isInfinite()) "∞" else String.format(Locale.US,"%.2f",it) } ?: "—"} • Max DD ${metrics.maxDrawdownR?.let { String.format(Locale.US,"%.2f R",it) } ?: "—"}",color=Color(0xFFB8B0A0),fontSize=10.sp)
            Text("وضعیت ارزیابی: ${ZoroTraderEvidenceLedger.screeningLabel(metrics)}",color=Color(0xFFFFD45A),fontSize=10.sp)
            calls.takeLast(10).reversed().forEach { c ->
                Column(Modifier.fillMaxWidth().padding(vertical=3.dp)) {
                    Text("${c.traderId} • ${c.symbol ?: "?"} • ${c.side} • ${c.status} • ${c.netR?.let { String.format(Locale.US,"%.2f R",it) } ?: "نتیجه نامعلوم"}",color=Color.White,fontSize=10.sp)
                    Text(c.sourceUrl,color=Color(0xFFB8B0A0),fontSize=9.sp)
                }
            }
        }
    }
}
