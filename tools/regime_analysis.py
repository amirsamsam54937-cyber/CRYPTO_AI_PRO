"""Deterministic market-regime labels and per-specialist holdout diagnostics.

Input rows are the seven raw (pre-scaler) features emitted by make_rows:
trend, RSI momentum, normalized ADX, relative volume, candle body,
normalized ATR percent, and six-bar momentum.
This is an offline diagnostic; it does not alter live signal weights.
"""
from __future__ import annotations

import math
from typing import Iterable, Sequence

REGIMES = ("high_volatility", "low_volatility", "trend_up", "trend_down", "range")
MIN_REGIME_SAMPLES = 30


def classify_regime(row: Sequence[float]) -> str:
    if len(row) != 7:
        raise ValueError("each feature row must contain exactly seven values")
    values = [float(value) for value in row]
    if not all(math.isfinite(value) for value in values):
        raise ValueError("regime features must be finite")
    trend, _rsi, adx_strength, _relative_volume, _body, atr_norm, _momentum = values
    if trend not in (-1.0, 1.0):
        raise ValueError("trend feature must be -1 or +1")
    if not 0.0 <= adx_strength <= 1.0 or not -1.0 <= atr_norm <= 1.0:
        raise ValueError("normalized ADX/ATR features are out of range")
    if atr_norm >= 0.6:
        return "high_volatility"
    if atr_norm <= -0.1:
        return "low_volatility"
    if adx_strength >= 0.5:
        return "trend_up" if trend > 0 else "trend_down"
    return "range"


def regime_labels(rows: Iterable[Sequence[float]]) -> list[str]:
    return [classify_regime(row) for row in rows]


def specialist_regime_breakdown(
    rows: Sequence[Sequence[float]],
    targets: Sequence[int],
    probabilities: Sequence[float],
    min_samples: int = MIN_REGIME_SAMPLES,
) -> dict[str, dict[str, float | int | str | None]]:
    if not (len(rows) == len(targets) == len(probabilities)):
        raise ValueError("rows, targets and probabilities must have equal lengths")
    if min_samples < 1:
        raise ValueError("min_samples must be >= 1")
    groups: dict[str, list[tuple[int, float]]] = {name: [] for name in REGIMES}
    for row, target, probability in zip(rows, targets, probabilities):
        if target not in (0, 1):
            raise ValueError("targets must be binary 0/1")
        p = float(probability)
        if not math.isfinite(p) or not 0.0 <= p <= 1.0:
            raise ValueError("probabilities must be finite and within [0, 1]")
        groups[classify_regime(row)].append((int(target), p))

    result: dict[str, dict[str, float | int | str | None]] = {}
    for name, samples in groups.items():
        count = len(samples)
        item: dict[str, float | int | str | None] = {"count": count}
        if count < min_samples:
            item["status"] = "insufficient_samples"
            item.update({"accuracy": None, "balanced_accuracy": None,
                         "brier_score": None, "log_loss": None})
            result[name] = item
            continue
        ys = [y for y, _ in samples]
        ps = [p for _, p in samples]
        predicted = [int(p >= 0.5) for p in ps]
        accuracy = sum(y == pred for y, pred in zip(ys, predicted)) / count
        recalls = []
        for label in (0, 1):
            selected = [pred == label for y, pred in zip(ys, predicted) if y == label]
            if selected:
                recalls.append(sum(selected) / len(selected))
        balanced = sum(recalls) / len(recalls) if len(recalls) == 2 else None
        brier = sum((p - y) ** 2 for y, p in samples) / count
        eps = 1e-15
        logloss = -sum(y * math.log(max(eps, p)) +
                       (1 - y) * math.log(max(eps, 1 - p))
                       for y, p in samples) / count
        item.update({"status": "evaluated", "accuracy": round(accuracy, 6),
                     "balanced_accuracy": round(balanced, 6) if balanced is not None else None,
                     "brier_score": round(brier, 6), "log_loss": round(logloss, 6)})
        result[name] = item
    return result
