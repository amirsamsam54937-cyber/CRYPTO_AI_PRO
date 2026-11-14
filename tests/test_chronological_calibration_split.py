import numpy as np
import pytest
from tools.time_series_validation import chronological_train_calibration_masks


def test_fit_precedes_calibration_with_purged_gap():
    hour = 60 * 60 * 1000
    ts = np.repeat(np.arange(100, dtype=np.int64) * hour, 2)
    fit, calibration, purged, cutoff = chronological_train_calibration_masks(ts, horizon_bars=6)
    assert fit.any() and calibration.any()
    assert purged > 0
    assert int(ts[fit].max()) < cutoff
    assert int(ts[calibration].min()) == cutoff
    assert not np.any(fit & calibration)
    assert not np.any((fit | calibration) & ((ts >= cutoff - 6 * hour) & (ts < cutoff)))


def test_rejects_invalid_calibration_fraction():
    with pytest.raises(ValueError):
        chronological_train_calibration_masks(np.arange(30), calibration_fraction=0.9)
