"""Always-on public-market research service for AMIR ARSALAN TRADER WOLF.
No exchange keys, accounts, or order execution. Requires managed PostgreSQL in production.
"""
from __future__ import annotations
import asyncio, json, os, time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any
import ccxt
import pandas as pd
import psycopg
from fastapi import FastAPI, Header, HTTPException
from app.advanced_analysis import analyze_ohlcv

DATABASE_URL=os.environ["DATABASE_URL"]
API_TOKEN=os.environ["WOLF_CLOUD_TOKEN"]
EXCHANGE_ID=os.getenv("WOLF_EXCHANGE","binanceusdm")
SCAN_SECONDS=max(120,int(os.getenv("WOLF_SCAN_SECONDS","300")))
UNIVERSE_SIZE=max(10,min(100,int(os.getenv("WOLF_UNIVERSE_SIZE","40"))))
TIMEFRAMES=("15m","1h","4h","1d")
exchange=None
scan_state={"running":False,"last_scan":None,"symbols":0,"records":0,"error":None}

def db():
    return psycopg.connect(DATABASE_URL, autocommit=True)

def init_db():
    with db() as c:
        c.execute("""
        CREATE TABLE IF NOT EXISTS wolf_market_memory(
          id BIGSERIAL PRIMARY KEY, exchange TEXT NOT NULL, symbol TEXT NOT NULL,
          timeframe TEXT NOT NULL, candle_time TIMESTAMPTZ NOT NULL,
          payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
          UNIQUE(exchange,symbol,timeframe,candle_time));
        CREATE INDEX IF NOT EXISTS wolf_memory_recent
          ON wolf_market_memory(exchange,symbol,timeframe,candle_time DESC);
        CREATE TABLE IF NOT EXISTS wolf_learning_outcomes(
          id BIGSERIAL PRIMARY KEY, memory_id BIGINT UNIQUE REFERENCES wolf_market_memory(id),
          horizon_return DOUBLE PRECISION, direction_label SMALLINT,
          resolved_at TIMESTAMPTZ DEFAULT now());
        """)

def auth(authorization: str|None):
    if not authorization or authorization != "Bearer "+API_TOKEN:
        raise HTTPException(status_code=401,detail="Unauthorized")

def get_exchange():
    global exchange
    if exchange is None:
        cls=getattr(ccxt,EXCHANGE_ID)
        exchange=cls({"enableRateLimit":True,"timeout":20000})
        exchange.load_markets()
    return exchange

def scan_once():
    ex=get_exchange()
    tickers=ex.fetch_tickers()
    candidates=[]
    for symbol,market in ex.markets.items():
        if not market.get("active") or not market.get("swap") or market.get("quote")!="USDT":
            continue
        ticker=tickers.get(symbol) or {}
        turnover=float(ticker.get("quoteVolume") or 0)
        if turnover>0: candidates.append((turnover,symbol))
    candidates=sorted(candidates,reverse=True)[:UNIVERSE_SIZE]
    written=0
    for _,symbol in candidates:
        for tf in TIMEFRAMES:
            try:
                rows=ex.fetch_ohlcv(symbol,tf,limit=260)
                if len(rows)<80: continue
                # Exclude current, possibly incomplete candle.
                rows=rows[:-1]
                frame=pd.DataFrame(rows,columns=["timestamp","open","high","low","close","volume"])
                result=analyze_ohlcv(frame)
                candle_time=datetime.fromtimestamp(int(rows[-1][0])/1000,tz=timezone.utc)
                payload={"direction":result["direction"],"score":result["score"],
                  "confidence":result["confidence"],"price":result["last_price"],
                  "rsi":result["rsi"],"macd_histogram":result["macd_histogram"],
                  "atr":result["atr"],"atr_percent":result["atr_percent"],
                  "adx":result["adx"],"regime":result["regime"],
                  "volume_ratio":result["volume_ratio"],"vwap":result["rolling_vwap"],
                  "reasons":result["reasons"],"candles":len(frame)}
                with db() as c:
                    c.execute("""INSERT INTO wolf_market_memory
                    (exchange,symbol,timeframe,candle_time,payload)
                    VALUES(%s,%s,%s,%s,%s::jsonb)
                    ON CONFLICT(exchange,symbol,timeframe,candle_time) DO NOTHING""",
                    (EXCHANGE_ID,symbol,tf,candle_time,json.dumps(payload)))
                    written+=1
            except Exception:
                continue
    return len(candidates),written

