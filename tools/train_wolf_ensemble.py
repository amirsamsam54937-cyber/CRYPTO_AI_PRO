#!/usr/bin/env python3
"""Train ten compact MLPs on verified historical futures OHLCV candles."""
import csv, json, os
import numpy as np
from sklearn.neural_network import MLPClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, balanced_accuracy_score, brier_score_loss, log_loss, roc_auc_score
try:
    from tools.time_series_validation import chronological_masks
except ModuleNotFoundError:  # direct execution as `python tools/train_wolf_ensemble.py`
    from time_series_validation import chronological_masks

DATA_SOURCE = "verified XT USDT perpetual historical corpus"
INTERVAL, HORIZON = "1h", 6

def ema(values, period):
    result = np.empty(len(values), dtype=float)
    result[0] = values[0]
    alpha = 2.0 / (period + 1.0)
    for i in range(1, len(values)):
        result[i] = alpha * values[i] + (1.0 - alpha) * result[i-1]
    return result

def wilder_rsi(close, period=14):
    result=np.full(len(close),50.0,dtype=float)
    if len(close)<=period:
        return result
    delta=np.diff(close)
    gains=np.maximum(delta,0.0); losses=np.maximum(-delta,0.0)
    avg_gain=float(gains[:period].mean()); avg_loss=float(losses[:period].mean())
    def value(g,l):
        return 100.0 if l<=1e-12 else 100.0-100.0/(1.0+g/l)
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
    if len(close)<period*2+1:
        return result
    true_ranges=[]; plus_moves=[]; minus_moves=[]
    for i in range(1,len(close)):
        up=high[i]-high[i-1]; down=low[i-1]-low[i]
        plus_moves.append(up if up>down and up>0 else 0.0)
        minus_moves.append(down if down>up and down>0 else 0.0)
        true_ranges.append(max(high[i]-low[i],abs(high[i]-close[i-1]),abs(low[i]-close[i-1])))
    smooth_tr=sum(true_ranges[:period]); smooth_plus=sum(plus_moves[:period]); smooth_minus=sum(minus_moves[:period])
    dx=[]
    adx=None
    for i in range(period,len(true_ranges)):
        if i>period:
            smooth_tr=smooth_tr-smooth_tr/period+true_ranges[i]
            smooth_plus=smooth_plus-smooth_plus/period+plus_moves[i]
            smooth_minus=smooth_minus-smooth_minus/period+minus_moves[i]
        safe=max(smooth_tr,1e-12)
        plus_di=100.0*smooth_plus/safe; minus_di=100.0*smooth_minus/safe
        dx_value=abs(plus_di-minus_di)/max(plus_di+minus_di,1e-12)*100.0
        dx.append(dx_value)
        if len(dx)<period:
            adx=dx_value
        elif len(dx)==period:
            adx=float(np.mean(dx[:period]))
        else:
            adx=(adx*(period-1)+dx_value)/period
        # Kotlin's trueRanges[0] corresponds to candle index 1; loop index i
        # therefore writes the current estimate to candle index i+1.
        result[i+1]=adx
    return np.clip(result,0.0,100.0)

def make_rows(raw):
    a = np.asarray(raw, dtype=float)
    o,h,l,c,v = a[:,1],a[:,2],a[:,3],a[:,4],a[:,5]
    e20,e50 = ema(c,20),ema(c,50)
    rsi = wilder_rsi(c,14)
    atr = app_atr_series(h,l,c,14)
    rv = app_relative_volume(v,20)
    adx = app_adx_series(h,l,c,14)
    trend = np.where(e20>=e50,1.0,-1.0)
    raw_score = np.clip(trend*25.0 + (50.0-rsi)*.55 + np.clip(rv-1.0,-1.0,2.0)*8.0,-100,100)
    direction = np.where(raw_score>=0,1.0,-1.0)
    body = (c-o)/np.maximum(h-l,1e-9)
    atr_pct = atr/np.maximum(c,1e-9)*100.0
    # Exact 1h feature definitions mirror the Android inference path.
    X = np.column_stack([
        trend*direction,
        ((rsi-50.0)/50.0)*direction,
        np.clip(adx/50.0,0.0,1.0),
        np.clip((rv-1.0)/2.0,-1.0,1.0),
        np.where(body>.25,1.0,np.where(body<-.25,-1.0,0.0))*direction,
        np.clip((atr_pct-2.0)/5.0,-1.0,1.0),
        raw_score/100.0
    ])
    future = np.roll(c,-HORIZON)/c-1.0
    y = (future*direction > .0025).astype(int)
    mask = np.arange(len(c)) < len(c)-HORIZON
    mask &= np.abs(future) > .0025
    return X[mask],y[mask],a[:,0][mask]

def fetch_symbol_samples(symbol):
    raw=get_history(symbol)
    x,y,t=make_rows(raw)
    return symbol,x,y,t

