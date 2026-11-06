"""XT USDT perpetual multi-timeframe market scanner.

Public market data only. It never accesses account credentials or places orders.
"""
from __future__ import annotations

from typing import Any
import ccxt
import pandas as pd

from app.indicators import build_signal, atr
from app.advanced_analysis import analyze_ohlcv


def _frame(rows: list[list[float]]) -> pd.DataFrame:
    if not rows or len(rows) < 52:
        raise ValueError("Not enough candles")
    frame = pd.DataFrame(rows, columns=["timestamp", "open", "high", "low", "close", "volume"])
    frame["timestamp"] = pd.to_datetime(frame["timestamp"], unit="ms", utc=True)
    # Exclude the currently forming candle to avoid look-ahead / repainting.
    return frame.iloc[:-1].reset_index(drop=True)


def scan_xt_usdt_perpetuals(limit: int = 20, min_quote_volume: float = 100_000,
                            timeframes: tuple[str, ...] = ("15m", "1h", "4h")) -> list[dict[str, Any]]:
    if not 1 <= limit <= 50:
        raise ValueError("limit must be between 1 and 50")
    exchange = ccxt.xt({"enableRateLimit": True, "timeout": 20000,
                        "options": {"defaultType": "swap"}})
    markets = exchange.load_markets()
    eligible = {
        symbol for symbol, market in markets.items()
        if market.get("active") is not False
        and market.get("swap") is True
        and market.get("quote") == "USDT"
        and market.get("linear") is not False
    }
    tickers = exchange.fetch_tickers(list(eligible))
    ranked = []
    for symbol, ticker in tickers.items():
        quote_volume = ticker.get("quoteVolume")
        if quote_volume is None:
            last, base_volume = ticker.get("last"), ticker.get("baseVolume")
            quote_volume = (last or 0) * (base_volume or 0)
        if quote_volume and float(quote_volume) >= min_quote_volume:
            ranked.append((symbol, float(quote_volume)))
    ranked.sort(key=lambda item: item[1], reverse=True)

    results = []
    for symbol, quote_volume in ranked[:limit]:
        try:
            signals = {}
            for tf in timeframes:
                candles = exchange.fetch_ohlcv(symbol, timeframe=tf, limit=160)
                frame = _frame(candles)
                signals[tf] = build_signal(frame)
                signals[tf]["advanced"] = analyze_ohlcv(frame)
            directions = [signals[tf]["direction"] for tf in timeframes]
            long_votes = directions.count("LONG_BIAS")
            short_votes = directions.count("SHORT_BIAS")
            direction = ("LONG" if long_votes == len(timeframes) else
                         "SHORT" if short_votes == len(timeframes) else "NO_TRADE")
            primary = signals[timeframes[0]]
            entry = primary["last_price"]
            volatility = primary["atr"]
            stop_distance = 1.5 * volatility
            stop = entry - stop_distance if direction == "LONG" else entry + stop_distance
            risk_distance = abs(entry - stop)
            sign = 1 if direction == "LONG" else -1
            targets = [entry + sign * risk_distance * multiple for multiple in (1, 2, 3)]
            results.append({
                "symbol": symbol, "quote_volume_24h": quote_volume,
                "direction": direction, "agreement": f"{max(long_votes, short_votes)}/{len(timeframes)}",
                "advanced_consensus": {tf: signals[tf]["advanced"]["direction"] for tf in timeframes},
                "market_regime": primary_regime if (primary_regime := signals[timeframes[0]]["advanced"]["regime"]) else "UNKNOWN",
                "volume_ratio": signals[timeframes[0]]["advanced"]["volume_ratio"],
                "entry_reference": entry, "stop_loss_reference": stop if direction != "NO_TRADE" else None,
                "take_profit_references": targets if direction != "NO_TRADE" else [],
                "leverage_ceiling_estimate": min(5, max(1, round(entry / max(stop_distance, 1e-12), 1))) if direction != "NO_TRADE" else 1,
                "timeframes": signals,
                "note": "Closed-candle research signal only; not an order or guaranteed fill."
            })
        except (ccxt.BaseError, ValueError, KeyError, TypeError):
            continue
    results.sort(key=lambda row: (row["direction"] != "NO_TRADE",
                                  max(float(row["timeframes"][tf]["confidence"]) for tf in timeframes),
                                  row["quote_volume_24h"]), reverse=True)
    return results