def resolve_outcomes():
    # Label historical observations only after 24h; labels are price-direction outcomes,
    # not simulated fills or verified profits.
    with db() as c:
        rows=c.execute("""
          SELECT m.id,m.payload,m.symbol,m.exchange,m.candle_time
          FROM wolf_market_memory m
          LEFT JOIN wolf_learning_outcomes o ON o.memory_id=m.id
          WHERE o.id IS NULL AND m.timeframe='1h'
            AND m.candle_time < now()-interval '24 hours'
          ORDER BY m.candle_time DESC LIMIT 500
        """).fetchall()
    ex=get_exchange()
    for mid,payload,symbol,exchange_id,candle_time in rows:
        try:
            candles=ex.fetch_ohlcv(symbol,"1h",since=int(candle_time.timestamp()*1000),limit=30)
            later=[x for x in candles if x[0] >= int((candle_time.timestamp()+24*3600)*1000)]
            if not later: continue
            start=float(payload["price"]); end=float(later[0][4])
            ret=(end/start)-1.0
            label=1 if ret>0.001 else (-1 if ret< -0.001 else 0)
            with db() as c:
                c.execute("""INSERT INTO wolf_learning_outcomes
                (memory_id,horizon_return,direction_label) VALUES(%s,%s,%s)
                ON CONFLICT(memory_id) DO NOTHING""",(mid,ret,label))
        except Exception: continue

async def worker():
    while True:
        try:
            scan_state["running"]=True
            symbols,records=await asyncio.to_thread(scan_once)
            await asyncio.to_thread(resolve_outcomes)
            scan_state.update(last_scan=datetime.now(timezone.utc).isoformat(),
                symbols=symbols,records=records,error=None)
        except Exception as exc:
            scan_state["error"]=type(exc).__name__
        finally:
            scan_state["running"]=False
        await asyncio.sleep(SCAN_SECONDS)

@asynccontextmanager
async def lifespan(app:FastAPI):
    await asyncio.to_thread(init_db)
    task=asyncio.create_task(worker())
    yield
    task.cancel()
    try: await task
    except asyncio.CancelledError: pass

app=FastAPI(title="AMIR ARSALAN TRADER WOLF Cloud Brain",version="1.0.0",lifespan=lifespan)

@app.get("/health")
def health():
    return {"status":"ok","service":"wolf-cloud-brain","exchange":EXCHANGE_ID,
            "scan_seconds":SCAN_SECONDS,"last_scan":scan_state["last_scan"]}

@app.get("/v1/status")
def status(authorization:str|None=Header(default=None)):
    auth(authorization)
    with db() as c:
        total=c.execute("SELECT count(*) FROM wolf_market_memory").fetchone()[0]
        labels=c.execute("SELECT count(*) FROM wolf_learning_outcomes").fetchone()[0]
    return {**scan_state,"stored_market_observations":total,
            "resolved_learning_labels":labels,"learning_mode":"outcome-journal"}

@app.get("/v1/signals")
def signals(timeframe:str="1h",limit:int=50,authorization:str|None=Header(default=None)):
    auth(authorization)
    if timeframe not in TIMEFRAMES: raise HTTPException(400,"Unsupported timeframe")
    limit=max(1,min(limit,100))
    with db() as c:
        rows=c.execute("""SELECT DISTINCT ON(symbol) symbol,candle_time,payload
          FROM wolf_market_memory WHERE exchange=%s AND timeframe=%s
          ORDER BY symbol,candle_time DESC LIMIT %s""",(EXCHANGE_ID,timeframe,limit)).fetchall()
    return [{"symbol":s,"time":t.isoformat(),"analysis":p} for s,t,p in rows]

@app.get("/v1/learning")
def learning(authorization:str|None=Header(default=None)):
    auth(authorization)
    with db() as c:
        n,avg,up=c.execute("""SELECT count(*),avg(horizon_return),
          avg((direction_label=1)::int) FROM wolf_learning_outcomes""").fetchone()
    return {"labeled_observations":n,"mean_24h_return":avg,
            "upward_fraction":up,"note":"Descriptive outcome memory, not proof of strategy profitability."}
