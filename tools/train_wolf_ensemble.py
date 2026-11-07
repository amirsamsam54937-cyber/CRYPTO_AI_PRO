#!/usr/bin/env python3
"""Train ten compact MLPs on historical Binance USD-M Futures candles."""
import json, os, time, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
import numpy as np
from sklearn.neural_network import MLPClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, balanced_accuracy_score, brier_score_loss, log_loss, roc_auc_score
try:
    from tools.time_series_validation import chronological_masks
except ModuleNotFoundError:  # direct execution as `python tools/train_wolf_ensemble.py`
    from time_series_validation import chronological_masks

BASE_URL = "https://fapi.binance.com"
SYMBOLS = ["BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT","DOGEUSDT","ADAUSDT","LINKUSDT","AVAXUSDT","LTCUSDT","TRXUSDT","DOTUSDT","BCHUSDT","ETCUSDT","ATOMUSDT","NEARUSDT","APTUSDT","ARBUSDT","OPUSDT","FILUSDT"]
INTERVAL, PAGE_LIMIT, HISTORY_BARS, HORIZON = "1h", 1500, 6000, 6

def get_json(path):
    # Use only Binance's canonical public USD-M endpoint. Never rotate hosts or mirrors
    # after a regional-eligibility denial.
    req = urllib.request.Request(BASE_URL + path, headers={"User-Agent":"AmirWolf-Research/2.14"})
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return json.loads(response.read())
    except Exception as exc:
        raise RuntimeError(f"Canonical Binance public futures endpoint unavailable: {exc}") from exc

def get_history(symbol):
    # Historical training uses the same canonical public endpoint as the app.
    # If Binance denies access, skip the symbol rather than trying a mirror.
    rows=[]
    start=int(time.time()*1000)-HISTORY_BARS*60*60*1000
    while len(rows)<HISTORY_BARS:
        query=f"/fapi/v1/klines?symbol={symbol}&interval={INTERVAL}&limit={PAGE_LIMIT}&startTime={start}"
        page=get_json(query)
        if not page:
            break
        rows.extend(page)
        next_start=int(page[-1][6])+1
        if next_start<=start:
            break
        start=next_start
        if len(page)<PAGE_LIMIT:
            break
        time.sleep(.08)
    unique={int(row[0]):row for row in rows}
    return [unique[key] for key in sorted(unique)][-HISTORY_BARS:]


def ema(values, period):
    result = np.empty(len(values), dtype=float)
    result[0] = values[0]
    alpha = 2.0 / (period + 1.0)
    for i in range(1, len(values)):
        result[i] = alpha * values[i] + (1.0 - alpha) * result[i-1]
    return result

def make_rows(raw):
    a = np.asarray(raw, dtype=float)
    o,h,l,c,v = a[:,1],a[:,2],a[:,3],a[:,4],a[:,5]
    e20,e50 = ema(c,20),ema(c,50)
    delta = np.diff(c, prepend=c[0])
    gain,loss = np.maximum(delta,0),np.maximum(-delta,0)
    ag,al = ema(gain,14),ema(loss,14)
    rsi = 100.0 - 100.0/(1.0 + ag/np.maximum(al,1e-9))
    tr = np.maximum(h-l,np.maximum(abs(h-np.r_[c[0],c[:-1]]),abs(l-np.r_[c[0],c[:-1]])))
    atr = ema(tr,14)
    rv = v/np.maximum(ema(v,20),1e-9)
    adx_proxy = np.clip(ema(abs(np.diff(e20,prepend=e20[0]))/np.maximum(atr,1e-9)*100,14),0,60)
    trend = np.where(e20>=e50,1.0,-1.0)
    raw_score = np.clip(trend*25.0 + (50.0-rsi)*.55 + np.clip(rv-1.0,-1.0,2.0)*8.0,-100,100)
    direction = np.where(raw_score>=0,1.0,-1.0)
    body = (c-o)/np.maximum(h-l,1e-9)
    atr_pct = atr/np.maximum(c,1e-9)*100.0
    # Feature order and transformations mirror WolfPretrainedEnsemble.predict().
    X = np.column_stack([
        trend*direction,
        ((rsi-50.0)/50.0)*direction,
        np.clip(adx_proxy/50.0,0.0,1.0),
        np.clip((rv-1.0)/2.0,-1.0,1.0),
        np.where(body>.25,1.0,np.where(body<-.25,-1.0,0.0))*direction,
        np.clip((atr_pct-2.0)/5.0,-1.0,1.0),
        np.abs(raw_score)/100.0
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

def main():
    chunks=[]
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures={pool.submit(fetch_symbol_samples,symbol):symbol for symbol in SYMBOLS}
        for future in as_completed(futures):
            symbol=futures[future]
            try:
                _,x,y,t=future.result()
                if len(x)>100:
                    chunks.append((x,y,t))
                print(f"{symbol}: {len(x)} labeled samples",flush=True)
            except Exception as exc:
                print(f"SKIP {symbol}: {exc}",flush=True)
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
        "interval":INTERVAL,"horizon_bars":HORIZON,"symbols":SYMBOLS,"samples":int(len(X)),
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
