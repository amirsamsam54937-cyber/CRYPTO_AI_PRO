"""Advanced, explainable OHLCV feature pack for research and paper trading.

No model is trained on future data: all values use candles supplied by caller.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from app.indicators import atr, ema, macd, rsi


def _adx(frame: pd.DataFrame, period: int = 14) -> float:
    high, low, close = (frame[c].astype(float) for c in ("high", "low", "close"))
    up = high.diff()
    down = -low.diff()
    plus_dm = up.where((up > down) & (up > 0), 0.0)
    minus_dm = down.where((down > up) & (down > 0), 0.0)
    prev = close.shift(1)
    tr = pd.concat([(high-low), (high-prev).abs(), (low-prev).abs()], axis=1).max(axis=1)
    smooth_tr = tr.ewm(alpha=1/period, adjust=False).mean().replace(0, np.nan)
    plus_di = 100 * plus_dm.ewm(alpha=1/period, adjust=False).mean() / smooth_tr
    minus_di = 100 * minus_dm.ewm(alpha=1/period, adjust=False).mean() / smooth_tr
    dx = 100 * (plus_di-minus_di).abs() / (plus_di+minus_di).replace(0, np.nan)
    value = dx.ewm(alpha=1/period, adjust=False).mean().iloc[-1]
    return float(0 if pd.isna(value) else value)


def analyze_ohlcv(frame: pd.DataFrame) -> dict:
    """Return trend, momentum, volatility, volume and candle-quality diagnostics."""
    required = {"open", "high", "low", "close", "volume"}
    if not required.issubset(frame.columns) or len(frame) < 60:
        raise ValueError("OHLCV data with at least 60 candles is required")
    f = frame.copy()
    for col in required:
        f[col] = pd.to_numeric(f[col], errors="coerce")
    if f[list(required)].isna().any().any() or (f[["open","high","low","close"]] <= 0).any().any():
        raise ValueError("OHLCV contains missing or invalid values")
    if (f["high"] < f[["open","close","low"]].max(axis=1)).any() or (f["low"] > f[["open","close","high"]].min(axis=1)).any():
        raise ValueError("Invalid candle high/low bounds")

    close, volume = f["close"], f["volume"]
    last = float(close.iloc[-1])
    e20, e50, e200 = ema(close,20), ema(close,50), ema(close, min(200, len(f)-1))
    rv = float(rsi(close).iloc[-1])
    macd_line, macd_sig, macd_hist = macd(close)
    av = float(atr(f).iloc[-1])
    atr_pct = 100 * av / last if last else 0.0
    mid = close.rolling(20).mean().iloc[-1]
    std = close.rolling(20).std(ddof=0).iloc[-1]
    upper, lower = mid + 2*std, mid - 2*std
    bb_width_pct = float((upper-lower)/mid*100) if mid else 0.0
    bb_position = float((last-lower)/(upper-lower)) if upper != lower else 0.5
    vol_mean = float(volume.rolling(20).mean().iloc[-1])
    volume_ratio = float(volume.iloc[-1]/vol_mean) if vol_mean > 0 else 0.0

    typical = (f["high"] + f["low"] + close) / 3
    vwap_den = float(volume.rolling(24).sum().iloc[-1])
    vwap = float((typical*volume).rolling(24).sum().iloc[-1]/vwap_den) if vwap_den > 0 else last
    adx = _adx(f)
    score, reasons = 0, []
    checks = [
        (e20.iloc[-1] > e50.iloc[-1], "EMA20/50 bullish", "EMA20/50 bearish"),
        (last > e200.iloc[-1], "Above long EMA", "Below long EMA"),
        (macd_line.iloc[-1] > macd_sig.iloc[-1], "MACD bullish", "MACD bearish"),
        (rv >= 55, "RSI positive momentum", "RSI negative momentum"),
        (last > vwap, "Above rolling VWAP", "Below rolling VWAP"),
    ]
    for bullish, positive, negative in checks:
        score += 1 if bullish else -1
        reasons.append(positive if bullish else negative)
    if volume_ratio >= 1.5:
        reasons.append("Volume expansion")
    if adx >= 25:
        reasons.append("ADX confirms trend strength")
    regime = "TRENDING" if adx >= 25 else "RANGING"
    direction = "LONG_BIAS" if score >= 3 else "SHORT_BIAS" if score <= -3 else "NEUTRAL"
    confidence = min(95.0, abs(score)/5*80 + (min(adx,40)/40)*15)
    return {
        "direction": direction, "score": score, "confidence": round(confidence,1),
        "last_price": last, "rsi": round(rv,2), "macd_histogram": round(float(macd_hist.iloc[-1]),8),
        "atr": round(av,10), "atr_percent": round(atr_pct,3), "adx": round(adx,2),
        "regime": regime, "bb_width_percent": round(bb_width_pct,3),
        "bb_position": round(bb_position,3), "volume_ratio": round(volume_ratio,3),
        "rolling_vwap": round(vwap,10), "reasons": reasons,
    }
