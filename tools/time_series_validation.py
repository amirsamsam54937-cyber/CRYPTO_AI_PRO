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


def three_way_chronological_masks(timestamps, horizon_bars=6, interval_ms=60 * 60 * 1000,
                                  train_fraction=0.6, calibration_fraction=0.2):
    """Create train/calibration/final-test folds with a purge before each boundary.

    All rows sharing a timestamp stay together. Calibration is never used to fit
    the scaler or networks; final test is never used for calibration or fitting.
    """
    ts = np.asarray(timestamps, dtype=np.int64)
    unique_times = np.unique(ts)
    if len(unique_times) < 20:
        raise ValueError("Not enough unique timestamps for three chronological folds")
    if not (0.4 <= train_fraction <= 0.8 and 0.1 <= calibration_fraction <= 0.3
            and train_fraction + calibration_fraction < 0.95):
        raise ValueError("invalid chronological fold fractions")
    first = int(unique_times[min(len(unique_times)-1, int(len(unique_times)*train_fraction))])
    second_fraction = train_fraction + calibration_fraction
    second = int(unique_times[min(len(unique_times)-1, int(len(unique_times)*second_fraction))])
    purge_ms = int(horizon_bars * interval_ms)
    train_end, calibration_end = first - purge_ms, second - purge_ms
    train = ts < train_end
    calibration = (ts >= first) & (ts < calibration_end)
    test = ts >= second
    purged = ((ts >= train_end) & (ts < first)) | ((ts >= calibration_end) & (ts < second))
    if not train.any() or not calibration.any() or not test.any():
        raise ValueError("Purged three-way split produced an empty partition")
    if len(np.unique(ts[train])) < 2 or len(np.unique(ts[calibration])) < 2 or len(np.unique(ts[test])) < 2:
        raise ValueError("Each partition needs at least two unique timestamps")
    return train, calibration, test, int(purged.sum()), first, second
