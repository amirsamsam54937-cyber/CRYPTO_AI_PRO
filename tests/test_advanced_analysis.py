import numpy as np
import pandas as pd
import pytest

from app.advanced_analysis import analyze_ohlcv


def candles(n=240, trend=0.15):
    close = 100 + np.arange(n) * trend + np.sin(np.arange(n)/4)
    return pd.DataFrame({
        "open": close - 0.2, "high": close + 0.8,
        "low": close - 0.8, "close": close, "volume": np.full(n, 1000.0)
    })


def test_analysis_returns_bounded_explainable_metrics():
    result = analyze_ohlcv(candles())
    assert result["direction"] in {"LONG_BIAS", "SHORT_BIAS", "NEUTRAL"}
    assert 0 <= result["confidence"] <= 95
    assert result["atr"] > 0
    assert result["regime"] in {"TRENDING", "RANGING"}
    assert isinstance(result["reasons"], list)


def test_analysis_rejects_short_or_invalid_data():
    with pytest.raises(ValueError):
        analyze_ohlcv(candles(30))
    bad = candles()
    bad.loc[10, "high"] = 1
    with pytest.raises(ValueError):
        analyze_ohlcv(bad)


def test_analysis_rejects_non_finite_prices_and_volume():
    bad_price = candles()
    bad_price.loc[100, "close"] = np.inf
    with pytest.raises(ValueError, match="non-finite"):
        analyze_ohlcv(bad_price)

    bad_volume = candles()
    bad_volume.loc[100, "volume"] = -1.0
    with pytest.raises(ValueError, match="volume cannot be negative"):
        analyze_ohlcv(bad_volume)

    infinite_volume = candles()
    infinite_volume.loc[100, "volume"] = np.inf
    with pytest.raises(ValueError, match="non-finite"):
        analyze_ohlcv(infinite_volume)


def test_analysis_rejects_duplicate_ohlcv_column_names():
    frame = candles()
    frame.columns = ["open", "high", "low", "close", "close"]
    with pytest.raises(ValueError, match="column names must be unique"):
        analyze_ohlcv(frame)


@pytest.mark.parametrize(
    "timestamp_mutation",
    ["duplicate", "out_of_order", "invalid"],
)
def test_analysis_rejects_invalid_or_non_monotonic_timestamps(timestamp_mutation):
    frame = candles()
    frame.insert(
        0,
        "timestamp",
        pd.date_range("2024-01-01", periods=len(frame), freq="15min", tz="UTC"),
    )
    if timestamp_mutation == "duplicate":
        frame.loc[100, "timestamp"] = frame.loc[99, "timestamp"]
    elif timestamp_mutation == "out_of_order":
        frame.loc[100, "timestamp"] = frame.loc[99, "timestamp"] - pd.Timedelta(minutes=15)
    else:
        frame.loc[100, "timestamp"] = "not-a-timestamp"

    with pytest.raises(ValueError, match="timestamps"):
        analyze_ohlcv(frame)



def test_directional_movement_diagnostics_distinguish_uptrend_and_downtrend():
    bullish = analyze_ohlcv(candles(trend=0.15))
    bearish = analyze_ohlcv(candles(trend=-0.15))

    assert bullish["plus_di"] > bullish["minus_di"]
    assert bullish["directional_bias"] == "BULLISH"
    assert bullish["di_spread"] == pytest.approx(
        bullish["plus_di"] - bullish["minus_di"], abs=0.02
    )
    assert bearish["minus_di"] > bearish["plus_di"]
    assert bearish["directional_bias"] == "BEARISH"
    assert bearish["di_spread"] == pytest.approx(
        bearish["plus_di"] - bearish["minus_di"], abs=0.02
    )


def test_analysis_reports_timestamp_gaps_without_changing_signal_metrics():
    frame = candles()
    frame.insert(
        0,
        "timestamp",
        pd.date_range("2024-01-01", periods=len(frame), freq="15min", tz="UTC"),
    )
    frame.loc[120:, "timestamp"] += pd.Timedelta(minutes=15)

    result = analyze_ohlcv(frame)
    baseline = analyze_ohlcv(candles())

    assert result["timestamp_interval_seconds"] == 900.0
    assert result["timestamp_gap_count"] == 1
    assert result["estimated_missing_candles"] == 1
    assert result["direction"] == baseline["direction"]
    assert result["score"] == baseline["score"]
    assert result["confidence"] == baseline["confidence"]


def test_analysis_reports_no_timestamp_gaps_for_regular_or_untimestamped_data():
    regular = candles()
    regular.insert(
        0,
        "timestamp",
        pd.date_range("2024-01-01", periods=len(regular), freq="15min", tz="UTC"),
    )
    regular_result = analyze_ohlcv(regular)
    assert regular_result["timestamp_interval_seconds"] == 900.0
    assert regular_result["timestamp_gap_count"] == 0
    assert regular_result["estimated_missing_candles"] == 0

    untimestamped_result = analyze_ohlcv(candles())
    assert untimestamped_result["timestamp_interval_seconds"] is None
    assert untimestamped_result["timestamp_gap_count"] == 0
    assert untimestamped_result["estimated_missing_candles"] == 0
