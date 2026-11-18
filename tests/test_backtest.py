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

def test_backtest_does_not_reenter_at_same_bar_open_after_intrabar_exit(monkeypatch):
    frame = sample_frame(105)
    frame.loc[100:101, ["open", "high", "low", "close"]] = [100.0, 100.5, 99.5, 100.0]
    # The first LONG reaches its target during bar 102. A second position may
    # only be entered at bar 103's open, never retroactively at bar 102's open.
    frame.loc[102, ["open", "high", "low", "close"]] = [100.0, 103.5, 99.5, 103.0]
    frame.loc[103:104, ["open", "high", "low", "close"]] = [100.0, 100.5, 99.5, 100.0]
    monkeypatch.setattr(
        "app.backtest.analyze_ohlcv",
        lambda history: {"direction": "LONG_BIAS", "atr": 1.0},
    )

    result = run_backtest(
        frame,
        initial_equity=1000.0,
        risk_per_trade=0.01,
        max_leverage=1.0,
        fee_rate=0.0,
        slippage_rate=0.0,
        funding_rate_8h=0.0,
        warmup=100,
    )

    assert result["trade_count"] == 2
    assert result["trades"][0]["exit_time"] == str(frame.loc[102, "timestamp"])
    assert result["trades"][1]["entry_time"] == str(frame.loc[103, "timestamp"])
    assert result["trades"][1]["exit_reason"] == "END_OF_DATA"

def test_drawdown_marks_open_position_to_market(monkeypatch):
    frame = sample_frame(104)
    # Entry occurs at bar 101. The trade remains open through the final close,
    # but its unrealized loss must already be visible in the equity curve/DD.
    frame.loc[100:103, ["open", "high", "low", "close"]] = [
        100.0, 100.5, 99.0, 99.0
    ]
    monkeypatch.setattr(
        "app.backtest.analyze_ohlcv",
        lambda history: {"direction": "LONG_BIAS", "atr": 1.0},
    )

    result = run_backtest(
        frame,
        initial_equity=1000.0,
        risk_per_trade=0.01,
        max_leverage=1.0,
        fee_rate=0.0,
        slippage_rate=0.0,
        funding_rate_8h=0.0,
        warmup=100,
    )

    assert result["trade_count"] == 1
    expected_dd = (1000.0 - result["final_equity"]) / 1000.0 * 100.0
    assert expected_dd > 0.5
    assert result["max_drawdown_percent"] == pytest.approx(expected_dd, abs=5e-5)
    bar_101_equity = next(
        point["equity"] for point in result["equity_curve"]
        if point["bar"] == 101
    )
    assert bar_101_equity < result["initial_equity"]
    assert result["assumptions"]["drawdown_marking"].startswith("close-to-market")

def test_funding_uses_actual_elapsed_time_for_irregular_candle_intervals(monkeypatch):
    frame = sample_frame(104)
    frame.loc[100:103, ["open", "high", "low", "close"]] = [
        100.0, 100.5, 99.5, 100.0
    ]
    # Entry is at bar 101. The final bar is seven hours after bar 102,
    # so elapsed time from entry to liquidation is eight hours, not two bars.
    frame.loc[103, "timestamp"] = frame.loc[102, "timestamp"] + pd.Timedelta(hours=7)
    monkeypatch.setattr(
        "app.backtest.analyze_ohlcv",
        lambda history: {"direction": "LONG_BIAS", "atr": 1.0},
    )

    result = run_backtest(
        frame,
        initial_equity=1000.0,
        risk_per_trade=0.01,
        max_leverage=1.0,
        fee_rate=0.0,
        slippage_rate=0.0,
        funding_rate_8h=0.001,
        warmup=100,
    )

    assert result["trade_count"] == 1
    trade = result["trades"][0]
    expected_eight_hour_funding = trade["quantity"] * trade["entry"] * 0.001
    assert trade["exit_reason"] == "END_OF_DATA"
    assert trade["funding_estimate"] == pytest.approx(expected_eight_hour_funding)

