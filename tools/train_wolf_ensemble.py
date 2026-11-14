#!/usr/bin/env python3
"""Train a leakage-aware, role-diverse ten-network directional ensemble on XT OHLCV.

Labels describe the subsequent market move (up/down), never the output of the
technical rules used to choose a reference side. Each member receives a distinct
feature mask so the ensemble contains genuine specialist diversity.
"""
import csv, json, os, time
import numpy as np
try:
    from tools.time_series_validation import chronological_masks
    from tools.advanced_validation import evaluate_generalization
except ModuleNotFoundError:
    from time_series_validation import chronological_masks
    from advanced_validation import evaluate_generalization

DATA_SOURCE = "verified XT USDT-M crypto-only historical corpus"
MIN_LABELED_SAMPLES = 250_000
MAX_TRAIN_SAMPLES_PER_CLASS = 60_000  # Match the bounded walk-forward evaluator.
INTERVAL, HORIZON = "1h", 6
INTERVAL_MS = 60 * 60 * 1000
FEATURE_NAMES = ["ema_trend", "rsi_momentum", "adx_strength", "relative_volume",
                 "candle_body_pressure", "atr_percent", "past_6_bar_momentum"]
SPECIALISTS = [
    ("trend_structure",       [1,0,1,0,0,1,1]),
    ("momentum_oscillator",   [0,1,1,0,0,0,1]),
    ("volume_participation",  [0,0,1,1,1,0,0]),
    ("candle_pressure",       [0,1,0,1,1,1,0]),
    ("volatility_regime",     [1,0,1,1,0,1,1]),
    ("mean_reversion",        [0,1,1,1,1,1,1]),
    ("breakout_continuation", [1,0,1,1,1,0,1]),
    ("short_horizon_momentum",[1,1,0,0,1,0,1]),
    ("risk_regime",           [1,1,1,1,0,1,0]),
    ("balanced_critic",       [1,1,1,1,1,1,1]),
]

def ema(values, period):
    result=np.empty(len(values),dtype=float); result[0]=values[0]
    alpha=2.0/(period+1.0)
    for i in range(1,len(values)): result[i]=alpha*values[i]+(1-alpha)*result[i-1]
    return result

def wilder_rsi(close, period=14):
    result=np.full(len(close),50.0,dtype=float)
    if len(close)<=period: return result
    delta=np.diff(close); gains=np.maximum(delta,0.0); losses=np.maximum(-delta,0.0)
    avg_gain=float(gains[:period].mean()); avg_loss=float(losses[:period].mean())
    def value(g,l): return 100.0 if l<=1e-12 else 100.0-100.0/(1.0+g/l)
    result[period]=value(avg_gain,avg_loss)
    for i in range(period+1,len(close)):
        avg_gain=(avg_gain*(period-1)+gains[i-1])/period
        avg_loss=(avg_loss*(period-1)+losses[i-1])/period
        result[i]=value(avg_gain,avg_loss)
    return result

def app_atr_series(high,low,close,period=14):
    previous=np.r_[close[0],close[:-1]]
    tr=np.maximum(high-low,np.maximum(abs(high-previous),abs(low-previous)))
    result=np.zeros(len(close),dtype=float)
    for i in range(len(close)):
        start=max(1,i-period+1)
        result[i]=float(tr[start:i+1].mean()) if i>=period else close[i]*.01
    return result

def app_relative_volume(volume,period=20):
    result=np.ones(len(volume),dtype=float)
    for i in range(period,len(volume)):
        result[i]=volume[i]/max(float(volume[i-period:i].mean()),1e-9)
    return result

def app_adx_series(high,low,close,period=14):
    result=np.zeros(len(close),dtype=float)
    if len(close)<period*2+1: return result
    true_ranges=[]; plus_moves=[]; minus_moves=[]
    for i in range(1,len(close)):
        up=high[i]-high[i-1]; down=low[i-1]-low[i]
        plus_moves.append(up if up>down and up>0 else 0.0)
        minus_moves.append(down if down>up and down>0 else 0.0)
        true_ranges.append(max(high[i]-low[i],abs(high[i]-close[i-1]),abs(low[i]-close[i-1])))
    smooth_tr=sum(true_ranges[:period]); smooth_plus=sum(plus_moves[:period]); smooth_minus=sum(minus_moves[:period])
    dx=[]; adx=0.0
    for i in range(period,len(true_ranges)):
        if i>period:
            smooth_tr=smooth_tr-smooth_tr/period+true_ranges[i]
            smooth_plus=smooth_plus-smooth_plus/period+plus_moves[i]
            smooth_minus=smooth_minus-smooth_minus/period+minus_moves[i]
        safe=max(smooth_tr,1e-12)
        plus_di=100.0*smooth_plus/safe; minus_di=100.0*smooth_minus/safe
        dx_value=abs(plus_di-minus_di)/max(plus_di+minus_di,1e-12)*100.0
        dx.append(dx_value)
        if len(dx)<period: adx=dx_value
        elif len(dx)==period: adx=float(np.mean(dx[:period]))
        else: adx=(adx*(period-1)+dx_value)/period
        result[i+1]=adx
    return np.clip(result,0.0,100.0)

