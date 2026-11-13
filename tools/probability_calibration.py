"""Chronological, leakage-aware probability calibration utilities.

Fit only on a dedicated calibration partition that is later than training and
earlier than the untouched final test partition. This module does not train the
base classifier and does not imply that a calibrated probability is profitable.
"""
from __future__ import annotations

import math
from collections.abc import Sequence


def chronological_calibration_split(
    sample_count: int,
    *,
    calibration_fraction: float = 0.20,
    test_fraction: float = 0.20,
    purge: int = 6,
) -> dict[str, list[int]]:
    """Return ordered train/calibration/test indices with purge gaps between them."""
    if sample_count < 3:
        raise ValueError("sample_count must be at least 3")
    if not (0 < calibration_fraction < 1 and 0 < test_fraction < 1):
        raise ValueError("fractions must be between zero and one")
    if calibration_fraction + test_fraction >= 0.8:
        raise ValueError("calibration and test fractions leave too little training data")
    if purge < 0:
        raise ValueError("purge must be non-negative")

    train_end = int(sample_count * (1.0 - calibration_fraction - test_fraction))
    calibration_start = train_end + purge
    calibration_end = int(sample_count * (1.0 - test_fraction))
    test_start = calibration_end + purge
    if train_end < 2 or calibration_end - calibration_start < 2 or sample_count - test_start < 2:
        raise ValueError("not enough samples after chronological purge gaps")
    return {
        "train": list(range(0, train_end)),
        "calibration": list(range(calibration_start, calibration_end)),
        "test": list(range(test_start, sample_count)),
    }


def _sigmoid(value: float) -> float:
    if value >= 0:
        z = math.exp(-min(value, 700.0))
        return 1.0 / (1.0 + z)
    z = math.exp(max(value, -700.0))
    return z / (1.0 + z)


def fit_platt_calibrator(
    logits: Sequence[float],
    labels: Sequence[int],
    *,
    min_samples: int = 30,
    max_iter: int = 100,
    ridge: float = 1e-6,
) -> dict[str, float | int | str]:
    """Fit a two-parameter logistic map using calibration-set logits only.

    A non-positive fitted slope is rejected: it indicates reversed ranking or
    unstable calibration, which should trigger model review rather than silently
    invert the model's probabilities.
    """
    if len(logits) != len(labels):
        raise ValueError("logits and labels must have equal lengths")
    if len(logits) < min_samples:
        raise ValueError("insufficient calibration observations")
    if min_samples < 2 or max_iter < 1 or not math.isfinite(ridge) or ridge <= 0:
        raise ValueError("invalid calibration settings")
    xs = [float(x) for x in logits]
    ys = [int(y) for y in labels]
    if any(not math.isfinite(x) for x in xs):
        raise ValueError("logits must be finite")
    if any(y not in (0, 1) for y in ys) or len(set(ys)) != 2:
        raise ValueError("calibration labels must contain both binary classes")

    a, b = 1.0, 0.0
    n = float(len(xs))
    for _ in range(max_iter):
        g0 = ridge * a
        g1 = ridge * b
        h00 = ridge
        h01 = 0.0
        h11 = ridge
        for x, y in zip(xs, ys):
            p = _sigmoid(a * x + b)
            error = p - y
            weight = p * (1.0 - p)
            g0 += error * x
            g1 += error
            h00 += weight * x * x
            h01 += weight * x
            h11 += weight
        det = h00 * h11 - h01 * h01
        if not math.isfinite(det) or det <= 1e-18:
            raise ValueError("calibration Hessian is singular")
        da = (h11 * g0 - h01 * g1) / det
        db = (-h01 * g0 + h00 * g1) / det
        scale = 1.0
        # Damped Newton step prevents a small calibration set from diverging.
        old_loss = sum(
            math.log1p(math.exp(-min(max((2 * y - 1) * (a * x + b), -700), 700)))
            for x, y in zip(xs, ys)
        ) / n + 0.5 * ridge * (a * a + b * b)
        accepted = False
        for _ in range(20):
            na, nb = a - scale * da, b - scale * db
            new_loss = sum(
                math.log1p(math.exp(-min(max((2 * y - 1) * (na * x + nb), -700), 700)))
                for x, y in zip(xs, ys)
            ) / n + 0.5 * ridge * (na * na + nb * nb)
            if math.isfinite(new_loss) and new_loss <= old_loss:
                a, b = na, nb
                accepted = True
                break
            scale *= 0.5
        if not accepted or max(abs(scale * da), abs(scale * db)) < 1e-8:
            break
    if not math.isfinite(a) or not math.isfinite(b) or a <= 0:
        raise ValueError("calibration produced a non-positive slope; review model ranking")
    return {"slope": float(a), "intercept": float(b), "samples": len(xs),
            "positive_rate": sum(ys) / n, "status": "fitted"}


def apply_platt_calibrator(
    logits: Sequence[float], calibrator: dict[str, float | int | str]
) -> list[float]:
    """Map raw logits to calibrated probabilities in [0, 1]."""
    a = float(calibrator["slope"])
    b = float(calibrator["intercept"])
    if not math.isfinite(a) or not math.isfinite(b) or a <= 0:
        raise ValueError("invalid Platt calibrator")
    result = []
    for value in logits:
        x = float(value)
        if not math.isfinite(x):
            raise ValueError("logits must be finite")
        result.append(_sigmoid(a * x + b))
    return result
