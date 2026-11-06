#!/usr/bin/env python3
"""Train a compact 10-member crypto-futures neural ensemble on historical Binance candles.
Exports only model parameters; no credentials or order functionality.
"""
import json, os, time, urllib.request
import numpy as np
from sklearn.neural_network import MLPClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, log_loss
from sklearn.model_selection import train_test_split

BASES = ["https://fapi.binance.com", "https://fapi1.binance.com", "https://fapi2.binance.com"]
SYMBOLS = ["BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT","DOGEUSDT","ADAUSDT","LINKUSDT","AVAXUSDT","LTCUSDT","TRXUSDT","DOTUSDT"]
INTERVAL = "1h"
LIMIT = 1500
HORIZON = 6

def get_json(path):
    last = None
    for base in BASES:
        try:
            req=urllib.request.Request(base+path,headers={"User-Agent":"AmirWolf-Research/1.0"})
            with urllib.request.urlopen(req,timeout=18) as r: return json.loads(r.read())
        except Exception as e: last=e
    raise RuntimeError(f"Binance historical data unavailable: {last}")

def ema(x,n):
    a=np.empty(len(x)); a[0]=x[0]; alpha=2/(n+1)
    for i in range(1,len(x)): a[i]=alpha*x[i]+(1-alpha)*a[i-1]
    return a

def make_rows(k):
    a=np.asarray(k,dtype=float)
    o,h,l,c,v=a[:,1],a[:,2],a[:,3],a[:,4],a[:,5]
    e20,e50,e200=ema(c,20),ema(c,50),ema(c,200)
    delta=np.diff(c,prepend=c[0]); gain=np.maximum(delta,0); loss=np.maximum(-delta,0)
    ag=ema(gain,14); al=ema(loss,14); rsi=100-100/(1+ag/np.maximum(al,1e-9))
    tr=np.maximum(h-l,np.maximum(abs(h-np.r_[c[0],c[:-1]]),abs(l-np.r_[c[0],c[:-1]])))
    atr=ema(tr,14)
    # Stable, bounded features matching the Android inference contract.
    trend=np.where(e20>=e50,1.,-1.)
    rv=v/np.maximum(ema(v,20),1e-9)
    adx=np.clip(ema(abs(np.diff(e20,prepend=e20[0]))/np.maximum(atr,1e-9)*100,14),0,60)
    body=(c-o)/np.maximum(h-l,1e-9)
    atrp=atr/np.maximum(c,1e-9)*100
    score=np.clip((trend*25)+(50-rsi)*.55+np.clip(rv-1,-1,2)*8, -100,100)
    X=np.column_stack([trend,(rsi-50)/50,np.clip(adx/50,0,1),np.clip((rv-1)/2,-1,1),np.clip(body,-1,1),np.clip((atrp-2)/5,-1,1),score/100])
    future=np.roll(c,-HORIZON)/c-1
    # Ignore the last horizon and neutral moves to reduce label noise.
    y=(future>0.0025).astype(int)
    mask=np.arange(len(c))<len(c)-HORIZON
    mask &= np.abs(future)>0.0025
    return X[mask],y[mask],a[:,0][mask]

def main():
    chunks=[]
    for sym in SYMBOLS:
        try:
            raw=get_json(f"/fapi/v1/klines?symbol={sym}&interval={INTERVAL}&limit={LIMIT}")
            x,y=make_rows(raw)
            if len(x)>100: chunks.append((x,y))
            print(f"{sym}: {len(x)} labeled rows",flush=True)
        except Exception as e: print(f"SKIP {sym}: {e}",flush=True)
        time.sleep(.12)
    if not chunks: raise SystemExit("No historical training data collected; refusing to create fake weights.")
    X=np.vstack([x for x,_ in chunks]); y=np.concatenate([z for _,z in chunks])
    if len(X)<500 or len(np.unique(y))<2: raise SystemExit("Insufficient balanced historical samples.")
    # Chronological split; scaler and networks only see the training segment.
    split=int(len(X)*.8); Xtr,Xte=X[:split],X[split:]; ytr,yte=y[:split],y[split:]
    scaler=StandardScaler().fit(Xtr); A=scaler.transform(Xtr); B=scaler.transform(Xte)
    members=[]; metrics=[]
    for i in range(10):
        model=MLPClassifier(hidden_layer_sizes=(12,8),activation="tanh",solver="adam",
            alpha=0.002,learning_rate_init=0.002,max_iter=220,early_stopping=True,
            validation_fraction=.15,n_iter_no_change=18,random_state=20260+i)
        model.fit(A,ytr)
        pred=model.predict_proba(B)[:,1]
        metrics.append({"member":i+1,"accuracy":round(float(accuracy_score(yte,pred>=.5)),4),
                        "log_loss":round(float(log_loss(yte,np.clip(pred,1e-6,1-1e-6))),4),
                        "iterations":int(model.n_iter_)})
        members.append({"w1":model.coefs_[0].T.tolist(),"b1":model.intercepts_[0].tolist(),
                        "w2":model.coefs_[1].T.tolist(),"b2":model.intercepts_[1].tolist(),
                        "w3":model.coefs_[2][:,0].tolist(),"b3":float(model.intercepts_[2][0])})
        print(f"trained member {i+1}/10: {metrics[-1]}",flush=True)
    out={"format":"amir-wolf-ensemble-v1","trained_at_utc":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),
         "interval":INTERVAL,"horizon_bars":HORIZON,"symbols":SYMBOLS,"samples":int(len(X)),
         "train_samples":int(len(Xtr)),"test_samples":int(len(Xte)),
         "feature_mean":scaler.mean_.tolist(),"feature_scale":scaler.scale_.tolist(),
         "metrics":metrics,"members":members}
    os.makedirs("app/src/main/assets",exist_ok=True)
    with open("app/src/main/assets/wolf_pretrained_ensemble.json","w") as f: json.dump(out,f,separators=(",",":"))
    with open("wolf_model_training_report.json","w") as f: json.dump({k:v for k,v in out.items() if k!="members"},f,indent=2)
    print(f"Exported genuine trained ensemble using {len(X)} labeled samples.")
if __name__=="__main__": main()
