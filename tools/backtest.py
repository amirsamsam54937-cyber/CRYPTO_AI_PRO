#!/usr/bin/env python3
"""Research-only historical backtest for CRYPTO AI PRO signals.

No API key is needed. Uses public Binance USD-M Futures klines.
Signals use closed 15m/1h/4h candles; execution starts at the next 15m open.
"""
from __future__ import annotations

import argparse
import time
from dataclasses import dataclass

import numpy as np
import pandas as pd
import requests

BASE = "https://fapi.binance.com"
INTERVAL_MS = {"15m": 15 * 60_000, "1h": 60 * 60_000, "4h": 4 * 60 * 60_000}


def download_klines(symbol: str, interval: str, days: int) -> pd.DataFrame:
    end = int(time.time() * 1000)
    start = end - days * 24 * 60 * 60_000
    rows: list[list] = []
    cursor = start
    while cursor < end:
        response = requests.get(
            BASE + "/fapi/v1/klines",
            params={"symbol": symbol, "interval": interval, "startTime": cursor,
                    "endTime": end, "limit": 1500},
            timeout=20,
        )
        response.raise_for_status()
        batch = response.json()
        if not batch:
            break
        rows.extend(batch)
        cursor = int(batch[-1][0]) + INTERVAL_MS[interval]
        if len(batch) < 1500:
            break
        time.sleep(0.12)

    if not rows:
        raise RuntimeError(f"No Binance futures candles returned for {symbol} {interval}")
    frame = pd.DataFrame(rows, columns=[
        "open_time", "open", "high", "low", "close", "volume",
        "close_time", "quote_volume", "trades", "taker_buy_base",
        "taker_buy_quote", "ignore",
    ])
    for col in ("open", "high", "low", "close", "volume"):
        frame[col] = pd.to_numeric(frame[col], errors="coerce")
    frame["open_time"] = pd.to_datetime(frame["open_time"], unit="ms", utc=True)
    frame["close_time"] = pd.to_datetime(frame["close_time"], unit="ms", utc=True)
    frame = frame.drop_duplicates("open_time").sort_values("open_time")
    # Exclude the still-forming candle.
    now = pd.Timestamp.now(tz="UTC")
    return frame.loc[frame["close_time"] < now].reset_index(drop=True)


def indicator_frame(frame: pd.DataFrame, prefix: str) -> pd.DataFrame:
    x = frame[["open_time", "close_time", "open", "high", "low", "close", "volume"]].copy()
    close = x["close"]
    ema20 = close.ewm(span=20, adjust=False).mean()
    ema50 = close.ewm(span=50, adjust=False).mean()
    macd = close.ewm(span=12, adjust=False).mean() - close.ewm(span=26, adjust=False).mean()
    macd_signal = macd.ewm(span=9, adjust=False).mean()
    delta = close.diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
    rs = gain / loss.replace(0, np.nan)
    rsi = 100 - (100 / (1 + rs))
    rsi = rsi.where(loss.ne(0), 100).fillna(50)

    previous = close.shift(1)
    tr = pd.concat([
        x["high"] - x["low"],
        (x["high"] - previous).abs(),
        (x["low"] - previous).abs(),
    ], axis=1).max(axis=1)
    atr = tr.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()

    score = np.where(ema20 > ema50, 1, -1)
    score += np.where(macd > macd_signal, 1, -1)
    score += np.where(rsi >= 55, 1, np.where(rsi <= 45, -1, 0))
    x[prefix + "_score"] = score
    x[prefix + "_rsi"] = rsi
    x[prefix + "_atr"] = atr
    x["available_at"] = x["close_time"]
    return x[["available_at", prefix + "_score", prefix + "_rsi", prefix + "_atr"]]


def prepare_signal_table(symbol: str, days: int) -> pd.DataFrame:
    frames = {tf: indicator_frame(download_klines(symbol, tf, days), tf)
              for tf in ("15m", "1h", "4h")}
    base = download_klines(symbol, "15m", days)
    base["signal_at"] = base["close_time"]
    out = pd.merge_asof(
        base.sort_values("signal_at"),
        frames["15m"].sort_values("available_at"),
        left_on="signal_at", right_on="available_at", direction="backward",
    ).drop(columns=["available_at"])
    for tf in ("1h", "4h"):
        out = pd.merge_asof(
            out.sort_values("signal_at"),
            frames[tf].sort_values("available_at"),
            left_on="signal_at", right_on="available_at", direction="backward",
        ).drop(columns=["available_at"])
    out["score"] = out["15m_score"] + out["1h_score"] + out["4h_score"]
    return out.dropna(subset=["15m_atr", "15m_score", "1h_score", "4h_score"]).reset_index(drop=True)


@dataclass
class Trade:
    side: str
    entry_time: str
    entry: float
    stop: float
    tp1: float
    tp2: float
    tp3: float
    exit_time: str
    net_r: float
    reason: str