def expected_calibration_error(y_true, probabilities, bins=10):
    y_true=np.asarray(y_true,dtype=float); probabilities=np.asarray(probabilities,dtype=float)
    edges=np.linspace(0.0,1.0,bins+1); error=0.0
    for index in range(bins):
        mask=(probabilities>=edges[index]) & (probabilities<edges[index+1] if index<bins-1 else probabilities<=edges[index+1])
        if np.any(mask): error += float(mask.mean())*abs(float(y_true[mask].mean())-float(probabilities[mask].mean()))
    return float(error)

def train_majority_class_baseline_accuracy(y_train, y_test):
    """Score a constant-majority classifier whose class is selected from train only."""
    train = np.asarray(y_train)
    test = np.asarray(y_test)
    if train.ndim != 1 or test.ndim != 1 or train.size == 0 or test.size == 0:
        raise ValueError("training and test labels must be non-empty one-dimensional arrays")
    if not np.isin(train, (0, 1)).all() or not np.isin(test, (0, 1)).all():
        raise ValueError("majority baseline requires binary labels")
    majority_class = int(float(np.mean(train)) >= 0.5)
    return majority_class, float(np.mean(test == majority_class))


def balanced_bootstrap_indices(labels, seed, max_per_class=MAX_TRAIN_SAMPLES_PER_CLASS):
    """Return a deterministic, balanced and bounded training sample.

    Large classes are downsampled without replacement; smaller classes are
    oversampled only when needed. The holdout is never sampled or modified.
    """
    labels = np.asarray(labels, dtype=int)
    classes = [np.flatnonzero(labels == cls) for cls in (0, 1)]
    if min(map(len, classes)) < 2:
        raise ValueError("both classes need at least two training samples")
    per_class = min(max(map(len, classes)), int(max_per_class))
    if per_class < 2:
        raise ValueError("max_per_class must be at least two")
    rng = np.random.default_rng(seed)
    sampled = np.concatenate([
        rng.choice(indices, size=per_class, replace=len(indices) < per_class)
        for indices in classes
    ])
    rng.shuffle(sampled)
    return sampled


def repeat_symbol_labels(symbol, count):
    """Repeat a complete instrument identifier without NumPy's one-character <U1 truncation."""
    if count < 0:
        raise ValueError("count must be non-negative")
    return np.full(count, str(symbol), dtype=object)



def contiguous_candle_segments(raw, interval_ms=INTERVAL_MS):
    """Split OHLCV into strictly valid, gap-free intervals; never bridge missing hours."""
    a = np.asarray(raw, dtype=float)
    if a.ndim != 2 or a.shape[1] < 6:
        raise ValueError("raw candles must be a 2D array with timestamp and OHLCV columns")
    core = a[:, :6]
    finite = np.isfinite(core).all(axis=1)
    timestamps = core[:, 0]
    integral_time = timestamps == np.floor(timestamps)
    op, high, low, close, volume = core[:, 1], core[:, 2], core[:, 3], core[:, 4], core[:, 5]
    valid = (finite & integral_time & (op > 0) & (high > 0) & (low > 0) &
             (close > 0) & (volume >= 0) & (high >= np.maximum(op, close)) &
             (low <= np.minimum(op, close)) & (high >= low))
    indices = np.flatnonzero(valid)
    if indices.size == 0:
        return []
    valid_times = timestamps[indices].astype(np.int64)
    breaks = np.flatnonzero(
        (np.diff(indices) != 1) | (np.diff(valid_times) != int(interval_ms))
    ) + 1
    groups = np.split(indices, breaks)
    return [a[group] for group in groups if group.size >= 1]

