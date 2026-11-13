package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Ten role-diverse MLP experts. Training labels are future market direction,
 * independent of the technical rule used to define the reference side.
 * Each expert has its own feature mask and remains advisory until holdout validation passes.
 */
internal object WolfPretrainedEnsemble {
    data class ResearchPrediction(
        val upProbability:Double, val memberProbabilities:List<Double>
    ) {
        val predictedDirection:String get() = when {
            upProbability >= 0.55 -> "LONG_BIAS"
            upProbability <= 0.45 -> "SHORT_BIAS"
            else -> "NEUTRAL"
        }
        val strength:Int get() = (maxOf(upProbability, 1.0-upProbability) * 100.0).toInt().coerceIn(1,99)
    }
    data class Status(val loaded:Boolean,val members:Int,val samples:Int,val trainedAt:String,val testAccuracy:Double,
        val baselineAccuracy:Double=0.0,val balancedAccuracy:Double=0.0,val logLoss:Double=0.0,
        val validatedForScoring:Boolean=false,val corpusRows:Int=0,val brierScore:Double=0.0,
        val rocAuc:Double=0.0,val calibrationError:Double=1.0)
    private data class Member(
        val role:String,val featureMask:BooleanArray,
        val w1:Array<DoubleArray>,val b1:DoubleArray,
        val w2:Array<DoubleArray>,val b2:DoubleArray,
        val w3:Array<DoubleArray>,val b3:DoubleArray,
        val w4:DoubleArray,val b4:Double
    )
    @Volatile private var members:List<Member> = emptyList()
    @Volatile private var mean=DoubleArray(7)
    @Volatile private var scale=DoubleArray(7){1.0}
    @Volatile private var status=Status(false,0,0,"",0.0)

    @Synchronized fun initialize(context:Context):Status {
        if(status.loaded) return status
        runCatching {
            val root=JSONObject(context.assets.open("wolf_pretrained_ensemble.json").bufferedReader().use{it.readText()})
            require(root.optString("format")=="amir-wolf-ensemble-v1")
            require(root.optString("target_definition")=="future_return_over_6_hours_gt_0.25_percent")
            val corpus=JSONObject(context.assets.open("wolf_corpus_manifest.json").bufferedReader().use{it.readText()})
            val corpusRows=corpus.optInt("rows",0)
            val sources=corpus.optJSONArray("source_exchanges")
            require(corpus.optString("status")=="verified_real_exchange_data" &&
                corpusRows>=745_427 && corpus.optInt("synthetic_rows",-1)==0 &&
                corpus.optString("data_source_policy")=="XT_USDT_M_ONLY" &&
                sources!=null && sources.length()==1 && sources.optString(0).equals("XT",ignoreCase=true))
            fun vector(a:org.json.JSONArray)=DoubleArray(a.length()){a.getDouble(it)}
            fun matrix(a:org.json.JSONArray)=Array(a.length()){r->vector(a.getJSONArray(r))}
            val data=root.getJSONArray("members")
            members=(0 until data.length()).map { index ->
                val item=data.getJSONObject(index)
                val mask=vector(item.getJSONArray("feature_mask")).map{it>=0.5}.toBooleanArray()
                Member(item.optString("role","expert_${index+1}"),mask,
                    matrix(item.getJSONArray("w1")),vector(item.getJSONArray("b1")),
                    matrix(item.getJSONArray("w2")),vector(item.getJSONArray("b2")),
                    matrix(item.getJSONArray("w3")),vector(item.getJSONArray("b3")),
                    vector(item.getJSONArray("w4")),item.getDouble("b4"))
            }
            require(members.size==10 && members.all{it.featureMask.size==7 && it.featureMask.count{v->v}>=3 &&
                it.w1.size==64 && it.w2.size==32 && it.w3.size==16 && it.w4.size==16})
            require(members.map{it.featureMask.joinToString(""){v->if(v)"1" else "0"}}.distinct().size==10)
            mean=vector(root.getJSONArray("feature_mean"))
            scale=vector(root.getJSONArray("feature_scale")).map{if(it==0.0)1.0 else it}.toDoubleArray()
            require(mean.size==7 && scale.size==7)
            val metrics=root.getJSONArray("metrics")
            val memberAccuracy=(0 until metrics.length()).map{metrics.getJSONObject(it).optDouble("accuracy",0.0)}.average()
            val evaluation=root.optJSONObject("evaluation")
            val accuracy=evaluation?.optDouble("ensemble_accuracy",memberAccuracy) ?: memberAccuracy
            val baseline=evaluation?.optDouble("majority_class_baseline_accuracy",0.0) ?: 0.0
            val balanced=evaluation?.optDouble("ensemble_balanced_accuracy",0.0) ?: 0.0
            val loss=evaluation?.optDouble("ensemble_log_loss",Double.POSITIVE_INFINITY) ?: Double.POSITIVE_INFINITY
            val baselineLoss=evaluation?.optDouble("prevalence_baseline_log_loss",Double.POSITIVE_INFINITY) ?: Double.POSITIVE_INFINITY
            val brier=evaluation?.optDouble("ensemble_brier_score",Double.POSITIVE_INFINITY) ?: Double.POSITIVE_INFINITY
            val baselineBrier=evaluation?.optDouble("prevalence_baseline_brier_score",Double.POSITIVE_INFINITY) ?: Double.POSITIVE_INFINITY
            val auc=evaluation?.optDouble("ensemble_roc_auc",0.0) ?: 0.0
            val ece=evaluation?.optDouble("ensemble_expected_calibration_error",Double.POSITIVE_INFINITY) ?: Double.POSITIVE_INFINITY
            val testSamples=root.optInt("test_samples",0)
            val chronological=evaluation?.optString("split","")=="chronological_purged"
            val correctTarget=evaluation?.optString("target","")=="future_price_direction_up_vs_down"
            val validated=evaluation!=null && chronological && correctTarget && testSamples>=300 &&
                balanced.isFinite() && balanced>=0.52 && loss.isFinite() && baselineLoss.isFinite() && loss<baselineLoss &&
                accuracy.isFinite() && accuracy>=baseline && brier.isFinite() && baselineBrier.isFinite() &&
                brier<baselineBrier && auc.isFinite() && auc>=0.55 && ece.isFinite() && ece<=0.10
            status=Status(true,members.size,root.optInt("samples"),root.optString("trained_at_utc"),
                accuracy,baseline,balanced,loss,validated,corpusRows,brier,auc,ece)
        }.onFailure {
            members=emptyList()
            status=Status(false,0,0,"",0.0)
        }
        return status
    }

