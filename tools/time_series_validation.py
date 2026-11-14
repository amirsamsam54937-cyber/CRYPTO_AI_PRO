"""Leakage-resistant time-series split utilities shared by training and tests."""
import numpy as np


def chronological_masks(timestamps, horizon_bars=6, interval_ms=60 * 60 * 1000, test_fraction=0.2):
    """Purged chronological split; all rows sharing a timestamp stay in the same fold."""
    ts = np.asarray(timestamps, dtype=np.int64)
    unique_times = np.unique(ts)
    if len(unique_times) < 10:
        raise ValueError("Not enough unique timestamps for a chronological holdout")
    cutoff = unique_times[min(len(unique_times) - 1, int(len(unique_times) * (1.0 - test_fraction)))]
    train_end = int(cutoff) - int(horizon_bars * interval_ms)
    train_mask = ts < train_end
    test_mask = ts >= cutoff
    purged_mask = (ts >= train_end) & (ts < cutoff)
    if not train_mask.any() or not test_mask.any():
        raise ValueError("Purged chronological split produced an empty train or test partition")
    return train_mask, test_mask, int(purged_mask.sum()), int(cutoff)
 
def chronological_train_calibration_masks(
    timestamps, horizon_bars=6, interval_ms=60 * 60 * 1000, calibration_fraction=0.25
):
    """Split outer training data into fit and later calibration windows.

    A purge of horizon_bars separates model fitting from calibration so forward
    labels cannot overlap. The final test window must remain outside this helper.
    """
    fraction = float(calibration_fraction)
    if not 0.05 <= fraction <= 0.5:
        raise ValueError("calibration_fraction must be between 0.05 and 0.5")
    return chronological_masks(
        timestamps, horizon_bars=horizon_bars,
        interval_ms=interval_ms, test_fraction=fraction,
    )
