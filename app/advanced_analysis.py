"""Advanced, explainable OHLCV feature pack for research and paper trading.

No model is trained on future data: all values use candles supplied by caller.
"""
from __future__ import annotations

from math import gcd

import numpy as np
import pandas as pd

from app.indicators import atr, ema, macd, rsi


def _directional_movement(frame: pd.DataFrame, period: int = 14) -> tuple[float, float, float]:
    """Return Wilder-smoothed +DI, -DI and ADX from the supplied closed candles."""
    high, low, close = (frame[c].astype(float) for c in ("high", "low", "close"))
    up = high.diff()
    down = -low.diff()
    plus_dm = up.where((up > down) & (up > 0), 0.0)
    minus_dm = down.where((down > up) & (down > 0), 0.0)
    prev = close.shift(1)
    tr = pd.concat([(high-low), (high-prev).abs(), (low-prev).abs()], axis=1).max(axis=1)
    smooth_tr = tr.ewm(alpha=1/period, adjust=False).mean().replace(0, np.nan)
    plus_di_series = 100 * plus_dm.ewm(alpha=1/period, adjust=False).mean() / smooth_tr
    minus_di_series = 100 * minus_dm.ewm(alpha=1/period, adjust=False).mean() / smooth_tr
    denominator = (plus_di_series + minus_di_series).replace(0, np.nan)
    dx = 100 * (plus_di_series-minus_di_series).abs() / denominator
    adx_series = dx.ewm(alpha=1/period, adjust=False).mean()
    last_values = (plus_di_series.iloc[-1], minus_di_series.iloc[-1], adx_series.iloc[-1])
    return tuple(float(0.0 if pd.isna(value) else value) for value in last_values)

