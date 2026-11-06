from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic_settings import BaseSettings, SettingsConfigDict
from app.market import fetch_ohlcv
from app.indicators import build_signal
from app.exchanges.xt_futures import XTFuturesClient, XTAPIError

class Settings(BaseSettings):
    app_name: str="CRYPTO AI PRO"
    exchange_id: str="binance"
    default_symbol: str="BTC/USDT"
    default_timeframe: str="1h"
    candle_limit: int=300
    model_config=SettingsConfigDict(env_file=".env",extra="ignore")

settings=Settings()
app=FastAPI(title=settings.app_name,version="0.1.0",
            description="Market research, paper trading, and authenticated XT account read access. No live orders.")

@app.get("/",response_class=HTMLResponse)
def dashboard():
    return """<!doctype html><html lang="fa" dir="rtl"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>CRYPTO AI PRO</title>
<style>body{font-family:system-ui;background:#0b1220;color:#e8eefc;margin:0;padding:24px}
main{max-width:760px;margin:auto}section{background:#172238;padding:18px;border-radius:14px;margin:14px 0}
button,input{padding:12px;border-radius:8px;border:0;margin:4px}button{background:#48a6ff;color:#061326}
pre{white-space:pre-wrap;overflow-wrap:anywhere}</style><main><h1>CRYPTO AI PRO</h1>
<p>داشبورد تحلیل بازار — نسخه پایه</p><section>نماد <input id="symbol" value="BTC/USDT">
بازه <input id="tf" value="1h"><button onclick="run()">تحلیل بازار</button></section>
<section><h3>نتیجه تحلیل</h3><pre id="out">برای دریافت تحلیل دکمه را بزنید.</pre></section>
<p>این نسخه فقط داده عمومی و تحلیل ارائه می‌کند؛ معامله واقعی انجام نمی‌دهد.</p></main>
<script>async function run(){let o=document.getElementById('out');o.textContent='در حال دریافت داده...';
try{let s=encodeURIComponent(document.getElementById('symbol').value),t=encodeURIComponent(document.getElementById('tf').value);
let r=await fetch('/api/signal/'+s+'?timeframe='+t),j=await r.json();if(!r.ok)throw Error(j.detail||'خطا');
o.textContent=JSON.stringify(j,null,2)}catch(e){o.textContent='خطا: '+e.message}}</script></html>"""

@app.get("/health")
def health():
    return {"status":"ok","app":settings.app_name,"mode":"research-paper-and-xt-read-only"}

@app.get("/api/xt/connection")
def xt_connection():
    """Check XT futures account authentication without placing orders."""
    try:
        return XTFuturesClient().connection_check()
    except XTAPIError as exc:
        raise HTTPException(status_code=503, detail=str(exc))


@app.get("/api/xt/balances")
def xt_balances(account_id: str = Query(..., min_length=1)):
    """Read XT futures balances. Account ID comes from the authenticated account."""
    try:
        return XTFuturesClient().balances(account_id)
    except (XTAPIError, ValueError) as exc:
        raise HTTPException(status_code=503, detail=str(exc))


@app.get("/api/signal/{symbol:path}")
def signal(symbol: str,timeframe: str=Query(default=None)):
    try:
        tf=timeframe or settings.default_timeframe
        frame=fetch_ohlcv(symbol=symbol,timeframe=tf,limit=settings.candle_limit,
                          exchange_id=settings.exchange_id)
        result=build_signal(frame)
        result.update({"symbol":symbol,"timeframe":tf,"candles":len(frame),"mode":"research-only"})
        return result
    except (ValueError,RuntimeError) as exc:
        raise HTTPException(status_code=400,detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502,detail="Market data provider unavailable; retry later.")
