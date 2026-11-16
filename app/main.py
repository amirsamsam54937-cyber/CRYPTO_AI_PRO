from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic_settings import BaseSettings, SettingsConfigDict
from app.market import fetch_ohlcv
from app.indicators import build_signal
from app.advanced_analysis import analyze_ohlcv
from app.backtest import run_backtest

class Settings(BaseSettings):
    app_name: str="CRYPTO AI PRO"
    exchange_id: str="xt"
    default_symbol: str="BTC/USDT:USDT"
    default_timeframe: str="1h"
    candle_limit: int=300
    model_config=SettingsConfigDict(env_file=".env",extra="ignore")

settings=Settings()
app=FastAPI(title=settings.app_name,version="0.2.0",
            description="XT USDT-M public futures research and backtesting. No exchange credentials or live orders.")

@app.get("/",response_class=HTMLResponse)
def dashboard():
    return """<!doctype html><html lang="fa" dir="rtl"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>CRYPTO AI PRO</title>
<style>body{font-family:system-ui;background:#0b1220;color:#e8eefc;margin:0;padding:24px}
main{max-width:760px;margin:auto}section{background:#172238;padding:18px;border-radius:14px;margin:14px 0}
button,input{padding:12px;border-radius:8px;border:0;margin:4px}button{background:#48a6ff;color:#061326}
pre{white-space:pre-wrap;overflow-wrap:anywhere}</style><main><h1>CRYPTO AI PRO</h1>
<p>داشبورد تحلیل بازار — XT USDT-M</p><section>نماد <input id="symbol" value="BTC/USDT:USDT">
بازه <input id="tf" value="1h"><button onclick="run()">تحلیل بازار</button></section>
<section><h3>نتیجه تحلیل</h3><pre id="out">برای دریافت تحلیل دکمه را بزنید.</pre></section>
<p>این نسخه از داده عمومی XT برای پژوهش و بک‌تست استفاده می‌کند؛ سفارش واقعی ارسال نمی‌کند.</p></main>
<script>async function run(){let o=document.getElementById('out');o.textContent='در حال دریافت داده...';
try{let s=encodeURIComponent(document.getElementById('symbol').value),t=encodeURIComponent(document.getElementById('tf').value);
let r=await fetch('/api/signal/'+s+'?timeframe='+t),j=await r.json();if(!r.ok)throw Error(j.detail||'خطا');
o.textContent=JSON.stringify(j,null,2)}catch(e){o.textContent='خطا: '+e.message}}</script></html>"""

@app.get("/health")
def health():
    return {"status":"ok","app":settings.app_name,"exchange":"XT USDT-M","mode":"public-market-research-only"}

@app.get("/api/signal/{symbol:path}")
def signal(symbol: str,timeframe: str=Query(default=None)):
    try:
        tf=timeframe or settings.default_timeframe
        frame=fetch_ohlcv(symbol=symbol,timeframe=tf,limit=settings.candle_limit,
                          exchange_id=settings.exchange_id)
        result=build_signal(frame)
        result.update({"symbol":symbol,"timeframe":tf,"candles":len(frame),"exchange":"XT","mode":"research-only"})
        return result
    except (ValueError,RuntimeError) as exc:
        raise HTTPException(status_code=400,detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502,detail="XT public market data unavailable; retry later.")

@app.get("/api/analysis/advanced/{symbol:path}")
def advanced_analysis(symbol: str, timeframe: str = Query(default="1h")):
    allowed = {"5m", "15m", "30m", "1h", "2h", "4h", "1d"}
    if timeframe not in allowed:
        raise HTTPException(status_code=400, detail="Unsupported timeframe")
    try:
        frame = fetch_ohlcv(symbol=symbol, timeframe=timeframe, limit=300,
                            exchange_id=settings.exchange_id)
        if len(frame) > 60:
            frame = frame.iloc[:-1].reset_index(drop=True)
        result = analyze_ohlcv(frame)
        return {"symbol": symbol, "timeframe": timeframe,
                "exchange":"XT","mode": "research-only", "analysis": result}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502, detail="XT public market data unavailable; retry later.")

@app.get("/api/backtest/{symbol:path}")
def backtest(symbol: str, timeframe: str = Query(default="1h"),
             candles: int = Query(default=1000, ge=150, le=1000),
             initial_equity: float = Query(default=10.0, gt=0),
             risk_per_trade: float = Query(default=0.005, gt=0, le=0.02),
             taker_fee_rate: float = Query(default=0.0006, ge=0, le=0.02),
             slippage_rate: float = Query(default=0.0005, ge=0, le=0.02),
             funding_rate_8h: float = Query(default=0.0001, ge=0, le=0.02)):
    """XT historical simulation; starts with 10 virtual USDT and deducts both-side fees, slippage and estimated funding. No orders."""
    allowed = {"5m", "15m", "30m", "1h", "2h", "4h", "1d"}
    if timeframe not in allowed:
        raise HTTPException(status_code=400, detail="Unsupported timeframe")
    try:
        frame = fetch_ohlcv(symbol=symbol, timeframe=timeframe, limit=candles,
                            exchange_id=settings.exchange_id)
        if len(frame) > 1:
            frame = frame.iloc[:-1].reset_index(drop=True)
        result = run_backtest(frame, initial_equity=initial_equity,
                              risk_per_trade=risk_per_trade,
                              fee_rate=taker_fee_rate,
                              slippage_rate=slippage_rate,
                              funding_rate_8h=funding_rate_8h)
        return {"symbol": symbol, "timeframe": timeframe, "exchange":"XT",
                "mode": "historical-research-only",
                "fee_model":"taker fee charged on entry and exit; default is an estimate and can be overridden with XT account/contract rate",
                "results": result}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502, detail="XT backtest data unavailable; check symbol, network and market access.")

@app.get("/api/consensus/{symbol:path}")
def consensus_analysis(symbol: str,
                       timeframe: str = Query(default="1h"),
                       exchange_id: str = Query(default="xt")):
    """Ten explainable specialist votes over completed candles; read-only."""
    allowed = {"5m", "15m", "30m", "1h", "2h", "4h", "1d"}
    if timeframe not in allowed:
        raise HTTPException(status_code=400, detail="Unsupported timeframe")
    selected_exchange = (exchange_id or settings.exchange_id).lower()
    if selected_exchange != "xt":
        raise HTTPException(status_code=400, detail="Only XT USDT-M public futures data is configured")
    try:
        frame = fetch_ohlcv(symbol=symbol, timeframe=timeframe, limit=300,
                            exchange_id=selected_exchange)
        if len(frame) > 60:
            frame = frame.iloc[:-1].reset_index(drop=True)
        from app.multi_agent import analyze_consensus
        result = analyze_consensus(frame)
        return {"symbol":symbol,"exchange":"XT","timeframe":timeframe,
                "candles":len(frame),"mode":"read-only-research","analysis":result}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502, detail="XT public market data unavailable; retry later.")
