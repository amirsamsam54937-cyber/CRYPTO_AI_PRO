import numpy as np
import pytest

from tools.time_series_validation import chronological_masks
from tools.train_wolf_ensemble import (
    ARCHITECTURE_DIVERSITY, FEATURE_NAMES, HORIZON, MODEL_FAMILY,
    SPECIALISTS, make_rows, repeat_symbol_labels, majority_baseline_from_training,
)


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


def test_ten_mlp_experts_have_distinct_feature_masks_not_architectures():
    assert len(SPECIALISTS) == 10
    masks = [tuple(mask) for _, mask in SPECIALISTS]
    assert len(set(masks)) == 10
    assert all(len(mask) == 7 and sum(mask) >= 3 for mask in masks)
    assert MODEL_FAMILY == "MLPClassifier" and ARCHITECTURE_DIVERSITY is False


def test_symbol_labels_preserve_full_ticker_names():
    assert repeat_symbol_labels("AAPLXUSDT", 3).tolist() == ["AAPLXUSDT"] * 3


def test_majority_baseline_uses_training_partition_not_test_prevalence():
    majority, accuracy = majority_baseline_from_training([0,0,0,1], [1,1,1,0,0])
    assert majority == 0
    assert accuracy == 0.4
