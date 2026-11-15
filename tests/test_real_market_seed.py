import pytest

from tools.build_real_market_seed import summarize_candle_timestamps


HOUR_MS = 60 * 60 * 1000


def test_exact_hourly_series_has_one_contiguous_segment():
    result = summarize_candle_timestamps([0, HOUR_MS, 2 * HOUR_MS])

    assert result == {
        "duplicate_timestamps": 0,
        "out_of_order_candles": 0,
        "missing_1h_candles": 0,
        "irregular_gap_transitions": 0,
        "contiguous_segments": 1,
    }


def test_missing_hour_is_counted_and_splits_contiguous_segment():
    result = summarize_candle_timestamps([0, HOUR_MS, 3 * HOUR_MS])

    assert result["missing_1h_candles"] == 1
    assert result["contiguous_segments"] == 2


def test_duplicate_and_out_of_order_timestamps_are_reported():
    result = summarize_candle_timestamps(
        [0, HOUR_MS, HOUR_MS, 2 * HOUR_MS, 0]
    )

    assert result["duplicate_timestamps"] == 2
    assert result["out_of_order_candles"] == 2
    assert result["missing_1h_candles"] == 0


def test_non_hour_aligned_gap_is_not_misreported_as_a_missing_hour_count():
    result = summarize_candle_timestamps([0, HOUR_MS, HOUR_MS + 30 * 60 * 1000])

    assert result["missing_1h_candles"] == 0
    assert result["irregular_gap_transitions"] == 1
    assert result["contiguous_segments"] == 2
