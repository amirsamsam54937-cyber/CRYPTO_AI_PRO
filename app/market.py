"""Public CCXT data access. No API credentials or order methods."""
import ccxt
import pandas as pd

def make_exchange(exchange_id: str="binanceusdm"):
    exchange_cls=getattr(ccxt,exchange_id,None)
    if exchange_cls is None: raise ValueError(f"Unsupported exchange: {exchange_id}")
    return exchange_cls({"enableRateLimit":True,"timeout":15000})

def fetch_ohlcv(symbol: str="BTC/USDT:USDT", timeframe: str="1h", limit: int=300,
                exchange_id: str="binanceusdm") -> pd.DataFrame:
    if not 50 <= limit <= 1000: raise ValueError("limit must be between 50 and 1000")
    rows=make_exchange(exchange_id).fetch_ohlcv(symbol,timeframe=timeframe,limit=limit)
    if not rows: raise RuntimeError("Exchange returned no candles")
    frame=pd.DataFrame(rows,columns=["timestamp","open","high","low","close","volume"])
    frame["timestamp"]=pd.to_datetime(frame["timestamp"],unit="ms",utc=True)
    return frame
