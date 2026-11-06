"""Deterministic technical indicators and explainable baseline signal."""
import numpy as np
import pandas as pd

def ema(series: pd.Series, period: int) -> pd.Series:
    if period < 1: raise ValueError("period must be positive")
    return series.astype(float).ewm(span=period, adjust=False).mean()

def rsi(close: pd.Series, period: int = 14) -> pd.Series:
    if period < 2: raise ValueError("period must be at least 2")
    delta = close.astype(float).diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / period, adjust=False).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / period, adjust=False).mean()
    rs = gain / loss.replace(0, np.nan)
    return (100 - 100 / (1 + rs)).fillna(50)

def macd(close: pd.Series, fast: int = 12, slow: int = 26, signal: int = 9):
    line = ema(close, fast) - ema(close, slow)
    signal_line = ema(line, signal)
    return line, signal_line, line - signal_line

def atr(frame: pd.DataFrame, period: int = 14) -> pd.Series:
    if not {"high","low","close"}.issubset(frame.columns):
        raise ValueError("OHLCV frame must include high, low, close")
    prev = frame["close"].shift(1)
    tr = pd.concat([frame["high"]-frame["low"], (frame["high"]-prev).abs(),
                    (frame["low"]-prev).abs()], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / period, adjust=False).mean()

def build_signal(frame: pd.DataFrame) -> dict:
    if len(frame) < 50: raise ValueError("At least 50 candles are required")
    close = frame["close"].astype(float)
    fast, slow = ema(close,20), ema(close,50)
    rv = float(rsi(close).iloc[-1]); m, sig, _ = macd(close)
    score, reasons = 0, []
    if fast.iloc[-1] > slow.iloc[-1]: score += 1; reasons.append("EMA20 above EMA50")
    else: score -= 1; reasons.append("EMA20 below EMA50")
    if m.iloc[-1] > sig.iloc[-1]: score += 1; reasons.append("MACD above signal")
    else: score -= 1; reasons.append("MACD below signal")
    if rv >= 55: score += 1; reasons.append("RSI momentum positive")
    elif rv <= 45: score -= 1; reasons.append("RSI momentum negative")
    else: reasons.append("RSI momentum neutral")
    direction = "LONG_BIAS" if score >= 2 else "SHORT_BIAS" if score <= -2 else "NEUTRAL"
    return {"direction":direction,"score":score,"rsi":round(rv,2),
            "last_price":float(close.iloc[-1]),"atr":float(atr(frame).iloc[-1]),
            "reasons":reasons,"confidence":round(abs(score)/3*100,1)}
