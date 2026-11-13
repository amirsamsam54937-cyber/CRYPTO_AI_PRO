"""Leakage-resistant expanding walk-forward folds for financial time series.

All observations sharing an event timestamp stay in the same fold. Each test
window is strictly later than its training window, and the label horizon is
purged before each test window. This module deliberately does not fit models.
"""
from __future__ import annotations
import numpy as np

HOUR_MS = 60 * 60 * 1000


def expanding_walk_forward_masks(timestamps, *, horizon_bars=6, interval_ms=HOUR_MS,
                                 n_splits=5, min_train_fraction=0.50):
    """Return (train_mask, test_mask, purged_count, cutoff) for each expanding fold."""
    ts = np.asarray(timestamps, dtype=np.int64)
    if ts.ndim != 1 or ts.size == 0:
        raise ValueError("timestamps must be a non-empty one-dimensional array")
    if horizon_bars < 0 or interval_ms <= 0:
        raise ValueError("horizon_bars must be >= 0 and interval_ms must be > 0")
    if n_splits < 2:
        raise ValueError("n_splits must be >= 2")
    if not 0.20 <= min_train_fraction <= 0.90:
        raise ValueError("min_train_fraction must be between 0.20 and 0.90")
    unique = np.unique(ts)
    if unique.size < n_splits + 5:
        raise ValueError("not enough unique timestamps for requested walk-forward folds")
    first_test = int(np.floor(unique.size * min_train_fraction))
    remaining = unique.size - first_test
    if remaining < n_splits:
        raise ValueError("evaluation tail is too short for requested folds")
    blocks = np.array_split(unique[first_test:], n_splits)
    purge_ms = int(horizon_bars) * int(interval_ms)
    folds = []
    for block in blocks:
        if block.size == 0:
            continue
        cutoff, end = int(block[0]), int(block[-1])
        train_end = cutoff - purge_ms
        train = ts < train_end
        test = (ts >= cutoff) & (ts <= end)
        purged = (ts >= train_end) & (ts < cutoff)
        if not train.any() or not test.any():
            raise ValueError("walk-forward fold has an empty train or test partition")
        if np.any(ts[train] >= cutoff) or np.any(ts[test] < cutoff):
            raise AssertionError("temporal ordering invariant violated")
        folds.append((train, test, int(purged.sum()), cutoff))
    if len(folds) != n_splits:
        raise ValueError("could not construct all requested walk-forward folds")
    return folds


def deterministic_symbol_holdout(symbols, *, holdout_fraction=0.20):
    """Stable sorted-symbol holdout for cross-sectional generalization diagnostics."""
    values = np.asarray(symbols, dtype=str)
    if values.ndim != 1 or values.size == 0:
        raise ValueError("symbols must be a non-empty one-dimensional array")
    if not 0.05 <= holdout_fraction <= 0.50:
        raise ValueError("holdout_fraction must be between 0.05 and 0.50")
    unique = np.unique(values)
    if unique.size < 4:
        raise ValueError("at least four distinct symbols are required")
    count = max(1, int(np.ceil(unique.size * holdout_fraction)))
    held_out = set(unique[-count:].tolist())
    test = np.fromiter((s in held_out for s in values), dtype=bool, count=values.size)
    return ~test, test