def test_position_size_caps_net_stop_loss_after_round_trip_fees_and_slippage(monkeypatch):
    frame = sample_frame(103)
    frame.loc[100:101, ["open", "high", "low", "close"]] = [
        100.0, 100.5, 99.5, 100.0
    ]
    # Entry is at bar 101; only the following bar reaches the stop.
    frame.loc[102, ["open", "high", "low", "close"]] = [
        100.0, 100.5, 98.0, 99.0
    ]
    monkeypatch.setattr(
        "app.backtest.analyze_ohlcv",
        lambda history: {"direction": "LONG_BIAS", "atr": 1.0},
    )

    result = run_backtest(
        frame,
        initial_equity=1000.0,
        risk_per_trade=0.01,
        max_leverage=3.0,
        fee_rate=0.001,
        slippage_rate=0.0005,
        funding_rate_8h=0.0,
        warmup=100,
    )

    assert result["trade_count"] == 1
    trade = result["trades"][0]
    assert trade["exit_reason"] == "STOP_LOSS"
    # The 1% risk budget now includes adverse entry/exit fills and both fees.
    assert trade["net_pnl"] == pytest.approx(-10.0, abs=1e-8)
    assert result["final_equity"] == pytest.approx(990.0, abs=1e-8)
    assert trade["fees"] > 0
    assert trade["slippage_cost"] > 0

@pytest.mark.parametrize(
    "parameter",
    [
        "initial_equity", "risk_per_trade", "max_leverage", "fee_rate",
        "slippage_rate", "funding_rate_8h", "atr_multiple", "reward_risk",
    ],
)
@pytest.mark.parametrize("bad_value", [np.nan, np.inf, -np.inf])
def test_backtest_rejects_non_finite_execution_and_risk_parameters(parameter, bad_value):
    with pytest.raises(ValueError, match="finite number"):
        run_backtest(sample_frame(), **{parameter: bad_value})


@pytest.mark.parametrize("bad_warmup", [-1, 1.5, True])
def test_backtest_rejects_invalid_warmup_type_or_range(bad_warmup):
    with pytest.raises(ValueError, match="warmup"):
        run_backtest(sample_frame(), warmup=bad_warmup)

def test_stop_market_exit_uses_worse_open_when_price_gaps_through_stop(monkeypatch):
    frame = sample_frame(103)
    frame.loc[101, ["open", "high", "low", "close"]] = [100.0, 100.5, 99.5, 100.0]
    # The planned LONG stop is 98.5, but the next candle opens at 97.0.
    # A stop-market fill cannot be assumed at the unavailable 98.5 price.
    frame.loc[102, ["open", "high", "low", "close"]] = [97.0, 97.5, 96.5, 97.0]
    monkeypatch.setattr(
        "app.backtest.analyze_ohlcv",
        lambda history: {"direction": "LONG_BIAS", "atr": 1.0},
    )

    result = run_backtest(
        frame,
        initial_equity=1000.0,
        risk_per_trade=0.01,
        max_leverage=3.0,
        fee_rate=0.0,
        slippage_rate=0.0,
        funding_rate_8h=0.0,
        warmup=100,
    )

    assert result["trade_count"] == 1
    trade = result["trades"][0]
    assert trade["exit_reason"] == "STOP_LOSS"
    assert trade["stop"] == pytest.approx(98.5)
    assert trade["exit"] == pytest.approx(97.0)
    assert trade["net_pnl"] == pytest.approx(-20.0)
    assert result["final_equity"] == pytest.approx(980.0)
    assert result["assumptions"]["stop_gap_rule"].startswith("use opening price")


