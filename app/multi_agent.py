"""Explainable ten-agent technical-analysis ensemble.

These are deterministic specialist rule engines, not ten external LLMs or trained models.
They vote independently on the same completed OHLCV window and abstain when evidence is weak.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
import math
import numpy as np
import pandas as pd

from app.advanced_analysis import analyze_ohlcv


@dataclass(frozen=True)
class AgentVote:
    name: str
    direction: str
    score: float
    weight: float
    reason: str


def _vote(name: str, raw: float, weight: float, reason: str) -> AgentVote:
    raw = float(np.clip(raw, -1.0, 1.0))
    direction = "LONG" if raw >= 0.20 else "SHORT" if raw <= -0.20 else "NEUTRAL"
    return AgentVote(name, direction, round(raw, 3), weight, reason)


def analyze_consensus(frame: pd.DataFrame) -> dict:
    """Return ten specialist votes, weighted consensus and an ATR-based risk plan."""
    base = analyze_ohlcv(frame)
    f = frame.copy()
    close = f["close"].astype(float)
    high, low, volume = f["high"].astype(float), f["low"].astype(float), f["volume"].astype(float)
    last = float(close.iloc[-1])
    atr = float(base["atr"])
    if not math.isfinite(atr) or atr <= 0 or last <= 0:
        raise ValueError("Cannot build a risk plan from invalid ATR or price")

    ema20 = close.ewm(span=20, adjust=False).mean()
    ema50 = close.ewm(span=50, adjust=False).mean()
    ema200 = close.ewm(span=min(200, len(f)-1), adjust=False).mean()
    delta = close.diff()
    gains = delta.clip(lower=0).ewm(alpha=1/14, adjust=False).mean()
    losses = (-delta.clip(upper=0)).ewm(alpha=1/14, adjust=False).mean()
    rsi = float((100 - 100/(1 + gains.iloc[-1] / max(float(losses.iloc[-1]), 1e-12))))
    macd = close.ewm(span=12, adjust=False).mean() - close.ewm(span=26, adjust=False).mean()
    macd_sig = macd.ewm(span=9, adjust=False).mean()
    avg_vol = float(volume.rolling(20).mean().iloc[-1])
    vol_ratio = float(volume.iloc[-1] / avg_vol) if avg_vol > 0 else 0.0
    typical = (high + low + close) / 3
    vol_sum = float(volume.rolling(24).sum().iloc[-1])
    vwap = float((typical * volume).rolling(24).sum().iloc[-1] / vol_sum) if vol_sum > 0 else last
    mid = float(close.rolling(20).mean().iloc[-1])
    std = float(close.rolling(20).std(ddof=0).iloc[-1])
    upper, lower = mid + 2*std, mid - 2*std
    prior_high = float(high.iloc[-21:-1].max())
    prior_low = float(low.iloc[-21:-1].min())
    body = float(close.iloc[-1] - f["open"].astype(float).iloc[-1])
    candle_range = max(float(high.iloc[-1] - low.iloc[-1]), 1e-12)
    body_ratio = body / candle_range
    returns = close.pct_change().dropna()
    recent_vol = float(returns.tail(20).std() or 0.0)
    long_vol = float(returns.tail(100).std() or 0.0)
    momentum_5 = float(close.pct_change(5).iloc[-1])

    # Specialist models are intentionally transparent and separately inspectable.
    votes = [
        _vote("Trend / EMA", 1 if ema20.iloc[-1] > ema50.iloc[-1] > ema200.iloc[-1] else -1 if ema20.iloc[-1] < ema50.iloc[-1] < ema200.iloc[-1] else 0, 1.25, "چیدمان EMA20/50/200"),
        _vote("Momentum / RSI", np.clip((rsi-50)/18, -1, 1), 1.0, f"RSI={rsi:.1f}"),
        _vote("MACD impulse", np.clip((float(macd.iloc[-1])-float(macd_sig.iloc[-1]))/(atr+1e-12)*8, -1, 1), 1.0, "فاصله MACD از خط سیگنال"),
        _vote("ADX trend quality", (1 if base["direction"] == "LONG_BIAS" else -1 if base["direction"] == "SHORT_BIAS" else 0) * min(float(base["adx"])/35, 1), 0.9, f"قدرت روند ADX={base['adx']:.1f}"),
        _vote("Volume confirmation", np.sign(momentum_5) * min(max(vol_ratio-0.7, 0)/1.3, 1), 0.8, f"حجم نسبی={vol_ratio:.2f}"),
        _vote("VWAP positioning", np.clip((last-vwap)/(atr*1.5), -1, 1), 0.8, "موقعیت قیمت نسبت به VWAP"),
        _vote("Bollinger regime", np.clip((last-mid)/(std*2+1e-12), -1, 1), 0.65, "جایگاه قیمت در باند بولینگر"),
        _vote("Range breakout", 1 if last > prior_high else -1 if last < prior_low else 0, 1.1, "شکست سقف/کف ۲۰ کندل بسته‌شده"),
        _vote("Candle pressure", np.clip(body_ratio*1.5, -1, 1), 0.55, "قدرت بدنه کندل آخر"),
        _vote("Volatility / stability", np.sign(momentum_5) * (0.5 if long_vol == 0 else np.clip(1-recent_vol/(long_vol*1.8+1e-12), -0.5, 0.8)), 0.55, "پایداری نوسان نسبت به ۱۰۰ کندل"),
    ]
    total_weight = sum(v.weight for v in votes)
    raw_consensus = sum(v.score*v.weight for v in votes) / total_weight
    long_weight = sum(v.weight for v in votes if v.direction == "LONG")
    short_weight = sum(v.weight for v in votes if v.direction == "SHORT")
    agreement = max(long_weight, short_weight) / total_weight
    direction = "LONG" if raw_consensus >= 0.22 and agreement >= 0.48 else "SHORT" if raw_consensus <= -0.22 and agreement >= 0.48 else "WAIT"
    # This is a rule-strength index, not a statistically calibrated probability.
    strength = int(round(min(95, abs(raw_consensus)*75 + agreement*20)))
    stop_distance = max(1.5*atr, last*0.002)
    if direction == "LONG":
        entry, stop = last, last-stop_distance
        targets = [last+stop_distance*r for r in (1.0, 1.8, 2.8)]
    elif direction == "SHORT":
        entry, stop = last, last+stop_distance
        targets = [last-stop_distance*r for r in (1.0, 1.8, 2.8)]
    else:
        entry, stop, targets = last, None, []
    return {
        "direction": direction, "strength_index": strength,
        "is_probability": False, "agreement": round(agreement, 3),
        "consensus_score": round(raw_consensus, 4),
        "votes": [asdict(v) for v in votes],
        "risk_plan": {"entry_reference": round(entry, 10),
                      "stop_loss": round(stop, 10) if stop is not None else None,
                      "take_profits": [round(x, 10) for x in targets],
                      "atr": round(atr, 10), "risk_reward": [1.0, 1.8, 2.8] if targets else []},
        "market_diagnostics": base,
        "disclaimer": "شاخص قدرت قوانین است، نه احتمال برد یا تضمین سود؛ قبل از استفاده باید با داده تاریخی و هزینه معاملات اعتبارسنجی شود."
    }
