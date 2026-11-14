import numpy as np
import pytest

from tools.time_series_validation import chronological_masks
from tools.train_wolf_ensemble import (
    FEATURE_NAMES, HORIZON, SPECIALISTS, make_rows,
    train_majority_class_baseline_accuracy,
)


def test_majority_accuracy_baseline_selects_class_from_training_only():
    # Test prevalence favors class 1, but the training-only majority is class 0.
    y_train = np.array([0, 0, 0, 1], dtype=int)
    y_test = np.array([1, 1, 1, 0, 0], dtype=int)

    majority_class, test_accuracy = train_majority_class_baseline_accuracy(y_train, y_test)

    assert majority_class == 0
    assert test_accuracy == 0.4


def test_chronological_split_keeps_same_timestamp_rows_together_and_purges_horizon():
    hours = np.repeat(np.arange(100, dtype=np.int64), 2)
    timestamps = hours * 60 * 60 * 1000
    train, test, purged, cutoff = chronological_masks(timestamps, horizon_bars=6)
    assert train.any() and test.any() and purged > 0
    assert np.all(timestamps[train] < cutoff - 6 * 60 * 60 * 1000)
    assert np.all(timestamps[test] >= cutoff)
    assert not np.any(train & test)
    for timestamp in np.unique(timestamps):
        rows = timestamps == timestamp
        assert len(np.unique(train[rows])) == 1
        assert len(np.unique(test[rows])) == 1


def test_chronological_split_rejects_too_few_timestamps():
    with pytest.raises(ValueError):
        chronological_masks(np.array([1, 1, 2, 2, 3, 3], dtype=np.int64))


def test_neural_target_is_future_price_direction_not_technical_rule_output():
    n = 260
    t = np.arange(n, dtype=np.int64)
    close = 100.0 * np.exp(0.0007 * t + 0.006 * np.sin(t / 5.0))
    open_ = np.r_[close[0], close[:-1]]
    high = np.maximum(open_, close) * 1.002
    low = np.minimum(open_, close) * 0.998
    volume = 1000.0 + (t % 17) * 13.0
    raw = np.column_stack([t * 3_600_000, open_, high, low, close, volume, np.zeros(n)])
    X, y, timestamps = make_rows(raw)
    assert X.shape[1] == len(FEATURE_NAMES) == 7
    assert len(X) == len(y) == len(timestamps)
    for label, timestamp in zip(y, timestamps):
        i = int(timestamp // 3_600_000)
        future_return = close[i + HORIZON] / close[i] - 1.0
        assert label == int(future_return > 0.0025)
        assert abs(future_return) > 0.0025


def test_ten_neural_experts_have_distinct_feature_specializations():
    assert len(SPECIALISTS) == 10
    masks = [tuple(mask) for _, mask in SPECIALISTS]
    assert len(set(masks)) == 10
    assert all(len(mask) == 7 and sum(mask) >= 3 for mask in masks)



def test_balanced_bootstrap_is_deterministic_balanced_and_bounded():
    from tools.train_wolf_ensemble import balanced_bootstrap_indices

    labels = np.array([0] * 90_000 + [1] * 10_000)
    first = balanced_bootstrap_indices(labels, seed=77, max_per_class=12_000)
    second = balanced_bootstrap_indices(labels, seed=77, max_per_class=12_000)

    assert np.array_equal(first, second)
    assert len(first) == 24_000
    assert np.count_nonzero(labels[first] == 0) == 12_000
    assert np.count_nonzero(labels[first] == 1) == 12_000
    # Majority class is sampled without replacement; minority is bootstrapped.
    assert len(np.unique(first[labels[first] == 0])) == 12_000
    assert len(np.unique(first[labels[first] == 1])) <= 10_000


def test_balanced_bootstrap_rejects_missing_or_tiny_class():
    from tools.train_wolf_ensemble import balanced_bootstrap_indices

    with pytest.raises(ValueError):
        balanced_bootstrap_indices(np.array([0, 0, 0]), seed=1)
    with pytest.raises(ValueError):
        balanced_bootstrap_indices(np.array([0, 1, 1]), seed=1, max_per_class=1)
