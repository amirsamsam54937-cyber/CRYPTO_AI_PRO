package com.cryptoaipro.monitor

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Ten-member deep MLP ensemble. Each network has 7 inputs and 3 hidden layers (64/32/16).
 * Weights are trained from verified historical exchange futures candles by tools/train_wolf_ensemble.py.
 */
internal object WolfPretrainedEnsemble {
    data class ResearchPrediction(
        val referenceDirection:String, val probability:Double,
        val memberProbabilities:List<Double>
    ) {
        val predictedDirection:String get() = when {
            strength < 55 -> "NEUTRAL"
            probability >= 0.5 -> referenceDirection
            referenceDirection == "LONG_BIAS" -> "SHORT_BIAS"
            else -> "LONG_BIAS"
        }
        val strength:Int get() = (maxOf(probability, 1.0-probability) * 100.0).toInt().coerceIn(1,99)
    }
    data class Status(val loaded:Boolean,val members:Int,val samples:Int,val trainedAt:String,val testAccuracy:Double,val baselineAccuracy:Double=0.0,val balancedAccuracy:Double=0.0,val logLoss:Double=0.0,val validatedForScoring:Boolean=false,val corpusRows:Int=0)
    private data class Member(
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
            val corpus=JSONObject(context.assets.open("wolf_corpus_manifest.json").bufferedReader().use{it.readText()})
            val corpusRows=corpus.optInt("rows",0)
            require(corpus.optString("status")=="verified_real_exchange_data" && corpusRows==500000 && corpus.optInt("synthetic_rows",-1)==0)
            fun vector(a:org.json.JSONArray)=DoubleArray(a.length()){a.getDouble(it)}
            fun matrix(a:org.json.JSONArray)=Array(a.length()){r->vector(a.getJSONArray(r))}
            val data=root.getJSONArray("members")
            members=(0 until data.length()).map { index ->
                val item=data.getJSONObject(index)
                Member(matrix(item.getJSONArray("w1")),vector(item.getJSONArray("b1")),
                    matrix(item.getJSONArray("w2")),vector(item.getJSONArray("b2")),
                    matrix(item.getJSONArray("w3")),vector(item.getJSONArray("b3")),
                    vector(item.getJSONArray("w4")),item.getDouble("b4"))
            }
            require(members.size==10 && members.all{it.w1.size==64 && it.w2.size==32 && it.w3.size==16 && it.w4.size==16})
            mean=vector(root.getJSONArray("feature_mean"))
            scale=vector(root.getJSONArray("feature_scale")).map{if(it==0.0)1.0 else it}.toDoubleArray()
            val metrics=root.getJSONArray("metrics")
            val memberAccuracy=(0 until metrics.length()).map{metrics.getJSONObject(it).optDouble("accuracy",0.0)}.average()
            val evaluation=root.optJSONObject("evaluation")
            val accuracy=evaluation?.optDouble("ensemble_accuracy",memberAccuracy) ?: memberAccuracy
            val baseline=evaluation?.optDouble("majority_class_baseline_accuracy",0.0) ?: 0.0
            val balanced=evaluation?.optDouble("ensemble_balanced_accuracy",0.0) ?: 0.0
            val loss=evaluation?.optDouble("ensemble_log_loss",Double.POSITIVE_INFINITY) ?: Double.POSITIVE_INFINITY
            val baselineLoss=evaluation?.optDouble("prevalence_baseline_log_loss",Double.POSITIVE_INFINITY) ?: Double.POSITIVE_INFINITY
            val testSamples=root.optInt("test_samples",0)
            val chronological=evaluation?.optString("split","")=="chronological_purged"
            val validated=evaluation!=null && chronological && testSamples>=300 &&
                balanced.isFinite() && balanced>=0.52 && loss.isFinite() &&
                baselineLoss.isFinite() && loss<baselineLoss && accuracy.isFinite() && accuracy>=baseline
            status=Status(true,members.size,root.optInt("samples"),root.optString("trained_at_utc"),accuracy,baseline,balanced,loss,validated,corpusRows)
        }.onFailure {
            members=emptyList()
            status=Status(false,0,0,"",0.0)
        }
        return status
    }

    fun status()=status

    /** Production prediction: only return a probability when the model's reference side matches the live technical side. */
    fun predict(signal:MarketSignal):Double? {
        val research = predictResearch(signal) ?: return null
        return research.probability.takeIf { research.referenceDirection == signal.direction }
    }

    /**
     * Research-only prediction for chronological historical replay.
     * It deliberately bypasses the live validation gate and side-agreement gate,
     * but MUST never be used to publish a live signal.
     */
    fun predictResearch(signal:MarketSignal):ResearchPrediction? {
        if(members.size!=10) return null
        // The training corpus is 1h OHLCV. Never feed 15m features into this model.
        val technical=signal.neuralTechnical ?: return null
        val rsiValue=signal.neuralRsi?.takeIf { it.isFinite() } ?: return null
        val candle=signal.neuralCandles.lastOrNull() ?: return null
        if(candle.size<5) return null
        val trend=if(technical.ema20>=technical.ema50) 1.0 else -1.0
        val rawScore=(trend*25.0+(50.0-rsiValue)*0.55+
            ((technical.relativeVolume-1.0).coerceIn(-1.0,2.0))*8.0).coerceIn(-100.0,100.0)
        val modelSide=if(rawScore>=0.0) "LONG_BIAS" else "SHORT_BIAS"
        // Training labels and directional normalization use this same 1h rule.
        // Research must retain the model's independent side even when it disagrees with
        // the technical engine; otherwise disagreement and agreement backtests are biased.
        // The production predict() wrapper still enforces side agreement before live use.
        val direction=if(modelSide=="SHORT_BIAS") -1.0 else 1.0
        val rsi=((rsiValue-50.0)/50.0).coerceIn(-1.0,1.0)
        val adx=(technical.adx/50.0).coerceIn(0.0,1.0)
        val volume=((technical.relativeVolume-1.0)/2.0).coerceIn(-1.0,1.0)
        val body=(candle[3]-candle[0])/((candle[1]-candle[2]).coerceAtLeast(1e-9))
        val bodyFeature=when{body>.25->1.0;body<-.25->-1.0;else->0.0}
        val volatility=((technical.atrPercent-2.0)/5.0).coerceIn(-1.0,1.0)
        val raw=doubleArrayOf(trend*direction,rsi*direction,adx,volume,bodyFeature*direction,volatility,rawScore/100.0)
        val x=DoubleArray(7){i->((raw[i]-mean[i])/scale[i]).coerceIn(-8.0,8.0)}
        val predictions=members.map { member ->
            val h1=DoubleArray(member.b1.size){i->tanh(member.b1[i]+x.indices.sumOf{j->member.w1[i][j]*x[j]})}
            val h2=DoubleArray(member.b2.size){i->tanh(member.b2[i]+h1.indices.sumOf{j->member.w2[i][j]*h1[j]})}
            val h3=DoubleArray(member.b3.size){i->tanh(member.b3[i]+h2.indices.sumOf{j->member.w3[i][j]*h2[j]})}
            val logit=member.b4+h3.indices.sumOf{j->member.w4[j]*h3[j]}
            1.0/(1.0+exp(-logit.coerceIn(-30.0,30.0)))
        }
        return ResearchPrediction(modelSide, predictions.average().coerceIn(.01,.99), predictions)
    }
}
