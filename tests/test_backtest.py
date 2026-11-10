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


def test_backtest_rejects_non_finite_values_negative_volume_and_bad_candle_bounds():
    cases = []
    non_finite = sample_frame()
    non_finite.loc[120, "close"] = np.inf
    cases.append(non_finite)

    negative_volume = sample_frame()
    negative_volume.loc[120, "volume"] = -1
    cases.append(negative_volume)

    broken_high = sample_frame()
    broken_high.loc[120, "high"] = broken_high.loc[120, "close"] - 0.01
    cases.append(broken_high)

    broken_low = sample_frame()
    broken_low.loc[120, "low"] = broken_low.loc[120, "open"] + 0.01
    cases.append(broken_low)

    for frame in cases:
        with pytest.raises(ValueError):
            run_backtest(frame, warmup=100)


def test_backtest_rejects_duplicate_or_out_of_order_timestamps():
    duplicate = sample_frame()
    duplicate.loc[120, "timestamp"] = duplicate.loc[119, "timestamp"]
    with pytest.raises(ValueError, match="timestamps"):
        run_backtest(duplicate, warmup=100)

    out_of_order = sample_frame()
    out_of_order.loc[120, "timestamp"] = out_of_order.loc[119, "timestamp"] - pd.Timedelta(hours=1)
    with pytest.raises(ValueError, match="timestamps"):
        run_backtest(out_of_order, warmup=100)


def test_backtest_terminal_liquidation_updates_equity_curve_and_drawdown(monkeypatch):
    frame = sample_frame(104)
    frame.loc[100:102, ["open", "high", "low", "close"]] = [100.0, 100.5, 99.5, 100.0]
    frame.loc[103, ["open", "high", "low", "close"]] = [100.0, 100.5, 98.9, 99.0]
    monkeypatch.setattr(
        "app.backtest.analyze_ohlcv",
        lambda history: {"direction": "LONG_BIAS", "atr": 1.0},
    )

    result = run_backtest(
        frame,
        initial_equity=1000.0,
        risk_per_trade=0.02,
        fee_rate=0.001,
        slippage_rate=0.0,
        funding_rate_8h=0.0,
        warmup=100,
    )

    assert result["trade_count"] == 1
    assert result["trades"][0]["exit_reason"] == "END_OF_DATA"
    assert result["trades"][0]["net_pnl"] == pytest.approx(result["final_equity"] - 1000.0)
    assert result["trades"][0]["fees"] > 0
    assert result["equity_curve"][-1]["equity"] == pytest.approx(result["final_equity"])
    expected_drawdown = (1000.0 - result["final_equity"]) / 1000.0 * 100.0
    assert result["max_drawdown_percent"] == pytest.approx(expected_drawdown, abs=5e-5)
