import numpy as np
import pandas as pd
import pytest

from app.backtest import run_backtest


def sample_frame(n=180):
    x = np.arange(n)
    close = 100 + x*0.12 + np.sin(x/3)
    return pd.DataFrame({
        "timestamp": pd.date_range("2024-01-01", periods=n, freq="h", tz="UTC"),
        "open": close-0.1, "high": close+0.8, "low": close-0.8,
        "close": close, "volume": np.full(n, 1000.0),
    })


def test_backtest_is_reproducible_and_reports_costs():
    frame = sample_frame()
    a = run_backtest(frame, warmup=100)
    b = run_backtest(frame, warmup=100)
    assert a["final_equity"] == b["final_equity"]
    assert a["assumptions"]["signal_uses_only_prior_closed_candles"] is True
    assert "estimated_fees" in a
    assert a["trade_count"] == len(a["trades"])


def test_backtest_rejects_invalid_input():
    with pytest.raises(ValueError):
        run_backtest(sample_frame(50))
    with pytest.raises(ValueError):
        run_backtest(sample_frame(), risk_per_trade=0.5)

def test_default_virtual_equity_is_ten_usdt():
    import inspect
    from app.backtest import run_backtest
    assert inspect.signature(run_backtest).parameters["initial_equity"].default == 10.0


def test_signed_funding_is_side_aware(monkeypatch):
    from app import backtest as module

    frame = pd.DataFrame({
        "timestamp": pd.date_range("2024-01-01", periods=112, freq="h", tz="UTC"),
        "open": np.full(112, 100.0),
        "high": np.full(112, 101.0),
        "low": np.full(112, 99.0),
        "close": np.full(112, 100.0),
        "volume": np.full(112, 1000.0),
    })

    def fake_analysis(history):
        return {"direction": "LONG_BIAS", "atr": 1.0, "adx": 20,
                "volume_ratio": 1, "confidence": 80, "score": 5,
                "regime": "TRENDING"}

    monkeypatch.setattr(module, "analyze_ohlcv", fake_analysis)
    long_result = module.run_backtest(frame, warmup=100, funding_rate_8h=0.001)
    assert long_result["estimated_funding"] > 0

    def fake_short(history):
        return {"direction": "SHORT_BIAS", "atr": 1.0, "adx": 20,
                "volume_ratio": 1, "confidence": 80, "score": -5,
                "regime": "TRENDING"}

    monkeypatch.setattr(module, "analyze_ohlcv", fake_short)
    short_result = module.run_backtest(frame, warmup=100, funding_rate_8h=0.001)
    assert short_result["estimated_funding"] < 0
