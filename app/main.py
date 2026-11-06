from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic_settings import BaseSettings, SettingsConfigDict
from app.market import fetch_ohlcv
from app.indicators import build_signal
from app.advanced_analysis import analyze_ohlcv
from app.backtest import run_backtest

class Settings(BaseSettings):
    app_name: str="CRYPTO AI PRO"
    exchange_id: str="binanceusdm"
    default_symbol: str="BTC/USDT:USDT"
    default_timeframe: str="1h"
    candle_limit: int=300
    model_config=SettingsConfigDict(env_file=".env",extra="ignore")

settings=Settings()
app=FastAPI(title=settings.app_name,version="0.1.0",
            description="Public Binance futures market research, technical analysis and backtesting. No exchange credentials or live orders.")

@app.get("/",response_class=HTMLResponse)
def dashboard():
    return """<!doctype html><html lang="fa" dir="rtl"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>CRYPTO AI PRO</title>
<style>body{font-family:system-ui;background:#0b1220;color:#e8eefc;margin:0;padding:24px}
main{max-width:760px;margin:auto}section{background:#172238;padding:18px;border-radius:14px;margin:14px 0}
button,input{padding:12px;border-radius:8px;border:0;margin:4px}button{background:#48a6ff;color:#061326}
pre{white-space:pre-wrap;overflow-wrap:anywhere}</style><main><h1>CRYPTO AI PRO</h1>
<p>داشبورد تحلیل بازار — نسخه پایه</p><section>نماد <input id="symbol" value="BTC/USDT:USDT">
بازه <input id="tf" value="1h"><button onclick="run()">تحلیل بازار</button></section>
<section><h3>نتیجه تحلیل</h3><pre id="out">برای دریافت تحلیل دکمه را بزنید.</pre></section>
<p>این نسخه فقط داده عمومی و تحلیل ارائه می‌کند؛ معامله واقعی انجام نمی‌دهد.</p></main>
<script>async function run(){let o=document.getElementById('out');o.textContent='در حال دریافت داده...';
try{let s=encodeURIComponent(document.getElementById('symbol').value),t=encodeURIComponent(document.getElementById('tf').value);
let r=await fetch('/api/signal/'+s+'?timeframe='+t),j=await r.json();if(!r.ok)throw Error(j.detail||'خطا');
o.textContent=JSON.stringify(j,null,2)}catch(e){o.textContent='خطا: '+e.message}}</script></html>"""

@app.get("/health")
def health():
    return {"status":"ok","app":settings.app_name,"mode":"public-binance-analysis-only"}

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


@app.get("/api/analysis/advanced/{symbol:path}")
def advanced_analysis(symbol: str, timeframe: str = Query(default="1h")):
    """Explainable multi-factor analysis using the latest completed candles."""
    allowed = {"5m", "15m", "30m", "1h", "2h", "4h", "1d"}
    if timeframe not in allowed:
        raise HTTPException(status_code=400, detail="Unsupported timeframe")
    try:
        frame = fetch_ohlcv(symbol=symbol, timeframe=timeframe, limit=300,
                            exchange_id=settings.exchange_id)
        # Public market providers may include the live candle; discard it.
        if len(frame) > 60:
            frame = frame.iloc[:-1].reset_index(drop=True)
        result = analyze_ohlcv(frame)
        return {"symbol": symbol, "timeframe": timeframe,
                "mode": "research-only", "analysis": result}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502, detail="Market data provider unavailable; retry later.")



@app.get("/api/backtest/{symbol:path}")
def backtest(symbol: str, timeframe: str = Query(default="1h"),
             candles: int = Query(default=300, ge=150, le=1000),
             initial_equity: float = Query(default=1000.0, gt=0),
             risk_per_trade: float = Query(default=0.005, gt=0, le=0.02)):
    """Run a historical research simulation; never submits exchange orders."""
    allowed = {"5m", "15m", "30m", "1h", "2h", "4h", "1d"}
    if timeframe not in allowed:
        raise HTTPException(status_code=400, detail="Unsupported timeframe")
    try:
        frame = fetch_ohlcv(symbol=symbol, timeframe=timeframe, limit=candles,
                            exchange_id=settings.exchange_id)
        # Remove the live, unfinished candle before simulation.
        if len(frame) > 1:
            frame = frame.iloc[:-1].reset_index(drop=True)
        result = run_backtest(frame, initial_equity=initial_equity,
                              risk_per_trade=risk_per_trade)
        return {"symbol": symbol, "timeframe": timeframe,
                "mode": "historical-research-only", "results": result}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502, detail="Backtest data provider unavailable; retry later.")


@app.get("/api/consensus/{symbol:path}")
def consensus_analysis(symbol: str,
                       timeframe: str = Query(default="1h"),
                       exchange_id: str = Query(default=None)):
    """Ten explainable specialist votes over completed candles; read-only."""
    allowed = {"5m", "15m", "30m", "1h", "2h", "4h", "1d"}
    if timeframe not in allowed:
        raise HTTPException(status_code=400, detail="Unsupported timeframe")
    selected_exchange = (exchange_id or settings.exchange_id).lower()
    if selected_exchange not in {"binanceusdm"}:
        raise HTTPException(status_code=400, detail="Only Binance USDⓈ-M public futures data is supported")
    try:
        frame = fetch_ohlcv(symbol=symbol, timeframe=timeframe, limit=300,
                            exchange_id=selected_exchange)
        if len(frame) > 60:
            frame = frame.iloc[:-1].reset_index(drop=True)
        from app.multi_agent import analyze_consensus
        result = analyze_consensus(frame)
        return {"symbol": symbol, "exchange": selected_exchange,
                "timeframe": timeframe, "candles": len(frame),
                "mode": "read-only-research", "analysis": result}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502, detail="Market data provider unavailable; retry later.")
