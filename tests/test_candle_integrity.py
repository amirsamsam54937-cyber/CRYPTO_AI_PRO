import numpy as np

from tools.train_wolf_ensemble import contiguous_candle_segments


HOUR = 60 * 60 * 1000


def candles(timestamps):
    rows = []
    for ts in timestamps:
        rows.append([ts, 100.0, 102.0, 99.0, 101.0, 10.0, 0.0])
    return np.asarray(rows, dtype=float)


def test_contiguous_hourly_candles_remain_one_segment():
    raw = candles([0, HOUR, 2 * HOUR, 3 * HOUR])
    segments = contiguous_candle_segments(raw)
    assert len(segments) == 1
    assert len(segments[0]) == 4
    assert np.all(np.diff(segments[0][:, 0]) == HOUR)


def test_missing_hour_splits_sequence_instead_of_bridging():
    raw = candles([0, HOUR, 3 * HOUR, 4 * HOUR])
    segments = contiguous_candle_segments(raw)
    assert [len(segment) for segment in segments] == [2, 2]


def test_duplicate_timestamp_splits_sequence():
    raw = candles([0, HOUR, HOUR, 2 * HOUR])
    segments = contiguous_candle_segments(raw)
    assert [len(segment) for segment in segments] == [2, 2]


def test_invalid_ohlcv_row_breaks_sequence():
    raw = candles([0, HOUR, 2 * HOUR, 3 * HOUR])
    raw[2, 2] = 90.0  # high below open/close
    segments = contiguous_candle_segments(raw)
    assert [len(segment) for segment in segments] == [2, 1]


def test_non_finite_row_breaks_sequence():
    raw = candles([0, HOUR, 2 * HOUR, 3 * HOUR])
    raw[2, 4] = np.nan
    segments = contiguous_candle_segments(raw)
    assert [len(segment) for segment in segments] == [2, 1]


def test_all_invalid_rows_return_no_segments():
    raw = candles([0, HOUR])
    raw[:, 1] = -1.0
    assert contiguous_candle_segments(raw) == []