    fun status()=status
    fun predict(signal:MarketSignal):Double? {
        val research=predictResearch(signal) ?: return null
        return research.upProbability
    }

    /** Research-only inference; probabilities are converted to P(correct relative to the technical reference side). */
    fun predictResearch(signal:MarketSignal):ResearchPrediction? {
        if(members.size!=10) return null
        val technical=signal.neuralTechnical ?: return null
        val rsiValue=signal.neuralRsi?.takeIf{it.isFinite()} ?: return null
        val candles=signal.neuralCandles
        if(candles.size<7 || candles.takeLast(7).any{it.size<5 || !it[3].isFinite()}) return null
        val candle=candles.last()
        val trend=if(technical.ema20>=technical.ema50) 1.0 else -1.0
        val rsi=((rsiValue-50.0)/50.0).coerceIn(-1.0,1.0)
        val adx=(technical.adx/50.0).coerceIn(0.0,1.0)
        val volume=((technical.relativeVolume-1.0)/2.0).coerceIn(-1.0,1.0)
        val body=((candle[3]-candle[0])/(candle[1]-candle[2]).coerceAtLeast(1e-9)).coerceIn(-1.0,1.0)
        val volatility=((technical.atrPercent-2.0)/5.0).coerceIn(-1.0,1.0)
        val oldClose=candles[candles.lastIndex-6][3]
        val momentum6=(((candle[3]/oldClose.coerceAtLeast(1e-9)-1.0)*100.0)/5.0).coerceIn(-1.0,1.0)
        // Feature order exactly matches tools/train_wolf_ensemble.py.
        val raw=doubleArrayOf(trend,rsi,adx,volume,body,volatility,momentum6)
        val predictions=members.map { member ->
            val x=DoubleArray(7){i->if(member.featureMask[i])
                ((raw[i]-mean[i])/scale[i]).coerceIn(-8.0,8.0) else 0.0}
            val h1=DoubleArray(member.b1.size){i->tanh(member.b1[i]+x.indices.sumOf{j->member.w1[i][j]*x[j]})}
            val h2=DoubleArray(member.b2.size){i->tanh(member.b2[i]+h1.indices.sumOf{j->member.w2[i][j]*h1[j]})}
            val h3=DoubleArray(member.b3.size){i->tanh(member.b3[i]+h2.indices.sumOf{j->member.w3[i][j]*h2[j]})}
            val logit=member.b4+h3.indices.sumOf{j->member.w4[j]*h3[j]}
            val pUp=1.0/(1.0+exp(-logit.coerceIn(-30.0,30.0)))
            pUp
        }
        return ResearchPrediction(predictions.average().coerceIn(.01,.99),predictions)
    }
}
