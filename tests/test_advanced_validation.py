import numpy as np

from tools.advanced_validation import evaluate_generalization
from tools.walk_forward_validation import HOUR_MS


def test_walk_forward_and_unseen_symbol_holdout_are_reported():
    rng = np.random.default_rng(41)
    n_hours, n_symbols = 1200, 6
    X = rng.normal(size=(n_hours * n_symbols, 7))
    y = (X[:, 0] + 0.25 * X[:, 1] > 0).astype(int)
    timestamps = np.repeat(np.arange(n_hours, dtype=np.int64) * HOUR_MS, n_symbols)
    symbols = np.tile(np.array([f"COIN{i}USDT" for i in range(n_symbols)]), n_hours)

    result = evaluate_generalization(X, y, timestamps, symbols, n_splits=3)

    walk = result["walk_forward"]
    cross = result["cross_symbol_holdout"]
    assert walk["status"] == "evaluated"
    assert walk["fold_count"] == 3
    assert len(walk["folds"]) == 3
    assert all(fold["purged_rows"] > 0 for fold in walk["folds"])
    assert cross["status"] == "evaluated"
    assert len(cross["held_out_symbols"]) >= 1
    assert cross["train_samples"] > 0
    assert cross["samples"] > 0
