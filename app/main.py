from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import BaseModel, Field
from app.market import fetch_ohlcv
from app.indicators import build_signal
from app.advanced_analysis import analyze_ohlcv
from app.exchanges.xt_futures import XTFuturesClient, XTAPIError
from app.exchanges.xt_ccxt import xt_account_snapshot, XTCCXTError
from app.xt_scanner import scan_xt_usdt_perpetuals
from app.backtest import run_backtest
from app.online_learning import OnlineLearner

class Settings(BaseSettings):
    app_name: str="CRYPTO AI PRO"
    exchange_id: str="xt"
    default_symbol: str="BTC/USDT:USDT"
    default_timeframe: str="1h"
    candle_limit: int=300
    model_config=SettingsConfigDict(env_file=".env",extra="ignore")

settings=Settings()
learner = OnlineLearner()
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
    return {"status":"ok","app":settings.app_name,"mode":"research-paper-and-xt-read-only"}

@app.get("/api/xt/ccxt/dashboard")
def xt_ccxt_dashboard():
    """Authenticated XT futures dashboard via CCXT; read-only, no order endpoints."""
    try:
        return xt_account_snapshot(include_positions=True, include_open_orders=True)
    except XTCCXTError as exc:
        raise HTTPException(status_code=503, detail=str(exc))


@app.get("/api/xt/connection")
def xt_connection():
    """Check XT futures account authentication without placing orders."""
    try:
        return XTFuturesClient().connection_check()
    except XTAPIError as exc:
        raise HTTPException(status_code=503, detail=str(exc))


@app.get("/api/xt/dashboard")
def xt_dashboard():
    """Fetch XT account metadata and balances in one read-only request flow."""
    try:
        client = XTFuturesClient()
        info = client.account_info()
        account = info.get("result") or info.get("data") or {}
        account_id = account.get("accountId") if isinstance(account, dict) else None
        balance_data = client.balances(str(account_id)) if account_id else None
        return {"connection": {
            "connected": True,
            "account_id": account_id,
            "account_state": account.get("state") if isinstance(account, dict) else None,
            "allow_trade": account.get("allowTrade") if isinstance(account, dict) else None,
            "allow_open_position": account.get("allowOpenPosition") if isinstance(account, dict) else None,
        }, "balances": balance_data}
    except XTAPIError as exc:
        raise HTTPException(status_code=503, detail=str(exc))


@app.get("/api/xt/contract/{symbol}")
def xt_contract_detail(symbol: str):
    """Read XT contract metadata needed for precision and order validation."""
    try:
        detail = XTFuturesClient().contract_detail(symbol)
        return {"symbol": symbol, "contract": detail, "mode": "metadata-read-only"}
    except (XTAPIError, ValueError) as exc:
        raise HTTPException(status_code=503, detail=str(exc))


@app.get("/api/xt/balances")
def xt_balances(account_id: str = Query(..., min_length=1)):
    """Read XT futures balances. Account ID comes from the authenticated account."""
    try:
        return XTFuturesClient().balances(account_id)
    except (XTAPIError, ValueError) as exc:
        raise HTTPException(status_code=503, detail=str(exc))



class PaperFeedback(BaseModel):
    symbol: str = Field(min_length=1, max_length=80)
    direction: str
    outcome: int = Field(ge=0, le=1, description="1=win, 0=loss")
    metrics: dict[str, float]

@app.get("/api/learning/status")
def learning_status():
    return learner.status()

@app.post("/api/learning/feedback")
def learning_feedback(payload: PaperFeedback):
    try:
        return learner.record_resolved_trade(payload.symbol, payload.direction, payload.metrics, payload.outcome)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

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
        adaptive = learner.predict(result)
        return {"symbol": symbol, "timeframe": timeframe,
                "mode": "research-only", "analysis": result,
                "adaptive_learning": adaptive}
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


@app.get("/api/xt/scan")
def xt_scan(limit: int = Query(default=20, ge=1, le=50),
            min_quote_volume: float = Query(default=100000, gt=0)):
    """Scan liquid XT USDT perpetuals using closed 15m/1h/4h candles."""
    try:
        rows = scan_xt_usdt_perpetuals(limit=limit, min_quote_volume=min_quote_volume)
        return {"exchange": "XT", "market_type": "USDT perpetual",
                "count": len(rows), "results": rows,
                "mode": "public-data-research-only"}
    except Exception:
        raise HTTPException(status_code=502, detail="XT public market scanner unavailable; retry later.")
