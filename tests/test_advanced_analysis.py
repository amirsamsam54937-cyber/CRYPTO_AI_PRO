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
