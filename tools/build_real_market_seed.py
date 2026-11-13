#!/usr/bin/env python3
"""Collect a verifiable real-candle training corpus from public USDT perpetual markets.

Sole source: XT USDT-M public API. No cross-exchange fallback is permitted.
Only exchange-returned, closed candles are stored. No synthetic rows are generated.
"""
import csv, json, math, os, sys, time, hashlib
from datetime import datetime, timezone

TARGET = 1_000_000
MINIMUM = 1_000_000
TIMEFRAME = "1h"
OUT = "app/src/main/assets/market_seed.csv"
META = "market_seed_manifest.json"
START = 1577836800000
FIELDS = ["exchange","symbol","timeframe","timestamp","open","high","low","close","volume"]

def normalized_symbol(market):
    return (market.get("base", "") + market.get("quote", "")).upper()

def collect(exchange_id):
    import ccxt
    if exchange_id != "xt":
        raise ValueError("Only XT USDT-M futures history is permitted.")
    ex = ccxt.xt({"enableRateLimit": True, "timeout": 30000,
                  "options": {"defaultType": "swap"}})
    try:
        ex.load_markets()
        markets = [m for m in ex.markets.values()
                   if m.get("active") is not False and m.get("swap")
                   and m.get("linear") and m.get("quote") == "USDT"
                   and m.get("settle") == "USDT"]
        markets.sort(key=lambda m: m["symbol"])
        if not markets:
            raise RuntimeError(f"{exchange_id}: no active linear USDT perpetual markets")
        per_symbol = max(1, math.ceil(TARGET / len(markets)))
        rows, errors, coverage = [], [], []
        now = ex.milliseconds()
        for market_index, market in enumerate(markets, 1):
            symbol = market["symbol"]
            # Paginate real 1h candles. A single 1,000-row response per symbol
            # cannot guarantee a million-row corpus on a smaller exchange universe.
            cursor = max(START, now - (per_symbol + 96) * 3_600_000)
            local = {}
            pages = 0
            try:
                while len(local) < per_symbol and pages < 40:
                    page = ex.fetch_ohlcv(symbol, TIMEFRAME, since=cursor, limit=1000)
                    pages += 1
                    if not page:
                        break
                    accepted = []
                    for candle in page:
                        ts, op, hi, lo, cl, vol = candle
                        if ts < cursor or ts + 3_600_000 > ex.milliseconds():
                            continue
                        if not all(math.isfinite(float(x)) for x in candle):
                            continue
                        if min(op,hi,lo,cl) <= 0 or vol < 0 or hi < max(op,cl) or lo > min(op,cl):
                            continue
                        local[int(ts)] = (int(ts),float(op),float(hi),float(lo),float(cl),float(vol))
                        accepted.append(int(ts))
                    if not accepted:
                        break
                    next_cursor = max(accepted) + 3_600_000
                    if next_cursor <= cursor:
                        break
                    cursor = next_cursor
                    if len(page) < 1000:
                        break
                if not local and pages == 1:
                    # Some XT endpoint variants ignore a very old since value.
                    page = ex.fetch_ohlcv(symbol, TIMEFRAME, since=None, limit=1000)
                    for candle in page or []:
                        ts, op, hi, lo, cl, vol = candle
                        if ts + 3_600_000 > ex.milliseconds():
                            continue
                        if not all(math.isfinite(float(x)) for x in candle):
                            continue
                        if min(op,hi,lo,cl) <= 0 or vol < 0 or hi < max(op,cl) or lo > min(op,cl):
                            continue
                        local[int(ts)] = (int(ts),float(op),float(hi),float(lo),float(cl),float(vol))
            except Exception as exc:
                errors.append(f"{symbol}: {type(exc).__name__}: {exc}")
            candles = [local[k] for k in sorted(local)]
            if candles:
                normalized = normalized_symbol(market)
                rows.extend((exchange_id.upper(), normalized, TIMEFRAME, *r)
                            for r in candles[-per_symbol:])
                coverage.append({"symbol": normalized, "candles": min(len(candles),per_symbol),
                                 "pages": pages})
            print(f"{exchange_id}: {symbol}: {len(candles)} real closed candles "
                  f"({pages} pages); total={len(rows)}; market={market_index}/{len(markets)}",
                  flush=True)
            if len(rows) >= TARGET:
                break

        rows.sort(key=lambda r: (r[1], r[3]))
        if len(rows) < TARGET:
            raise RuntimeError(
                f"{exchange_id}: collected {len(rows):,} real candles; "
                f"target is {TARGET:,}; markets={len(markets)}; "
                f"covered_symbols={len(coverage)}; errors={errors[:8]}"
            )
        return rows[:TARGET]
    finally:
        ex.close()

def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    try:
        rows = collect("xt")
    except Exception as exc:
        raise SystemExit(
            "XT-only historical collection failed; no other exchange or synthetic data will be used. "
            f"{type(exc).__name__}: {exc}"
        ) from exc
    with open(OUT, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(FIELDS)
        writer.writerows(rows)
    digest = hashlib.sha256(open(OUT, "rb").read()).hexdigest()
    manifest = {
        "status": "verified_real_exchange_data",
        "rows": len(rows),
        "target_rows": TARGET,
        "minimum_required_rows": MINIMUM,
        "timeframe": TIMEFRAME,
        "source_exchanges": sorted({r[0] for r in rows}),
        "data_source_policy": "XT_USDT_M_ONLY",
        "symbols": len({r[1] for r in rows}),
        "first_timestamp_ms": min(r[3] for r in rows),
        "last_timestamp_ms": max(r[3] for r in rows),
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "sha256": digest,
        "synthetic_rows": 0
    }
    with open(META, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print(json.dumps(manifest, indent=2), flush=True)

if __name__ == "__main__":
    main()
