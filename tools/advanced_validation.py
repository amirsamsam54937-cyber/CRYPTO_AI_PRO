"""Additional generalization checks for the ZORO research ensemble.

Walk-forward folds are strictly chronological and purge the full label horizon.
The cross-symbol test trains on one set of instruments in the earlier period and
tests on unseen instruments only in a later period. These are diagnostics, not
proof of profitability.
"""
from __future__ import annotations

import numpy as np
from sklearn.metrics import (
    accuracy_score, balanced_accuracy_score, brier_score_loss,
    log_loss, roc_auc_score,
)
from sklearn.neural_network import MLPClassifier
from sklearn.preprocessing import StandardScaler

try:
    from tools.walk_forward_validation import (
        HOUR_MS, deterministic_symbol_holdout, expanding_walk_forward_masks,
    )
except ModuleNotFoundError:
    from walk_forward_validation import (
        HOUR_MS, deterministic_symbol_holdout, expanding_walk_forward_masks,
    )


def _ece(y_true, probability, bins=10):
    edges = np.linspace(0.0, 1.0, bins + 1)
    error = 0.0
    for i in range(bins):
        mask = ((probability >= edges[i]) &
                (probability < edges[i + 1] if i < bins - 1 else probability <= edges[i + 1]))
        if mask.any():
            error += float(mask.mean()) * abs(float(y_true[mask].mean()) - float(probability[mask].mean()))
    return float(error)


def _fit_probability(X_train, y_train, X_test, seed):
    y_train = np.asarray(y_train, dtype=int)
    if np.unique(y_train).size != 2:
        raise ValueError("training fold must contain both directional classes")
    scaler = StandardScaler().fit(X_train)
    a = scaler.transform(X_train)
    b = scaler.transform(X_test)
    rng = np.random.default_rng(seed)
    indices = []
    for cls in (0, 1):
        positions = np.flatnonzero(y_train == cls)
        indices.append(rng.choice(positions, size=max(np.bincount(y_train)), replace=len(positions) < max(np.bincount(y_train))))
    sample = np.concatenate(indices)
    rng.shuffle(sample)
    model = MLPClassifier(
        hidden_layer_sizes=(32, 16), activation="tanh", solver="adam",
        alpha=0.003, learning_rate_init=0.001, max_iter=120,
        early_stopping=False, tol=1e-4, n_iter_no_change=12,
        random_state=seed,
    )
    model.fit(a[sample], y_train[sample])
    raw = np.clip(model.predict_proba(b)[:, 1], 1e-6, 1 - 1e-6)
    # Restore the real, non-balanced training prior.
    prior = float(np.clip(y_train.mean(), 1e-4, 1 - 1e-4))
    logits = np.log(raw / (1.0 - raw)) + np.log(prior / (1.0 - prior))
    return 1.0 / (1.0 + np.exp(-np.clip(logits, -30.0, 30.0)))


def _metrics(y_true, probability):
    y_true = np.asarray(y_true, dtype=int)
    probability = np.clip(np.asarray(probability, dtype=float), 1e-6, 1 - 1e-6)
    prevalence = float(y_true.mean())
    baseline = np.full(len(y_true), prevalence)
    return {
        "samples": int(len(y_true)),
        "positive_rate": round(prevalence, 6),
        "accuracy": round(float(accuracy_score(y_true, probability >= 0.5)), 4),
        "balanced_accuracy": round(float(balanced_accuracy_score(y_true, probability >= 0.5)), 4),
        "log_loss": round(float(log_loss(y_true, probability, labels=[0, 1])), 4),
        "baseline_log_loss": round(float(log_loss(y_true, baseline, labels=[0, 1])), 4),
        "brier_score": round(float(brier_score_loss(y_true, probability)), 4),
        "baseline_brier_score": round(float(np.mean((y_true - prevalence) ** 2)), 4),
        "roc_auc": round(float(roc_auc_score(y_true, probability)), 4),
        "expected_calibration_error": round(_ece(y_true, probability), 4),
    }


def evaluate_generalization(X, y, timestamps, symbols, *, horizon_bars=6, n_splits=3):
    X = np.asarray(X, dtype=float)
    y = np.asarray(y, dtype=int)
    timestamps = np.asarray(timestamps, dtype=np.int64)
    symbols = np.asarray(symbols, dtype=str)
    if X.ndim != 2 or X.shape[1] != 7 or not (len(X) == len(y) == len(timestamps) == len(symbols)):
        raise ValueError("X/y/timestamps/symbols dimensions do not match the seven-feature dataset")
    if not np.isfinite(X).all() or np.unique(y).size != 2:
        raise ValueError("generalization data must be finite and contain both labels")

    folds = expanding_walk_forward_masks(
        timestamps, horizon_bars=horizon_bars, interval_ms=HOUR_MS,
        n_splits=n_splits, min_train_fraction=0.55,
    )
    fold_results = []
    for index, (train, test, purged, cutoff) in enumerate(folds):
        probability = _fit_probability(X[train], y[train], X[test], 611_000 + index)
        result = _metrics(y[test], probability)
        result.update({"fold": index + 1, "purged_rows": int(purged), "cutoff_timestamp_ms": int(cutoff)})
        fold_results.append(result)

    walk = {
        "status": "evaluated",
        "fold_count": len(fold_results),
        "mean_balanced_accuracy": round(float(np.mean([f["balanced_accuracy"] for f in fold_results])), 4),
        "mean_log_loss": round(float(np.mean([f["log_loss"] for f in fold_results])), 4),
        "mean_baseline_log_loss": round(float(np.mean([f["baseline_log_loss"] for f in fold_results])), 4),
        "mean_brier_score": round(float(np.mean([f["brier_score"] for f in fold_results])), 4),
        "mean_baseline_brier_score": round(float(np.mean([f["baseline_brier_score"] for f in fold_results])), 4),
        "mean_roc_auc": round(float(np.mean([f["roc_auc"] for f in fold_results])), 4),
        "folds": fold_results,
    }

    symbol_train, symbol_test = deterministic_symbol_holdout(symbols, holdout_fraction=0.20)
    unique_times = np.unique(timestamps)
    cutoff = int(unique_times[int(len(unique_times) * 0.70)])
    train = symbol_train & (timestamps < cutoff - horizon_bars * HOUR_MS)
    test = symbol_test & (timestamps >= cutoff)
    if train.sum() < 500 or test.sum() < 200 or np.unique(y[train]).size != 2 or np.unique(y[test]).size != 2:
        cross = {
            "status": "insufficient_data",
            "train_samples": int(train.sum()),
            "test_samples": int(test.sum()),
            "held_out_symbols": sorted(np.unique(symbols[symbol_test]).tolist()),
        }
    else:
        probability = _fit_probability(X[train], y[train], X[test], 719_000)
        cross = _metrics(y[test], probability)
        cross.update({
            "status": "evaluated",
            "train_samples": int(train.sum()),
            "held_out_symbols": sorted(np.unique(symbols[symbol_test]).tolist()),
            "cutoff_timestamp_ms": cutoff,
        })
    return {"walk_forward": walk, "cross_symbol_holdout": cross}
