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
        val upProbability:Double,
        val memberProbabilities:List<Double>,
        val memberWeights:List<Double> = emptyList(),
        val marketRegime:String = "unknown"
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
        val rocAuc:Double=0.0,val calibrationError:Double=1.0,val loadError:String="")
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
    @Volatile private var trainingPriorLogOdds=0.0
    @Volatile private var globalMemberWeights:Map<String,Double> = emptyMap()
    @Volatile private var regimeMemberWeights:Map<String,Map<String,Double>> = emptyMap()
    @Volatile private var runtimeRegimeWeightingEnabled=false
    @Volatile private var status=Status(false,0,0,"",0.0)

    @Synchronized fun initialize(context:Context):Status {
        if(status.loaded) return status
        runCatching {
            val root=JSONObject(context.assets.open("wolf_pretrained_ensemble.json").bufferedReader().use{it.readText()})
            require(root.optString("format")=="amir-wolf-ensemble-v2")
            require(root.optString("target_definition")=="future_return_6h_up_vs_down_neutral_band_excluded")
            val corpus=JSONObject(context.assets.open("wolf_corpus_manifest.json").bufferedReader().use{it.readText()})
            val corpusRows=corpus.optInt("rows",0)
            val sources=corpus.optJSONArray("source_exchanges")
            require(corpus.optString("status")=="verified_real_exchange_data" &&
                corpusRows>=898_920 && corpus.optInt("synthetic_rows",-1)==0 &&
                corpus.optString("data_source_policy")=="XT_USDT_M_CRYPTO_ONLY" &&
                corpus.optString("asset_class_policy")=="XT_SPOT_CRYPTO_AND_COINGECKO_IDENTITY_MATCH" &&
                corpus.optBoolean("crypto_only_verified",false) &&
                corpus.optString("currency_universe_source")=="XT_SPOT_PUBLIC_SUPPORTED_CURRENCIES+COINGECKO_PUBLIC_COINS_LIST" &&
                corpus.optString("currency_metadata_sha256").length==64 &&
                corpus.optString("crypto_asset_registry_source")=="COINGECKO_PUBLIC_COINS_LIST" &&
                corpus.optString("crypto_asset_registry_sha256").length==64 &&
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
            trainingPriorLogOdds=root.optDouble("training_prior_log_odds",0.0)
            require(mean.size==7 && scale.size==7 && trainingPriorLogOdds.isFinite())
            // Read calibration weights, but never activate them unless the exported
            // bundle explicitly enables runtime weighting AND passes all validation gates.
            val regimeAudit=root.optJSONObject("regime_diagnostics")
            runtimeRegimeWeightingEnabled=regimeAudit?.optBoolean("runtime_weighting_enabled",false) ?: false
            val globalCalibration=regimeAudit?.optJSONObject("global_weights")
            val globalJson=globalCalibration?.optJSONObject("weights")
            globalMemberWeights=if(globalJson==null) emptyMap() else
                members.associate { member -> member.role to globalJson.optDouble(member.role,Double.NaN) }
            val regimeCalibration=regimeAudit?.optJSONObject("regime_weights")?.optJSONObject("regimes")
            val parsedRegimes=mutableMapOf<String,Map<String,Double>>()
            if(regimeCalibration!=null) {
                val keys=regimeCalibration.keys()
                while(keys.hasNext()) {
                    val regime=keys.next()
                    val weights=regimeCalibration.optJSONObject(regime)?.optJSONObject("weights") ?: continue
                    parsedRegimes[regime]=members.associate { member ->
                        member.role to weights.optDouble(member.role,Double.NaN)
                    }
                }
            }
            regimeMemberWeights=parsedRegimes
            require(root.optString("data_source_policy")==corpus.optString("data_source_policy"))
            require(root.optString("asset_class_policy")==corpus.optString("asset_class_policy"))
            require(root.optString("corpus_sha256")==corpus.optString("sha256"))
            require(root.optString("currency_metadata_sha256")==corpus.optString("currency_metadata_sha256"))
            require(root.optString("crypto_asset_registry_source")==corpus.optString("crypto_asset_registry_source"))
            require(root.optString("crypto_asset_registry_sha256")==corpus.optString("crypto_asset_registry_sha256"))
            val cryptoSymbols=corpus.optJSONArray("crypto_symbols")
            val verifiedSymbols=(0 until (cryptoSymbols?.length() ?: 0)).map { cryptoSymbols!!.optString(it) }.toSet()
            val modelSymbols=root.optJSONArray("symbols")
            require(modelSymbols!=null && (0 until modelSymbols.length()).all { verifiedSymbols.contains(modelSymbols.optString(it)) })
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
            val walk=root.optJSONObject("walk_forward")
            val cross=root.optJSONObject("cross_symbol_holdout")
            val walkForwardValid=walk!=null && walk.optString("status")=="evaluated" &&
                walk.optInt("fold_count",0)>=3 &&
                walk.optDouble("mean_balanced_accuracy",0.0)>=0.50 &&
                walk.optDouble("mean_log_loss",Double.POSITIVE_INFINITY) <
                    walk.optDouble("mean_baseline_log_loss",Double.NEGATIVE_INFINITY)
            val unseenSymbolValid=cross!=null && cross.optString("status")=="evaluated" &&
                cross.optDouble("balanced_accuracy",0.0)>=0.50 &&
                cross.optDouble("log_loss",Double.POSITIVE_INFINITY) <
                    cross.optDouble("baseline_log_loss",Double.NEGATIVE_INFINITY) &&
                cross.optDouble("roc_auc",0.0)>=0.52
            val validated=evaluation!=null && chronological && correctTarget && testSamples>=300 &&
                balanced.isFinite() && balanced>=0.52 && loss.isFinite() && baselineLoss.isFinite() && loss<baselineLoss &&
                accuracy.isFinite() && accuracy>=baseline && brier.isFinite() && baselineBrier.isFinite() &&
                brier<baselineBrier && auc.isFinite() && auc>=0.55 && ece.isFinite() && ece<=0.10 &&
                walkForwardValid && unseenSymbolValid
            status=Status(true,members.size,root.optInt("samples"),root.optString("trained_at_utc"),
                accuracy,baseline,balanced,loss,validated,corpusRows,brier,auc,ece)
        }.onFailure { error ->
            members=emptyList()
            status=Status(false,0,0,"",0.0,loadError=(error.message ?: error.javaClass.simpleName).take(240))
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
            // Match the class-prior correction used during chronological holdout evaluation.
            val logit=member.b4+h3.indices.sumOf{j->member.w4[j]*h3[j]}+trainingPriorLogOdds
            val pUp=1.0/(1.0+exp(-logit.coerceIn(-30.0,30.0)))
            pUp
        }
        val regime = when {
            raw[5] >= 0.6 -> "high_volatility"
            raw[5] <= -0.1 -> "low_volatility"
            raw[2] >= 0.5 -> if(raw[0] > 0.0) "trend_up" else "trend_down"
            else -> "range"
        }
        val useCalibratedWeights = runtimeRegimeWeightingEnabled && status.validatedForScoring
        val candidateWeights = if(useCalibratedWeights)
            (regimeMemberWeights[regime] ?: globalMemberWeights) else emptyMap()
        val validWeights = candidateWeights.size==members.size &&
            candidateWeights.values.all { it.isFinite() && it>=0.0 } &&
            kotlin.math.abs(candidateWeights.values.sum()-1.0) <= 1e-5
        val weights = if(validWeights) members.map { candidateWeights[it.role]!! }
            else List(members.size) { 1.0/members.size }
        val combined = predictions.indices.sumOf { predictions[it]*weights[it] }
        return ResearchPrediction(combined.coerceIn(.01,.99),predictions,weights,regime)
    }
}