def load_seed_samples():
    path="app/src/main/assets/market_seed.csv"
    if not os.path.exists(path):
        return None
    grouped={}
    with open(path,newline="",encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row.get("timeframe") != "1h":
                continue
            symbol=row["symbol"].upper()
            grouped.setdefault(symbol,[]).append([
                float(row["timestamp"]),float(row["open"]),float(row["high"]),
                float(row["low"]),float(row["close"]),float(row["volume"]),0.0
            ])
    return grouped

def main():
    chunks=[]
    used_symbols=[]
    seeded=load_seed_samples()
    if seeded:
        for symbol,raw in seeded.items():
            raw=sorted(raw,key=lambda row:row[0])
            x,y,t=make_rows(raw)
            if len(x)>100:
                chunks.append((x,y,t))
                used_symbols.append(symbol)
            print(f"REAL SEED {symbol}: {len(x)} labeled samples",flush=True)
    else:
        raise SystemExit(
            "Verified XT Futures training corpus is missing; refusing to fetch from another exchange "
            "or create synthetic samples."
        )
    if not chunks:
        raise SystemExit("No real historical data received; refusing to create fake pretrained weights.")
    X=np.vstack([item[0] for item in chunks])
    y=np.concatenate([item[1] for item in chunks])
    timestamps=np.concatenate([item[2] for item in chunks])
    # Keep features, labels and timestamps in the same chronological order.
    order=np.argsort(timestamps,kind="stable")
    X,y,timestamps=X[order],y[order],timestamps[order]
    if len(X)<500 or len(np.unique(y))<2:
        raise SystemExit("Insufficient labeled history or only one class; no model exported.")
    train_mask,test_mask,purged_rows,cutoff=chronological_masks(timestamps)
    X_train,X_test=X[train_mask],X[test_mask]
    y_train,y_test=y[train_mask],y[test_mask]
    if len(np.unique(y_train))<2 or len(np.unique(y_test))<2:
        raise SystemExit("Purged chronological partitions must both contain positive and negative labels.")
    scaler=StandardScaler().fit(X_train)
    A,B=scaler.transform(X_train),scaler.transform(X_test)
    members,metrics,all_probabilities=[],[],[]
    for index in range(10):
        model=MLPClassifier(hidden_layer_sizes=(64,32,16),activation="tanh",solver="adam",
            alpha=.002,learning_rate_init=.0015,max_iter=300,early_stopping=True,
            validation_fraction=.15,n_iter_no_change=22,random_state=20261107+index)
        model.fit(A,y_train)
        probabilities=model.predict_proba(B)[:,1]
        all_probabilities.append(probabilities)
        metrics.append({"member":index+1,
            "accuracy":round(float(accuracy_score(y_test,probabilities>=.5)),4),
            "log_loss":round(float(log_loss(y_test,np.clip(probabilities,1e-6,1-1e-6))),4),
            "iterations":int(model.n_iter_)})
        members.append({"w1":model.coefs_[0].T.tolist(),"b1":model.intercepts_[0].tolist(),
            "w2":model.coefs_[1].T.tolist(),"b2":model.intercepts_[1].tolist(),
            "w3":model.coefs_[2].T.tolist(),"b3":model.intercepts_[2].tolist(),
            "w4":model.coefs_[3][:,0].tolist(),"b4":float(model.intercepts_[3][0])})
        print(f"MODEL {index+1}/10 {metrics[-1]}",flush=True)
    ensemble_probabilities=np.mean(all_probabilities,axis=0)
    evaluation={
        "split":"chronological_purged",
        "cutoff_timestamp_ms":int(cutoff),
        "purged_rows":int(purged_rows),
        "positive_rate":round(float(y_test.mean()),6),
        "majority_class_baseline_accuracy":round(float(max(y_test.mean(),1.0-y_test.mean())),4),
        "prevalence_baseline_log_loss":round(float(log_loss(y_test,np.full(len(y_test),float(y_train.mean())),labels=[0,1])),4),
        "ensemble_accuracy":round(float(accuracy_score(y_test,ensemble_probabilities>=0.5)),4),
        "ensemble_balanced_accuracy":round(float(balanced_accuracy_score(y_test,ensemble_probabilities>=0.5)),4),
        "ensemble_log_loss":round(float(log_loss(y_test,np.clip(ensemble_probabilities,1e-6,1-1e-6))),4),
        "ensemble_brier_score":round(float(brier_score_loss(y_test,ensemble_probabilities)),4),
        "ensemble_roc_auc":round(float(roc_auc_score(y_test,ensemble_probabilities)),4)
    }
    output={"format":"amir-wolf-ensemble-v1","trained_at_utc":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),
        "interval":INTERVAL,"horizon_bars":HORIZON,"symbols":sorted(used_symbols),"symbols_count":len(used_symbols),"samples":int(len(X)),
        "train_samples":int(len(X_train)),"test_samples":int(len(X_test)),
        "feature_mean":scaler.mean_.tolist(),"feature_scale":scaler.scale_.tolist(),
        "evaluation":evaluation,"metrics":metrics,"members":members}
    os.makedirs("app/src/main/assets",exist_ok=True)
    with open("app/src/main/assets/wolf_pretrained_ensemble.json","w") as file:
        json.dump(output,file,separators=(",",":"))
    with open("wolf_model_training_report.json","w") as file:
        json.dump({key:value for key,value in output.items() if key!="members"},file,indent=2)
    print(f"PURGED HOLDOUT: {evaluation}",flush=True)
    print(f"EXPORTED ten trained networks from {len(X)} real labeled samples.",flush=True)

if __name__=="__main__":
    main()
