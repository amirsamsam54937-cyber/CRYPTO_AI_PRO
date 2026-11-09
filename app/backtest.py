"""Leakage-aware single-position OHLCV backtest for research.

Signals use only candles strictly before the execution candle. Intrabar ambiguity
is handled conservatively: stop is checked before target. Funding is an estimate,
not exchange historical funding data.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
import math

import numpy as np
import pandas as pd

from app.advanced_analysis import analyze_ohlcv


@dataclass
class Trade:
    side: str
    signal_time: str
    entry_time: str
    exit_time: str
    entry: float
    exit: float
    stop: float
    target: float
    quantity: float
    leverage: float
    gross_pnl: float
    fees: float
    slippage_cost: float
    funding_estimate: float
    net_pnl: float
    exit_reason: str
    equity_after: float


def run_backtest(
    frame: pd.DataFrame,
    initial_equity: float = 1000.0,
    risk_per_trade: float = 0.005,
    max_leverage: float = 3.0,
    fee_rate: float = 0.0006,
    slippage_rate: float = 0.0005,
    funding_rate_8h: float = 0.0001,
    atr_multiple: float = 1.5,
    reward_risk: float = 2.0,
    warmup: int = 100,
) -> dict:
    """Backtest one position at a time with fixed fractional risk sizing."""
    required = {"open", "high", "low", "close", "volume"}
    if not required.issubset(frame.columns):
        raise ValueError("OHLCV columns are required")
    data = frame.copy().reset_index(drop=True)
    for col in required:
        data[col] = pd.to_numeric(data[col], errors="coerce")
    values = data[["open", "high", "low", "close", "volume"]].to_numpy(dtype=float)
    if len(data) <= warmup + 2 or not np.isfinite(values).all():
        raise ValueError("Insufficient or non-finite OHLCV history")

    prices = data[["open", "high", "low", "close"]]
    if (prices <= 0).any().any() or (data["volume"] < 0).any():
        raise ValueError("OHLC prices must be positive and volume cannot be negative")
    invalid_bounds = (
        (data["high"] < data[["open", "close"]].max(axis=1))
        | (data["low"] > data[["open", "close"]].min(axis=1))
        | (data["high"] < data["low"])
    )
    if invalid_bounds.any():
        raise ValueError("OHLC candle high/low bounds are inconsistent")
    if "timestamp" in data.columns:
        timestamps = pd.to_datetime(data["timestamp"], utc=True, errors="coerce")
        if timestamps.isna().any() or timestamps.duplicated().any() or not timestamps.is_monotonic_increasing:
            raise ValueError("Candle timestamps must be valid, unique and strictly increasing")
    if initial_equity <= 0 or not 0 < risk_per_trade <= 0.02:
        raise ValueError("Invalid equity or risk fraction")
    if not 1 <= max_leverage <= 5 or fee_rate < 0 or slippage_rate < 0 or funding_rate_8h < 0:
        raise ValueError("Invalid execution cost or leverage settings")
    if atr_multiple <= 0 or reward_risk < 1:
        raise ValueError("Invalid stop or reward/risk parameters")

    equity = float(initial_equity)
    peak = equity
    max_dd = 0.0
    trades: list[Trade] = []
    position = None
    equity_curve = [{"bar": 0, "equity": equity}]
    daily_start_equity = equity
    halted = False

    def adverse(price: float, side: str, entering: bool) -> float:
        # LONG entry buys higher / exit sells lower; SHORT is inverse.
        direction = 1 if side == "LONG" else -1
        sign = direction if entering else -direction
        return price * (1 + sign * slippage_rate)

    for i in range(warmup, len(data)):
        row = data.iloc[i]
        ts = str(data.iloc[i]["timestamp"]) if "timestamp" in data.columns else str(i)

        # Manage existing trade using this candle's OHLC. Stop wins if both levels hit.
        if position is not None:
            side = position["side"]
            stop_hit = row["low"] <= position["stop"] if side == "LONG" else row["high"] >= position["stop"]
            target_hit = row["high"] >= position["target"] if side == "LONG" else row["low"] <= position["target"]
            exit_reason = "STOP_LOSS" if stop_hit else ("TAKE_PROFIT" if target_hit else None)
            if exit_reason:
                raw_exit = position["stop"] if stop_hit else position["target"]
                exit_price = adverse(float(raw_exit), side, entering=False)
                direction = 1 if side == "LONG" else -1
                gross = (exit_price - position["entry"]) * position["quantity"] * direction
                exit_fee = abs(exit_price * position["quantity"]) * fee_rate
                elapsed_hours = max(0.0, (i - position["entry_index"]) * position["bar_hours"])
                funding = position["notional"] * funding_rate_8h * (elapsed_hours / 8.0)
                fees = position["entry_fee"] + exit_fee
                slip_cost = position["entry_slip_cost"] + abs(raw_exit-exit_price)*position["quantity"]
                net = gross - fees - funding
                # Entry fee was deducted at entry; only add exit cashflow here.
                equity += gross - exit_fee - funding
                trades.append(Trade(
                    side=side, signal_time=position["signal_time"], entry_time=position["entry_time"],
                    exit_time=ts, entry=position["entry"], exit=exit_price,
                    stop=position["stop"], target=position["target"], quantity=position["quantity"],
                    leverage=position["leverage"], gross_pnl=gross, fees=fees,
                    slippage_cost=slip_cost, funding_estimate=funding, net_pnl=net,
                    exit_reason=exit_reason, equity_after=equity,
                ))
                position = None

        if equity <= 0:
            equity = 0.0
            halted = True
        if not halted and position is None and i > warmup:
            # Rolling signal sees data only through i-1. Entry occurs at current bar open.
            history = data.iloc[:i]
            try:
                analysis = analyze_ohlcv(history.tail(300))
            except ValueError:
                analysis = {"direction": "NEUTRAL"}
            side = "LONG" if analysis["direction"] == "LONG_BIAS" else (
                "SHORT" if analysis["direction"] == "SHORT_BIAS" else None)
            atr_value = float(analysis.get("atr", 0) or 0)
            entry_raw = float(row["open"])
            if side and atr_value > 0 and entry_raw > 0:
                stop_raw = entry_raw - atr_multiple*atr_value if side == "LONG" else entry_raw + atr_multiple*atr_value
                distance = abs(entry_raw-stop_raw)
                risk_cash = equity*risk_per_trade
                qty = risk_cash/distance
                notional = qty*entry_raw
                max_notional = equity*max_leverage
                if notional > max_notional:
                    qty = max_notional/entry_raw
                    notional = max_notional
                    risk_cash = qty*distance
                entry_price = adverse(entry_raw, side, entering=True)
                target = entry_raw + (1 if side == "LONG" else -1)*distance*reward_risk
                entry_fee = abs(entry_price*qty)*fee_rate
                entry_slip = abs(entry_price-entry_raw)*qty
                # Rough timeframe estimate from timestamp cadence; fallback one hour.
                bar_hours = 1.0
                if "timestamp" in data.columns and i > 0:
                    try:
                        delta = pd.to_datetime(data.iloc[i]["timestamp"]) - pd.to_datetime(data.iloc[i-1]["timestamp"])
                        bar_hours = max(1/60, delta.total_seconds()/3600)
                    except Exception:
                        pass
                position = {
                    "side": side, "entry": entry_price, "stop": stop_raw, "target": target,
                    "quantity": qty, "notional": notional, "leverage": notional/equity if equity else 0,
                    "entry_fee": entry_fee, "entry_slip_cost": entry_slip,
                    "entry_index": i, "entry_time": ts, "signal_time": str(i-1),
                    "bar_hours": bar_hours,
                }
                # Entry fee is reserved immediately so equity curve reflects actual costs.
                equity -= entry_fee

        peak = max(peak, equity)
        max_dd = max(max_dd, (peak-equity)/peak if peak > 0 else 1.0)
        equity_curve.append({"bar": i, "equity": equity})

    # Mark any still-open trade at final close; never leave an unreported position.
    if position is not None:
        last = data.iloc[-1]
        side = position["side"]
        raw_exit = float(last["close"])
        exit_price = adverse(raw_exit, side, entering=False)
        direction = 1 if side == "LONG" else -1
        gross = (exit_price-position["entry"])*position["quantity"]*direction
        exit_fee = abs(exit_price*position["quantity"])*fee_rate
        elapsed_hours = max(0.0, (len(data)-1-position["entry_index"])*position["bar_hours"])
        funding = position["notional"]*funding_rate_8h*(elapsed_hours/8.0)
        fees = position["entry_fee"]+exit_fee
        slip_cost = position["entry_slip_cost"]+abs(raw_exit-exit_price)*position["quantity"]
        net = gross-exit_fee-funding  # entry fee was already deducted from equity
        equity += gross-exit_fee-funding
        trades.append(Trade(
            side=side, signal_time=position["signal_time"], entry_time=position["entry_time"],
            exit_time=str(data.iloc[-1].get("timestamp", len(data)-1)),
            entry=position["entry"], exit=exit_price, stop=position["stop"], target=position["target"],
            quantity=position["quantity"], leverage=position["leverage"], gross_pnl=gross,
            fees=fees, slippage_cost=slip_cost, funding_estimate=funding,
            net_pnl=net, exit_reason="END_OF_DATA", equity_after=equity,
        ))

    pnls = [t.net_pnl for t in trades]
    wins = [p for p in pnls if p > 0]
    losses = [p for p in pnls if p < 0]
    gross_profit, gross_loss = sum(wins), abs(sum(losses))
    returns = pd.Series([p/max(initial_equity, 1e-12) for p in pnls], dtype=float)
    sharpe = float(returns.mean()/returns.std(ddof=1)*math.sqrt(len(returns))) if len(returns)>1 and returns.std(ddof=1)>0 else 0.0
    return {
        "initial_equity": initial_equity, "final_equity": round(equity, 8),
        "net_pnl": round(equity-initial_equity, 8),
        "return_percent": round((equity/initial_equity-1)*100, 4),
        "trade_count": len(trades), "win_rate_percent": round(len(wins)/len(trades)*100, 2) if trades else 0.0,
        "profit_factor": round(gross_profit/gross_loss, 4) if gross_loss else (None if not gross_profit else "infinity"),
        "max_drawdown_percent": round(max_dd*100, 4), "trade_return_sharpe": round(sharpe, 4),
        "estimated_fees": round(sum(t.fees for t in trades), 8),
        "estimated_slippage_cost": round(sum(t.slippage_cost for t in trades), 8),
        "estimated_funding": round(sum(t.funding_estimate for t in trades), 8),
        "assumptions": {"fee_rate_per_side": fee_rate, "slippage_rate_per_side": slippage_rate,
                        "funding_rate_per_8h_estimate": funding_rate_8h,
                        "signal_uses_only_prior_closed_candles": True,
                        "intrabar_rule": "stop-first if stop and target both touched",
                        "max_leverage": max_leverage, "risk_per_trade": risk_per_trade},
        "trades": [asdict(t) for t in trades],
        "equity_curve": equity_curve,
    }