def make_rows(raw):
    a=np.asarray(raw,dtype=float)
    o,h,l,c,v=a[:,1],a[:,2],a[:,3],a[:,4],a[:,5]
    e20,e50=ema(c,20),ema(c,50)
    rsi=wilder_rsi(c,14); atr=app_atr_series(h,l,c,14)
    rv=app_relative_volume(v,20); adx=app_adx_series(h,l,c,14)
    trend=np.where(e20>=e50,1.0,-1.0)
    body=(c-o)/np.maximum(h-l,1e-9)
    atr_pct=atr/np.maximum(c,1e-9)*100.0
    past6=np.zeros(len(c),dtype=float)
    past6[6:]=(c[6:]/np.maximum(c[:-6],1e-9)-1.0)*100.0
    X=np.column_stack([
        trend,
        np.clip((rsi-50.0)/50.0,-1.0,1.0),
        np.clip(adx/50.0,0.0,1.0),
        np.clip((rv-1.0)/2.0,-1.0,1.0),
        np.clip(body,-1.0,1.0),
        np.clip((atr_pct-2.0)/5.0,-1.0,1.0),
        np.clip(past6/5.0,-1.0,1.0),
    ])
    future=np.full(len(c),np.nan,dtype=float)
    future[:-HORIZON]=c[HORIZON:]/np.maximum(c[:-HORIZON],1e-9)-1.0
    # Label only economically meaningful future moves; sub-threshold noise is excluded.
    # Positive class means a forward return greater than +0.25%; negative class
    # contains returns below -0.25%. This target is independent of technical rules.
    y=(future>0.0025).astype(int)
    valid=np.isfinite(future) & (np.arange(len(c))>=50) & (np.abs(future)>0.0025)
    return X[valid],y[valid],a[:,0][valid]

