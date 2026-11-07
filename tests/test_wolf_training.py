import numpy as np
import pytest

from tools.time_series_validation import chronological_masks


def test_chronological_split_keeps_same_timestamp_rows_together_and_purges_horizon():
    # Two symbols share each hourly timestamp; six future bars are purged before test.
    hours = np.repeat(np.arange(100, dtype=np.int64), 2)
    timestamps = hours * 60 * 60 * 1000
    train, test, purged, cutoff = chronological_masks(timestamps, horizon_bars=6)

    assert train.any() and test.any()
    assert purged > 0
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
