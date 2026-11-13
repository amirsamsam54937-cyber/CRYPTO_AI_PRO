"""Reference Wilder ATR/ADX calculations.

The first true range / directional movement is undefined because no previous
close/high/low exists. ATR first becomes valid after `period` true ranges;
ADX first becomes valid after `period` valid DX observations. Earlier values
are NaN so callers cannot mistake warm-up placeholders for measurements.
"""
from __future__ import annotations

import math
from typing import Sequence


def _validate_ohlc(high: Sequence[float], low: Sequence[float],
                   close: Sequence[float], period: int) -> tuple[list[float], list[float], list[float]]:
    if not isinstance(period, int) or period < 2:
        raise ValueError("period must be an integer >= 2")
    if not (len(high) == len(low) == len(close)):
        raise ValueError("high, low and close arrays must have equal lengths")
    h, l, c = list(map(float, high)), list(map(float, low)), list(map(float, close))
    for i, (hi, lo, cl) in enumerate(zip(h, l, c)):
        if not all(map(math.isfinite, (hi, lo, cl))) or min(hi, lo, cl) <= 0:
            raise ValueError(f"OHLC values at row {i} must be finite and positive")
        if hi < max(lo, cl) or lo > min(hi, cl):
            raise ValueError(f"inconsistent high/low/close at row {i}")
    return h, l, c


def true_range_series(high: Sequence[float], low: Sequence[float],
                      close: Sequence[float]) -> list[float]:
    if not (len(high) == len(low) == len(close)):
        raise ValueError("high, low and close arrays must have equal lengths")
    out = [math.nan] * len(close)
    for i in range(1, len(close)):
        out[i] = max(float(high[i]) - float(low[i]),
                     abs(float(high[i]) - float(close[i - 1])),
                     abs(float(low[i]) - float(close[i - 1])))
    return out


def wilder_atr(high: Sequence[float], low: Sequence[float],
               close: Sequence[float], period: int = 14) -> list[float]:
    h, l, c = _validate_ohlc(high, low, close, period)
    tr = true_range_series(h, l, c)
    out = [math.nan] * len(c)
    first = period
    if len(c) <= first:
        return out
    value = sum(tr[1:first + 1]) / period
    out[first] = value
    for i in range(first + 1, len(c)):
        value = (value * (period - 1) + tr[i]) / period
        out[i] = value
    return out


def wilder_adx(high: Sequence[float], low: Sequence[float],
               close: Sequence[float], period: int = 14) -> list[float]:
    h, l, c = _validate_ohlc(high, low, close, period)
    size = len(c)
    out = [math.nan] * size
    if size <= 2 * period - 1:
        return out

    tr = [math.nan] * size
    plus_dm = [math.nan] * size
    minus_dm = [math.nan] * size
    for i in range(1, size):
        up = h[i] - h[i - 1]
        down = l[i - 1] - l[i]
        plus_dm[i] = up if up > down and up > 0 else 0.0
        minus_dm[i] = down if down > up and down > 0 else 0.0
        tr[i] = max(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))

    smooth_tr = sum(tr[1:period + 1])
    smooth_plus = sum(plus_dm[1:period + 1])
    smooth_minus = sum(minus_dm[1:period + 1])
    dx_values: list[float] = []
    adx_value = math.nan

    for i in range(period, size):
        if i > period:
            smooth_tr += tr[i] - smooth_tr / period
            smooth_plus += plus_dm[i] - smooth_plus / period
            smooth_minus += minus_dm[i] - smooth_minus / period

        if smooth_tr <= 0:
            plus_di = minus_di = 0.0
        else:
            plus_di = 100.0 * smooth_plus / smooth_tr
            minus_di = 100.0 * smooth_minus / smooth_tr
        denom = plus_di + minus_di
        dx = 0.0 if denom <= 0 else 100.0 * abs(plus_di - minus_di) / denom
        dx_values.append(dx)

        if len(dx_values) == period:
            adx_value = sum(dx_values) / period
            out[i] = adx_value
        elif len(dx_values) > period:
            adx_value = (adx_value * (period - 1) + dx) / period
            out[i] = adx_value

    return out
