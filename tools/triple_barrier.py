"""Conservative, cost-aware triple-barrier labels for chronological OHLC data.

Labels are advisory training targets, not trading instructions. If both barriers
are touched in one candle, the stop barrier wins (conservative intrabar ordering).
"""
from __future__ import annotations

import math
from typing import Sequence


def triple_barrier_labels(
    opens: Sequence[float],
    highs: Sequence[float],
    lows: Sequence[float],
    closes: Sequence[float],
    *,
    profit_target: float = 0.01,
    stop_loss: float = 0.01,
    horizon: int = 6,
    round_trip_cost: float = 0.0,
) -> list[int | None]:
    """Return +1/-1/0 labels; None marks rows without a full future horizon.

    Barriers are evaluated after the entry close, starting with the next candle.
    Costs are fractions of entry notional and are deducted from net returns.
    """
    lengths = {len(opens), len(highs), len(lows), len(closes)}
    if len(lengths) != 1:
        raise ValueError("OHLC arrays must have equal lengths")
    if horizon < 1:
        raise ValueError("horizon must be at least one")
    if not (math.isfinite(profit_target) and profit_target > 0):
        raise ValueError("profit_target must be finite and positive")
    if not (math.isfinite(stop_loss) and 0 < stop_loss < 1):
        raise ValueError("stop_loss must be finite and between 0 and 1")
    if not (math.isfinite(round_trip_cost) and 0 <= round_trip_cost < 1):
        raise ValueError("round_trip_cost must be finite and in [0, 1)")
    for i, (o, h, l, c) in enumerate(zip(opens, highs, lows, closes)):
        values = (float(o), float(h), float(l), float(c))
        if not all(math.isfinite(v) and v > 0 for v in values):
            raise ValueError(f"OHLC values at row {i} must be finite and positive")
        if h < max(o, c, l) or l > min(o, c, h):
            raise ValueError(f"inconsistent OHLC values at row {i}")

    labels: list[int | None] = []
    n = len(closes)
    for i, entry in enumerate(closes):
        if i + horizon >= n:
            labels.append(None)
            continue
        entry = float(entry)
        upper = entry * (1.0 + profit_target + round_trip_cost)
        lower = entry * (1.0 - stop_loss + round_trip_cost)
        label = 0
        for j in range(i + 1, i + horizon + 1):
            hit_upper = float(highs[j]) >= upper
            hit_lower = float(lows[j]) <= lower
            if hit_lower:
                label = -1
                break
            if hit_upper:
                label = 1
                break
        else:
            net_return = float(closes[i + horizon]) / entry - 1.0 - round_trip_cost
            label = 1 if net_return > 0 else (-1 if net_return < 0 else 0)
        labels.append(label)
    return labels
