#!/usr/bin/env python3
"""Train ten compact MLPs on historical Binance USD-M Futures candles."""
import json, os, time, urllib.request, io, zipfile, csv, datetime
from concurrent.futures import ThreadPoolExecutor, as_completed
import numpy as np
from sklearn.neural_network import MLPClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, log_loss

BASES = ["https://fapi.binance.com", "https://fapi1.binance.com", "https://fapi2.binance.com"]
SYMBOLS = ["BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT","DOGEUSDT","ADAUSDT","LINKUSDT","AVAXUSDT","LTCUSDT","TRXUSDT","DOTUSDT","BCHUSDT","ETCUSDT","ATOMUSDT","NEARUSDT","APTUSDT","ARBUSDT","OPUSDT","FILUSDT"]
INTERVAL, PAGE_LIMIT, HISTORY_BARS, HORIZON = "1h", 1500, 6000, 6

def get_json(path):
    error = None
    for base in BASES:
        try:
            req = urllib.request.Request(base + path, headers={"User-Agent":"AmirWolf-Research/1.0"})
            with urllib.request.urlopen(req, timeout=20) as response:
                return json.loads(response.read())
        except Exception as exc:
            error = exc
    raise RuntimeError(f"Binance historical candles unavailable: {error}")

def download_archive_month(symbol,period):
    filename=f"{symbol}-{INTERVAL}-{period}.zip"
    url=f"https://data.binance.vision/data/futures/um/monthly/klines/{symbol}/{INTERVAL}/{filename}"
    request=urllib.request.Request(url,headers={"User-Agent":"AmirWolf-Research/1.0"})
    with urllib.request.urlopen(request,timeout=12) as response:
        archive=zipfile.ZipFile(io.BytesIO(response.read()))
    result=[]
    with archive.open(archive.namelist()[0]) as stream:
        reader=csv.reader(io.TextIOWrapper(stream,encoding="utf-8"))
        for row in reader:
            try:
                timestamp=int(float(row[0]))
                values=[timestamp]+[float(value) for value in row[1:6]]
                close_time=int(float(row[6])) if len(row)>6 and row[6] else timestamp+3599999
                result.append(values+[close_time])
            except (ValueError,IndexError):
                continue
    return result

def get_archive_history(symbol):
    today=datetime.datetime.now(datetime.timezone.utc).date()
    periods=[]
    for offset in range(6,0,-1):
        month_index=today.year*12+(today.month-1)-offset
        year,month=divmod(month_index,12)
        periods.append(f"{year:04d}-{month+1:02d}")
    rows=[]
    with ThreadPoolExecutor(max_workers=12) as pool:
        futures={pool.submit(download_archive_month,symbol,period):period for period in periods}
        for future in as_completed(futures):
            try:
                rows.extend(future.result())
            except Exception as exc:
                print(f"ARCHIVE {symbol} {futures[future]}: {exc}",flush=True)
    unique={int(row[0]):row for row in rows}
    return [unique[key] for key in sorted(unique)]

def get_history(symbol):
    # Prefer Binance's official public USD-M Futures archive; it avoids regional REST API restrictions.
    archived=get_archive_history(symbol)
    if len(archived)>500:
        return archived
    # REST fallback for environments where the public archive is unavailable.
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

def main():
    chunks=[]
    for symbol in SYMBOLS:
        try:
            raw=get_history(symbol)
            x,y,t=make_rows(raw)
            if len(x)>100:
                chunks.append((x,y,t))
            print(f"{symbol}: {len(x)} labeled samples",flush=True)
        except Exception as exc:
            print(f"SKIP {symbol}: {exc}",flush=True)
        time.sleep(.15)
    if not chunks:
        raise SystemExit("No real historical data received; refusing to create fake pretrained weights.")
    X=np.vstack([item[0] for item in chunks])
    y=np.concatenate([item[1] for item in chunks])
    timestamps=np.concatenate([item[2] for item in chunks])
    order=np.argsort(timestamps,kind="stable")
    X,y=X[order],y[order]
    if len(X)<500 or len(np.unique(y))<2:
        raise SystemExit("Insufficient labeled history or only one class; no model exported.")
    unique_times=np.unique(timestamps)
    cutoff=unique_times[min(len(unique_times)-1,int(len(unique_times)*.8))]
    train_mask=timestamps<cutoff
    test_mask=timestamps>=cutoff
    X_train,X_test=X[train_mask],X[test_mask]
    y_train,y_test=y[train_mask],y[test_mask]
    scaler=StandardScaler().fit(X_train)
    A,B=scaler.transform(X_train),scaler.transform(X_test)
    members,metrics=[],[]
    for index in range(10):
        model=MLPClassifier(hidden_layer_sizes=(64,32,16),activation="tanh",solver="adam",
            alpha=.002,learning_rate_init=.0015,max_iter=300,early_stopping=True,
            validation_fraction=.15,n_iter_no_change=22,random_state=20261107+index)
        model.fit(A,y_train)
        probabilities=model.predict_proba(B)[:,1]
        metrics.append({"member":index+1,
            "accuracy":round(float(accuracy_score(y_test,probabilities>=.5)),4),
            "log_loss":round(float(log_loss(y_test,np.clip(probabilities,1e-6,1-1e-6))),4),
            "iterations":int(model.n_iter_)})
        members.append({"w1":model.coefs_[0].T.tolist(),"b1":model.intercepts_[0].tolist(),
            "w2":model.coefs_[1].T.tolist(),"b2":model.intercepts_[1].tolist(),
            "w3":model.coefs_[2].T.tolist(),"b3":model.intercepts_[2].tolist(),
            "w4":model.coefs_[3][:,0].tolist(),"b4":float(model.intercepts_[3][0])})
        print(f"MODEL {index+1}/10 {metrics[-1]}",flush=True)
    output={"format":"amir-wolf-ensemble-v1","trained_at_utc":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),
        "interval":INTERVAL,"horizon_bars":HORIZON,"symbols":SYMBOLS,"samples":int(len(X)),
        "train_samples":int(len(X_train)),"test_samples":int(len(X_test)),
        "feature_mean":scaler.mean_.tolist(),"feature_scale":scaler.scale_.tolist(),
        "metrics":metrics,"members":members}
    os.makedirs("app/src/main/assets",exist_ok=True)
    with open("app/src/main/assets/wolf_pretrained_ensemble.json","w") as file:
        json.dump(output,file,separators=(",",":"))
    with open("wolf_model_training_report.json","w") as file:
        json.dump({key:value for key,value in output.items() if key!="members"},file,indent=2)
    print(f"EXPORTED ten trained networks from {len(X)} real labeled samples.",flush=True)

if __name__=="__main__":
    main()