def run_backtest(data: pd.DataFrame, leverage: float, risk_pct: float,
                 fee_rate: float, slippage_bps: float, max_hold_bars: int) -> tuple[pd.DataFrame, dict]:
    trades: list[Trade] = []
    equity = 1000.0
    peak = equity
    max_dd = 0.0
    equity_curve = [equity]
    i = 60
    slip = slippage_bps / 10_000
    while i < len(data) - 2:
        row = data.iloc[i]
        score = int(row["score"])
        side = "LONG" if score >= 4 else "SHORT" if score <= -4 else ""
        if not side or not np.isfinite(row["15m_atr"]):
            i += 1
            continue

        entry_row = data.iloc[i + 1]
        raw_entry = float(entry_row["open"])
        entry = raw_entry * (1 + slip if side == "LONG" else 1 - slip)
        stop_dist = max(float(row["15m_atr"]) * 1.5, entry * 0.002)
        stop = entry - stop_dist if side == "LONG" else entry + stop_dist
        tp1 = entry + stop_dist if side == "LONG" else entry - stop_dist
        tp2 = entry + 2 * stop_dist if side == "LONG" else entry - 2 * stop_dist
        tp3 = entry + 3 * stop_dist if side == "LONG" else entry - 3 * stop_dist
        p1 = p2 = False
        gross_r = 0.0
        exit_reason = "TIME"
        exit_idx = min(i + 1 + max_hold_bars, len(data) - 1)

        for j in range(i + 1, exit_idx + 1):
            bar = data.iloc[j]
            high, low = float(bar["high"]), float(bar["low"])
            stop_hit = low <= stop if side == "LONG" else high >= stop
            hit1 = high >= tp1 if side == "LONG" else low <= tp1
            hit2 = high >= tp2 if side == "LONG" else low <= tp2
            hit3 = high >= tp3 if side == "LONG" else low <= tp3

            # Conservative intrabar ordering: stop is assumed first if both touch.
            if stop_hit:
                gross_r = -1.0 if not p1 else (0.1 if not p2 else 0.7)
                exit_reason = "SL"
                exit_idx = j
                break
            if hit3:
                gross_r = 0.4 + 0.6 + 0.9
                p1 = p2 = True
                exit_reason = "TP3"
                exit_idx = j
                break
            if hit2 and not p2:
                if not p1:
                    gross_r += 0.4
                    p1 = True
                gross_r += 0.6
                p2 = True
            elif hit1 and not p1:
                gross_r += 0.4
                p1 = True
            if j == exit_idx:
                close = float(bar["close"])
                move_r = (close - entry) / stop_dist if side == "LONG" else (entry - close) / stop_dist
                gross_r += (0.6 if p1 else 1.0) * float(np.clip(move_r, -1, 3))

        # Approximate entry + exit taker fees and slippage in R units.
        fee_r = (2 * fee_rate + 2 * slip) * entry / stop_dist
        net_r = gross_r - fee_r
        stop_pct = stop_dist / entry
        effective_risk = min(risk_pct, leverage * stop_pct)
        pnl = equity * effective_risk * net_r
        equity += pnl
        peak = max(peak, equity)
        max_dd = max(max_dd, (peak - equity) / peak if peak else 0)
        equity_curve.append(equity)
        trades.append(Trade(
            side, str(entry_row["open_time"]), entry, stop, tp1, tp2, tp3,
            str(data.iloc[exit_idx]["close_time"]), net_r, exit_reason,
        ))
        i = exit_idx + 1

    result = pd.DataFrame([t.__dict__ for t in trades])
    wins = int((result["net_r"] > 0).sum()) if not result.empty else 0
    losses = int((result["net_r"] <= 0).sum()) if not result.empty else 0
    gross_profit = result.loc[result.net_r > 0, "net_r"].sum() if not result.empty else 0
    gross_loss = abs(result.loc[result.net_r < 0, "net_r"].sum()) if not result.empty else 0
    stats = {
        "trades": len(result), "wins": wins, "losses": losses,
        "win_rate_pct": 100 * wins / len(result) if len(result) else 0,
        "ending_balance": equity, "net_return_pct": (equity / 1000 - 1) * 100,
        "max_drawdown_pct": max_dd * 100,
        "profit_factor": gross_profit / gross_loss if gross_loss else float("inf"),
        "max_leverage": leverage, "risk_per_trade_pct": risk_pct * 100,
    }
    return result, stats


def main() -> None:
    parser = argparse.ArgumentParser(description="CRYPTO AI PRO historical futures backtest (read-only)")
    parser.add_argument("--symbol", default="BTCUSDT")
    parser.add_argument("--days", type=int, default=90)
    parser.add_argument("--leverage", type=float, default=3.0)
    parser.add_argument("--risk", type=float, default=0.01, help="risk fraction per trade, default 0.01 = 1%%")
    parser.add_argument("--fee", type=float, default=0.0004, help="taker fee per side; verify your account tier")
    parser.add_argument("--slippage-bps", type=float, default=2.0)
    parser.add_argument("--max-hold-bars", type=int, default=96, help="96 bars = 24 hours on 15m")
    parser.add_argument("--out", default="backtest_trades.csv")
    args = parser.parse_args()

    print(f"Downloading {args.symbol} 15m/1h/4h candles for {args.days} days...")
    data = prepare_signal_table(args.symbol.upper(), args.days)
    trades, stats = run_backtest(data, args.leverage, args.risk, args.fee,
                                 args.slippage_bps, args.max_hold_bars)
    print("\n=== CRYPTO AI PRO BACKTEST ===")
    for key, value in stats.items():
        print(f"{key}: {value:.2f}" if isinstance(value, float) else f"{key}: {value}")
    trades.to_csv(args.out, index=False)
    print(f"\nTrade log saved: {args.out}")
    print("\nResearch only: historical results do not predict future performance.")
    print("Funding, liquidation mechanics, exchange outages and full market impact are not modeled.")


if __name__ == "__main__":
    main()