def analyze_ohlcv(frame: pd.DataFrame) -> dict:
    """Return trend, momentum, volatility, volume and candle-quality diagnostics."""
    required = {"open", "high", "low", "close", "volume"}
    if not frame.columns.is_unique:
        raise ValueError("OHLCV column names must be unique")
    if not required.issubset(frame.columns) or len(frame) < 60:
        raise ValueError("OHLCV data with at least 60 candles is required")
    f = frame.copy()
    for col in required:
        f[col] = pd.to_numeric(f[col], errors="coerce")
    if f[list(required)].isna().any().any():
        raise ValueError("OHLCV contains missing or invalid values")
    values = f[["open", "high", "low", "close", "volume"]].to_numpy(dtype=float)
    if not np.isfinite(values).all():
        raise ValueError("OHLCV contains non-finite values")
    if (f[["open", "high", "low", "close"]] <= 0).any().any() or (f["volume"] < 0).any():
        raise ValueError("OHLCV prices must be positive and volume cannot be negative")
    if (f["high"] < f[["open", "close", "low"]].max(axis=1)).any() or (f["low"] > f[["open", "close", "high"]].min(axis=1)).any():
        raise ValueError("Invalid candle high/low bounds")
    timestamp_interval_seconds = None
    timestamp_gap_count = 0
    estimated_missing_candles = 0
    timestamp_irregular_interval_count = None
    timestamp_cadence_method = None
    timestamp_cadence_coverage_percent = None
    if "timestamp" in f.columns:
        timestamps = pd.to_datetime(f["timestamp"], utc=True, errors="coerce")
        if timestamps.isna().any() or timestamps.duplicated().any() or not timestamps.is_monotonic_increasing:
            raise ValueError("Candle timestamps must be valid, unique and strictly increasing")
        # Infer cadence from integer-second intervals. Their GCD can recover the
        # original timeframe even when missing candles are more common than intact
        # intervals. Require repeated base intervals; otherwise use only a strongly
        # dominant interval, and fail closed when cadence remains ambiguous.
        intervals = np.diff(timestamps.astype("int64").to_numpy(dtype=np.int64)) / 1_000_000_000.0
        if intervals.size and np.all(np.abs(intervals - np.rint(intervals)) <= 0.01):
            integer_intervals = np.rint(intervals).astype(np.int64)
            cadence = int(integer_intervals[0])
            for interval in integer_intervals[1:]:
                cadence = gcd(cadence, int(interval))

            cadence_seconds = None
            if cadence >= 1 and np.count_nonzero(integer_intervals == cadence) >= 2:
                cadence_seconds = cadence
                timestamp_cadence_method = "gcd"
            else:
                values, counts = np.unique(integer_intervals, return_counts=True)
                max_count = int(counts.max())
                if (
                    np.count_nonzero(counts == max_count) == 1
                    and max_count >= max(2, int(np.ceil(0.8 * integer_intervals.size)))
                ):
                    cadence_seconds = int(values[int(np.argmax(counts))])
                    timestamp_cadence_method = "dominant_interval"

            if cadence_seconds is not None:
                timestamp_interval_seconds = float(cadence_seconds)
                aligned = integer_intervals % cadence_seconds == 0
                timestamp_irregular_interval_count = int((~aligned).sum())
                timestamp_cadence_coverage_percent = round(
                    float(np.count_nonzero(aligned)) / int(integer_intervals.size) * 100.0,
                    2,
                )
                gap_intervals = integer_intervals[
                    aligned & (integer_intervals > cadence_seconds * 1.5)
                ]
                timestamp_gap_count = int(gap_intervals.size)
                estimated_missing_candles = int(sum(
                    int(interval // cadence_seconds) - 1
                    for interval in gap_intervals
                ))

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
    plus_di, minus_di, adx = _directional_movement(f)
    di_spread = plus_di - minus_di
    directional_bias = "BULLISH" if di_spread > 0 else "BEARISH" if di_spread < 0 else "NEUTRAL"
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

    # Professional-style OHLCV chart reading. These are computed internally;
    # the cloud API returns the conclusions rather than a rendered chart.
    recent = f.tail(80).reset_index(drop=True)
    swing_high = recent["high"].rolling(5, center=True).max()
    swing_low = recent["low"].rolling(5, center=True).min()
    highs = recent.loc[recent["high"].eq(swing_high), "high"].tail(4).tolist()
    lows = recent.loc[recent["low"].eq(swing_low), "low"].tail(4).tolist()
    structure = "RANGE"
    if len(highs) >= 2 and len(lows) >= 2:
        if highs[-1] > highs[-2] and lows[-1] > lows[-2]:
            structure = "HH_HL"
        elif highs[-1] < highs[-2] and lows[-1] < lows[-2]:
            structure = "LH_LL"
    prior_high = float(f["high"].iloc[-21:-1].max())
    prior_low = float(f["low"].iloc[-21:-1].min())
    bos = "BULLISH_BOS" if last > prior_high else "BEARISH_BOS" if last < prior_low else "NONE"
    previous = f.iloc[-2]
    current = f.iloc[-1]
    liquidity = ("BUY_SIDE_SWEEP" if current["high"] > prior_high and last < prior_high
                 else "SELL_SIDE_SWEEP" if current["low"] < prior_low and last > prior_low
                 else "NONE")
    fvg = ("BULLISH_FVG" if f["low"].iloc[-1] > f["high"].iloc[-3]
           else "BEARISH_FVG" if f["high"].iloc[-1] < f["low"].iloc[-3]
           else "NONE")
    body = abs(float(current["close"] - current["open"]))
    candle_range = max(float(current["high"] - current["low"]), 1e-12)
    upper_wick = float(current["high"] - max(current["open"], current["close"]))
    lower_wick = float(min(current["open"], current["close"]) - current["low"])
    candle_pattern = "NORMAL"
    if body / candle_range < 0.1:
        candle_pattern = "DOJI"
    elif lower_wick > body * 2 and upper_wick < body:
        candle_pattern = "HAMMER"
    elif upper_wick > body * 2 and lower_wick < body:
        candle_pattern = "SHOOTING_STAR"
    elif current["close"] > current["open"] and previous["close"] < previous["open"] and current["close"] >= previous["open"] and current["open"] <= previous["close"]:
        candle_pattern = "BULLISH_ENGULFING"
    elif current["close"] < current["open"] and previous["close"] > previous["open"] and current["open"] >= previous["close"] and current["close"] <= previous["open"]:
        candle_pattern = "BEARISH_ENGULFING"
    # Wilder-style trailing Supertrend approximation.
    hl2 = (f["high"] + f["low"]) / 2
    basic_upper, basic_lower = hl2 + 3 * atr(f), hl2 - 3 * atr(f)
    final_upper, final_lower = basic_upper.copy(), basic_lower.copy()
    for i in range(1, len(f)):
        final_upper.iloc[i] = min(basic_upper.iloc[i], final_upper.iloc[i-1]) if close.iloc[i-1] <= final_upper.iloc[i-1] else basic_upper.iloc[i]
        final_lower.iloc[i] = max(basic_lower.iloc[i], final_lower.iloc[i-1]) if close.iloc[i-1] >= final_lower.iloc[i-1] else basic_lower.iloc[i]
    supertrend = "BULLISH" if last >= final_lower.iloc[-1] else "BEARISH"
    # Nearest rolling structure levels and Fibonacci retracement context.
    support = float(f["low"].tail(60).min())
    resistance = float(f["high"].tail(60).max())
    fib_range = resistance - support
    fibonacci = {str(level): round(resistance - fib_range * level, 10)
                 for level in (0.236, 0.382, 0.5, 0.618, 0.786)} if fib_range > 0 else {}
    direction = "LONG_BIAS" if score >= 3 else "SHORT_BIAS" if score <= -3 else "NEUTRAL"
    confidence = min(95.0, abs(score)/5*80 + (min(adx,40)/40)*15)
    return {
        "direction": direction, "score": score, "confidence": round(confidence,1),
        "last_price": last, "rsi": round(rv,2), "macd_histogram": round(float(macd_hist.iloc[-1]),8),
        "atr": round(av,10), "atr_percent": round(atr_pct,3), "adx": round(adx,2),
        "regime": regime, "plus_di": round(plus_di, 2), "minus_di": round(minus_di, 2),
        "di_spread": round(di_spread, 2), "directional_bias": directional_bias,
        "market_structure": structure, "break_of_structure": bos,
        "liquidity_sweep": liquidity, "fair_value_gap": fvg, "candle_pattern": candle_pattern,
        "supertrend": supertrend, "support": round(support,10), "resistance": round(resistance,10),
        "fibonacci": fibonacci, "bb_width_percent": round(bb_width_pct,3),
        "bb_position": round(bb_position,3), "volume_ratio": round(volume_ratio,3),
        "rolling_vwap": round(vwap,10),
        "timestamp_interval_seconds": timestamp_interval_seconds,
        "timestamp_gap_count": timestamp_gap_count,
        "estimated_missing_candles": estimated_missing_candles,
        "timestamp_irregular_interval_count": timestamp_irregular_interval_count,
        "timestamp_cadence_method": timestamp_cadence_method,
        "timestamp_cadence_coverage_percent": timestamp_cadence_coverage_percent,
        "reasons": reasons,
    }
