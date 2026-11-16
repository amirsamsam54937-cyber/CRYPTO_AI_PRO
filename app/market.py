"""Public CCXT market-data adapter for XT USDT-M perpetual contracts.

This module intentionally has no credential handling and no order methods.
"""
import ccxt
import pandas as pd


def make_exchange(exchange_id: str = "xt"):
    exchange_cls = getattr(ccxt, exchange_id, None)
    if exchange_cls is None:
        raise ValueError(f"Unsupported exchange: {exchange_id}")
    options = {"defaultType": "swap"} if exchange_id.lower() == "xt" else {}
    return exchange_cls({
        "enableRateLimit": True,
        "timeout": 20000,
        "options": options,
    })


def fetch_ohlcv(
    symbol: str = "BTC/USDT:USDT",
    timeframe: str = "1h",
    limit: int = 300,
    exchange_id: str = "xt",
) -> pd.DataFrame:
    if not 50 <= limit <= 1000:
        raise ValueError("limit must be between 50 and 1000")
    exchange = make_exchange(exchange_id)
    rows = exchange.fetch_ohlcv(symbol, timeframe=timeframe, limit=limit)
    if not rows:
        raise RuntimeError("XT returned no candles; verify the perpetual symbol and API availability")
    frame = pd.DataFrame(rows, columns=["timestamp", "open", "high", "low", "close", "volume"])
    frame["timestamp"] = pd.to_datetime(frame["timestamp"], unit="ms", utc=True)
    return frame
