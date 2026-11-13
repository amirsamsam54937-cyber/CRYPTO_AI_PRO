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


SPECIALIST_MASKS = (
    ("trend_structure",        [1,0,1,0,0,1,1]),
    ("momentum_oscillator",    [0,1,1,0,0,0,1]),
    ("volume_participation",  [0,0,1,1,1,0,0]),
    ("candle_pressure",        [0,1,0,1,1,1,0]),
    ("volatility_regime",      [1,0,1,1,0,1,1]),
    ("mean_reversion",         [0,1,1,1,1,1,1]),
    ("breakout_continuation",  [1,0,1,1,1,0,1]),
    ("short_horizon_momentum", [1,1,0,0,1,0,1]),
    ("risk_regime",            [1,1,1,1,0,1,0]),
    ("balanced_critic",        [1,1,1,1,1,1,1]),
)


def _fit_probability(X_train, y_train, X_test, seed):
    """Evaluate the same ten-member, role-masked MLP ensemble exported to Android."""
    y_train = np.asarray(y_train, dtype=int)
    if np.unique(y_train).size != 2:
        raise ValueError("training fold must contain both directional classes")
    scaler = StandardScaler().fit(X_train)
    a = scaler.transform(X_train)
    b = scaler.transform(X_test)
    rng = np.random.default_rng(seed)
    class_positions = [np.flatnonzero(y_train == cls) for cls in (0, 1)]
    if min(map(len, class_positions)) < 2:
        raise ValueError("each specialist fold requires both classes")
    # Bound fold cost while retaining deterministic class-balanced samples.
    per_class = min(max(map(len, class_positions)), 60_000)
    prior = float(np.clip(y_train.mean(), 1e-4, 1 - 1e-4))
    prior_log_odds = np.log(prior / (1.0 - prior))
    member_probabilities = []
    for member_index, (_, mask_values) in enumerate(SPECIALIST_MASKS):
        mask = np.asarray(mask_values, dtype=bool)
        a_member = a.copy()
        b_member = b.copy()
        a_member[:, ~mask] = 0.0
        b_member[:, ~mask] = 0.0
        sample = np.concatenate([
            rng.choice(positions, size=per_class, replace=len(positions) < per_class)
            for positions in class_positions
        ])
        rng.shuffle(sample)
        model = MLPClassifier(
            hidden_layer_sizes=(64, 32, 16), activation="tanh", solver="adam",
            alpha=0.002, learning_rate_init=0.0015, max_iter=160,
            early_stopping=False, tol=1e-4, n_iter_no_change=16,
            random_state=seed + member_index,
        )
        model.fit(a_member[sample], y_train[sample])
        raw = np.clip(model.predict_proba(b_member)[:, 1], 1e-6, 1 - 1e-6)
        logits = np.log(raw / (1.0 - raw)) + prior_log_odds
        member_probabilities.append(1.0 / (1.0 + np.exp(-np.clip(logits, -30.0, 30.0))))
    return np.mean(member_probabilities, axis=0)


def _metrics(y_true, probability, baseline_prevalence):
    y_true = np.asarray(y_true, dtype=int)
    probability = np.clip(np.asarray(probability, dtype=float), 1e-6, 1 - 1e-6)
    prevalence = float(y_true.mean())
    baseline_prevalence = float(np.clip(baseline_prevalence, 1e-4, 1 - 1e-4))
    baseline = np.full(len(y_true), baseline_prevalence)
    return {
        "samples": int(len(y_true)),
        "positive_rate": round(prevalence, 6),
        "accuracy": round(float(accuracy_score(y_true, probability >= 0.5)), 4),
        "balanced_accuracy": round(float(balanced_accuracy_score(y_true, probability >= 0.5)), 4),
        "log_loss": round(float(log_loss(y_true, probability, labels=[0, 1])), 4),
        "baseline_train_prevalence": round(baseline_prevalence, 6),
        "baseline_log_loss": round(float(log_loss(y_true, baseline, labels=[0, 1])), 4),
        "brier_score": round(float(brier_score_loss(y_true, probability)), 4),
        "baseline_brier_score": round(float(np.mean((y_true - baseline_prevalence) ** 2)), 4),
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
        result = _metrics(y[test], probability, float(y[train].mean()))
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
        cross = _metrics(y[test], probability, float(y[train].mean()))
        cross.update({
            "status": "evaluated",
            "train_samples": int(train.sum()),
            "held_out_symbols": sorted(np.unique(symbols[symbol_test]).tolist()),
            "cutoff_timestamp_ms": cutoff,
        })
    return {"walk_forward": walk, "cross_symbol_holdout": cross}