@pytest.mark.parametrize(
    ("side", "funding_rate", "expected_sign"),
    [
        ("LONG", 0.001, 1.0),
        ("SHORT", 0.001, -1.0),
        ("LONG", -0.001, 1.0),
        ("SHORT", -0.001, -1.0),
    ],
)
def test_funding_cashflow_sign_tracks_position_side_and_signed_rate(
    monkeypatch, side, funding_rate, expected_sign
):
    frame = sample_frame(103)
    frame.loc[100:102, ["open", "high", "low", "close"]] = [
        100.0, 100.5, 99.5, 100.0
    ]
    # Position opens at bar 101 and is liquidated eight hours later at bar 102.
    frame.loc[102, "timestamp"] = (
        frame.loc[101, "timestamp"] + pd.Timedelta(hours=8)
    )
    direction = "LONG_BIAS" if side == "LONG" else "SHORT_BIAS"
    monkeypatch.setattr(
        "app.backtest.analyze_ohlcv",
        lambda history: {"direction": direction, "atr": 1.0},
    )

    result = run_backtest(
        frame,
        initial_equity=1000.0,
        risk_per_trade=0.01,
        max_leverage=1.0,
        fee_rate=0.0,
        slippage_rate=0.0,
        funding_rate_8h=funding_rate,
        warmup=100,
    )

    assert result["trade_count"] == 1
    trade = result["trades"][0]
    expected_funding = (
        trade["quantity"] * abs(trade["entry"]) * funding_rate * expected_sign
    )
    assert trade["funding_estimate"] == pytest.approx(expected_funding)
    assert trade["net_pnl"] == pytest.approx(-expected_funding)
    assert result["final_equity"] == pytest.approx(1000.0 - expected_funding)
    assert "positive rates charge LONG and credit SHORT" in (
        result["assumptions"]["funding_cashflow_convention"]
    )

@pytest.mark.parametrize(
    ("side", "bar_high", "bar_low", "expected_reason", "expected_exit", "expected_pnl"),
    [
        ("LONG", 100.5, 98.0, "STOP_LOSS", 98.5, -10.0),
        ("LONG", 103.5, 99.5, "TAKE_PROFIT", 103.0, 20.0),
        ("SHORT", 102.0, 99.5, "STOP_LOSS", 101.5, -10.0),
        ("SHORT", 100.5, 96.5, "TAKE_PROFIT", 97.0, 20.0),
        # If both levels trade inside one OHLC candle, stop-first is conservative.
        ("LONG", 104.0, 98.0, "STOP_LOSS", 98.5, -10.0),
    ],
)
def test_entry_candle_intrabar_stop_and_target_are_processed(
    monkeypatch, side, bar_high, bar_low, expected_reason, expected_exit, expected_pnl
):
    frame = sample_frame(103)
    frame.loc[100:102, ["open", "high", "low", "close"]] = [
        100.0, 100.5, 99.5, 100.0
    ]
    # The signal is formed from bars through 100; entry is at bar 101 open.
    # Its remaining high/low range must already be checked for stop/target.
    frame.loc[101, ["open", "high", "low", "close"]] = [
        100.0, bar_high, bar_low, 100.0
    ]
    direction = "LONG_BIAS" if side == "LONG" else "SHORT_BIAS"
    signal_calls = 0

    def one_entry_signal(history):
        nonlocal signal_calls
        signal_calls += 1
        return {
            "direction": direction if signal_calls == 1 else "NEUTRAL",
            "atr": 1.0,
        }

    monkeypatch.setattr(
        "app.backtest.analyze_ohlcv", one_entry_signal,
    )

    result = run_backtest(
        frame,
        initial_equity=1000.0,
        risk_per_trade=0.01,
        max_leverage=3.0,
        fee_rate=0.0,
        slippage_rate=0.0,
        funding_rate_8h=0.0,
        warmup=100,
    )

    assert result["trade_count"] == 1
    trade = result["trades"][0]
    assert trade["entry_time"] == str(frame.loc[101, "timestamp"])
    assert trade["exit_time"] == str(frame.loc[101, "timestamp"])
    assert trade["exit_reason"] == expected_reason
    assert trade["exit"] == pytest.approx(expected_exit)
    assert trade["net_pnl"] == pytest.approx(expected_pnl)
    assert result["final_equity"] == pytest.approx(1000.0 + expected_pnl)