def load_seed_samples():
    path="app/src/main/assets/market_seed.csv"
    manifest_path="app/src/main/assets/wolf_corpus_manifest.json"
    if not os.path.exists(path) or not os.path.exists(manifest_path):
        raise SystemExit("Verified XT-only corpus or manifest is missing.")
    with open(manifest_path,encoding="utf-8") as handle: manifest=json.load(handle)
    asset_map = manifest.get("asset_classification", {})
    if (manifest.get("status")!="verified_real_exchange_data" or int(manifest.get("rows",0))<898_920
        or sorted(x.upper() for x in manifest.get("source_exchanges",[]))!=["XT"]
        or int(manifest.get("synthetic_rows",-1))!=0
        or manifest.get("data_source_policy")!="XT_USDT_M_CRYPTO_ONLY"
        or manifest.get("asset_class_policy")!="XT_SPOT_CRYPTO_AND_COINGECKO_IDENTITY_MATCH"
        or manifest.get("crypto_only_verified") is not True
        or manifest.get("currency_universe_source")!="XT_SPOT_PUBLIC_SUPPORTED_CURRENCIES+COINGECKO_PUBLIC_COINS_LIST"
        or int(manifest.get("xt_crypto_type_currency_count",0)) < 1
        or manifest.get("crypto_asset_registry_source")!="COINGECKO_PUBLIC_COINS_LIST"
        or len(str(manifest.get("crypto_asset_registry_sha256",""))) != 64
        or not asset_map):
        raise SystemExit("Training requires a verified XT crypto-only corpus with explicit asset-class metadata.")
    if any(item.get("asset_class") != "crypto" for item in asset_map.values()):
        raise SystemExit("Non-crypto or unverified asset classification found in corpus manifest.")
    grouped={}
    with open(path,newline="",encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row.get("exchange","").upper()!="XT": raise SystemExit("Non-XT rows detected; refusing mixed-exchange training.")
            if row.get("timeframe")!="1h": continue
            symbol=row["symbol"].upper()
            if asset_map.get(symbol, {}).get("asset_class") != "crypto":
                raise SystemExit(f"Symbol {symbol} is not explicitly classified as crypto in the corpus manifest.")
            grouped.setdefault(symbol,[]).append([float(row["timestamp"]),float(row["open"]),float(row["high"]),
                float(row["low"]),float(row["close"]),float(row["volume"]),0.0])
    return grouped, manifest

def main():
    # Keep feature extraction importable in lightweight test/API environments.
    from sklearn.neural_network import MLPClassifier
    from sklearn.preprocessing import StandardScaler
    from sklearn.metrics import accuracy_score, balanced_accuracy_score, brier_score_loss, log_loss, roc_auc_score
    from sklearn.exceptions import ConvergenceWarning
    import warnings
    grouped, corpus_manifest = load_seed_samples()
    chunks=[]; used_symbols=[]; symbol_chunks=[]
    for symbol,raw in grouped.items():
        raw=sorted(raw,key=lambda row:row[0])
        symbol_samples=0
        segments=contiguous_candle_segments(raw)
        for segment in segments:
            # Six-bar labels and rolling indicators must stay inside gap-free 1h data.
            x,y,t=make_rows(segment)
            if len(x)>100:
                chunks.append((x,y,t))
                symbol_chunks.append(repeat_symbol_labels(symbol,len(x)))
                symbol_samples += len(x)
        if symbol_samples:
            used_symbols.append(symbol)
        print(f"REAL SEED {symbol}: {symbol_samples} directional samples across {len(segments)} gap-free segments",flush=True)
    if not chunks: raise SystemExit("No valid XT history; refusing to create fake pretrained weights.")
    X=np.vstack([z[0] for z in chunks]); y=np.concatenate([z[1] for z in chunks]); ts=np.concatenate([z[2] for z in chunks])
    symbols=np.concatenate(symbol_chunks)
    order=np.argsort(ts,kind="stable"); X,y,ts,symbols=X[order],y[order],ts[order],symbols[order]
    if len(X)<MIN_LABELED_SAMPLES:
        raise SystemExit(f"Only {len(X):,} crypto-only labeled samples; at least {MIN_LABELED_SAMPLES:,} are required.")
    if len(np.unique(y))<2: raise SystemExit("Insufficient directional history or only one class.")
    generalization = evaluate_generalization(X,y,ts,symbols,horizon_bars=HORIZON,n_splits=3)
    train_mask,test_mask,purged,cutoff=chronological_masks(ts)
    X_train,X_test=X[train_mask],X[test_mask]; y_train,y_test=y[train_mask],y[test_mask]
    if len(np.unique(y_train))<2 or len(np.unique(y_test))<2: raise SystemExit("Both chronological partitions need both labels.")
    scaler=StandardScaler().fit(X_train)
    A,B=scaler.transform(X_train),scaler.transform(X_test)
    members=[]; metrics=[]; all_probabilities=[]
    for index,(role,mask_values) in enumerate(SPECIALISTS):
        mask=np.asarray(mask_values,dtype=bool)
        a=A.copy(); b=B.copy(); a[:,~mask]=0.0; b[:,~mask]=0.0
        model=MLPClassifier(hidden_layer_sizes=(64,32,16),activation="tanh",solver="adam",
            alpha=.002,learning_rate_init=.0015,max_iter=300,early_stopping=False,
            tol=1e-4,n_iter_no_change=22,random_state=20261112+index)
        # Train each specialist on a deterministic class-balanced bootstrap.
        # Correct the posterior back to the real chronological training prevalence
        # before evaluating on the untouched holdout, preserving calibration semantics.
        try:
            balanced_idx=balanced_bootstrap_indices(
                y_train, seed=20261112+index,
                max_per_class=MAX_TRAIN_SAMPLES_PER_CLASS
            )
        except ValueError as error:
            raise SystemExit(str(error)) from error
        with warnings.catch_warnings(record=True) as caught_warnings:
            warnings.simplefilter("always", ConvergenceWarning)
            model.fit(a[balanced_idx],y_train[balanced_idx])
        convergence_warning=any(issubclass(item.category,ConvergenceWarning) for item in caught_warnings)
        raw_probabilities=np.clip(model.predict_proba(b)[:,1],1e-6,1-1e-6)
        train_prevalence=float(np.clip(y_train.mean(),1e-4,1-1e-4))
        raw_logits=np.log(raw_probabilities/(1.0-raw_probabilities))
        prior_log_odds=np.log(train_prevalence/(1.0-train_prevalence))
        probabilities=1.0/(1.0+np.exp(-np.clip(raw_logits+prior_log_odds,-30.0,30.0)))
        all_probabilities.append(probabilities)
        metrics.append({"member":index+1,"role":role,
            "accuracy":round(float(accuracy_score(y_test,probabilities>=.5)),4),
            "balanced_accuracy":round(float(balanced_accuracy_score(y_test,probabilities>=.5)),4),
            "log_loss":round(float(log_loss(y_test,np.clip(probabilities,1e-6,1-1e-6))),4),
            "brier_score":round(float(brier_score_loss(y_test,probabilities)),4),
            "roc_auc":round(float(roc_auc_score(y_test,probabilities)),4),
            "expected_calibration_error":round(expected_calibration_error(y_test,probabilities),4),
            "iterations":int(model.n_iter_),"max_iter":int(model.max_iter),
            "converged":bool(int(model.n_iter_) < int(model.max_iter)),
            "convergence_warning":bool(convergence_warning),
            "final_loss":round(float(model.loss_),8)})
        members.append({"role":role,"feature_mask":mask.astype(int).tolist(),
            "w1":model.coefs_[0].T.tolist(),"b1":model.intercepts_[0].tolist(),
            "w2":model.coefs_[1].T.tolist(),"b2":model.intercepts_[1].tolist(),
            "w3":model.coefs_[2].T.tolist(),"b3":model.intercepts_[2].tolist(),
            "w4":model.coefs_[3][:,0].tolist(),"b4":float(model.intercepts_[3][0])})
        print(f"EXPERT {index+1}/10 {role}: {metrics[-1]}",flush=True)
    ensemble=np.mean(all_probabilities,axis=0)
    baseline_class, baseline_accuracy = train_majority_class_baseline_accuracy(y_train, y_test)
    evaluation={"split":"chronological_purged","target":"future_price_direction_up_vs_down",
        "cutoff_timestamp_ms":int(cutoff),"purged_rows":int(purged),"positive_rate":round(float(y_test.mean()),6),
        "majority_class_baseline_accuracy":round(baseline_accuracy,4),
        "majority_class_baseline_class":baseline_class,
        "majority_class_baseline_train_positive_rate":round(float(y_train.mean()),6),
        "majority_class_baseline_selection_split":"train_only",
        "prevalence_baseline_log_loss":round(float(log_loss(y_test,np.full(len(y_test),float(y_train.mean())),labels=[0,1])),4),
        "ensemble_accuracy":round(float(accuracy_score(y_test,ensemble>=.5)),4),
        "ensemble_balanced_accuracy":round(float(balanced_accuracy_score(y_test,ensemble>=.5)),4),
        "ensemble_log_loss":round(float(log_loss(y_test,np.clip(ensemble,1e-6,1-1e-6))),4),
        "ensemble_brier_score":round(float(brier_score_loss(y_test,ensemble)),4),
        "prevalence_baseline_brier_score":round(float(np.mean((y_test-float(y_train.mean()))**2)),4),
        "ensemble_expected_calibration_error":round(expected_calibration_error(y_test,ensemble),4),
        "ensemble_roc_auc":round(float(roc_auc_score(y_test,ensemble)),4)}
    output={"format":"amir-wolf-ensemble-v2","trained_at_utc":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),
        "interval":INTERVAL,"horizon_bars":HORIZON,"feature_names":FEATURE_NAMES,
        "target_definition":"future_return_6h_up_vs_down_neutral_band_excluded",
        "specialist_roles":[x[0] for x in SPECIALISTS],"symbols":sorted(used_symbols),"symbols_count":len(used_symbols),
        "data_source_policy":"XT_USDT_M_CRYPTO_ONLY","asset_class_policy":"XT_SPOT_CRYPTO_AND_COINGECKO_IDENTITY_MATCH",
        "corpus_sha256":corpus_manifest.get("sha256",""),
        "currency_metadata_sha256":corpus_manifest.get("currency_metadata_sha256",""),
         "crypto_asset_registry_source":corpus_manifest.get("crypto_asset_registry_source",""),
         "crypto_asset_registry_sha256":corpus_manifest.get("crypto_asset_registry_sha256",""),
        "samples":int(len(X)),"train_samples":int(len(X_train)),"test_samples":int(len(X_test)),
        "feature_mean":scaler.mean_.tolist(),"feature_scale":scaler.scale_.tolist(),
        "training_prior_log_odds":float(np.log(np.clip(y_train.mean(),1e-4,1-1e-4)/(1.0-np.clip(y_train.mean(),1e-4,1-1e-4)))),
        "evaluation":evaluation,"walk_forward":generalization["walk_forward"],
        "cross_symbol_holdout":generalization["cross_symbol_holdout"],
        "training_sampling":{"method":"deterministic_balanced_bootstrap","max_samples_per_class":MAX_TRAIN_SAMPLES_PER_CLASS,"actual_samples_per_class":int(len(balanced_idx)//2)},"metrics":metrics,"members":members}
    os.makedirs("app/src/main/assets",exist_ok=True)
    with open("app/src/main/assets/wolf_pretrained_ensemble.json","w") as f: json.dump(output,f,separators=(",",":"))
    with open("wolf_model_training_report.json","w") as f: json.dump({k:v for k,v in output.items() if k!="members"},f,indent=2)
    print(f"PURGED HOLDOUT: {evaluation}",flush=True)
    print(f"EXPORTED ten role-diverse XT networks from {len(X)} labeled samples.",flush=True)

if __name__=="__main__": main()
