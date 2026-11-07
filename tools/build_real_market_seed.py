#!/usr/bin/env python3
"""Collect a verifiable real-candle training corpus from public USDT perpetual markets.

Primary source: canonical Binance USD-M public API.
Independent fallback: XT USDT-M public API (not a Binance mirror).
Only exchange-returned, closed candles are stored. No synthetic rows are generated.
"""
import csv, json, math, os, sys, time, hashlib
from datetime import datetime, timezone

TARGET = 500_000
MINIMUM = 150_000
TIMEFRAME = "1h"
OUT = "app/src/main/assets/market_seed.csv"
META = "market_seed_manifest.json"
START = 1577836800000
FIELDS = ["exchange","symbol","timeframe","timestamp","open","high","low","close","volume"]

def normalized_symbol(market):
    return (market.get("base", "") + market.get("quote", "")).upper()

def collect(exchange_id):
    import ccxt
    if exchange_id == "binance":
        ex = ccxt.binanceusdm({"enableRateLimit": True, "timeout": 30000})
    else:
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
        per_symbol = math.ceil(TARGET / len(markets))
        rows, errors = [], []
        market_history = []
        for market in markets:
            symbol = market["symbol"]
            # Request recent history first. XT does not provide 2020-era candles
            # for newly listed contracts; an ancient since= timestamp returned empty pages.
            since = max(START, ex.milliseconds() - (per_symbol + 96) * 3_600_000)
            local = []
            try:
                page = ex.fetch_ohlcv(symbol, TIMEFRAME, since=since, limit=1000)
                # Some public endpoints return the latest page only when since is omitted.
                if not page:
                    page = ex.fetch_ohlcv(symbol, TIMEFRAME, since=None, limit=1000)
                if page:
                    now = ex.milliseconds()
                    for candle in page:
                        ts, op, hi, lo, cl, vol = candle
                        if ts + 3_600_000 > now:
                            continue
                        if not all(math.isfinite(float(x)) for x in candle):
                            continue
                        if min(op,hi,lo,cl) <= 0 or vol < 0 or hi < max(op,cl) or lo > min(op,cl):
                            continue
                        local.append((int(ts),float(op),float(hi),float(lo),float(cl),float(vol)))
            except Exception as exc:
                errors.append(f"{symbol}: {type(exc).__name__}: {exc}")
            unique = {r[0]: r for r in local}
            local = [unique[k] for k in sorted(unique)]
            if local:
                normalized = normalized_symbol(market)
                rows.extend((exchange_id.upper(), normalized, TIMEFRAME, *r)
                            for r in local[-per_symbol:])
                market_history.append((market, normalized, local[0][0], len(local)))
            print(f"{exchange_id}: {symbol}: {len(local)} real closed candles; total={len(rows)}", flush=True)
            if len(rows) >= TARGET:
                break
            time.sleep(max(ex.rateLimit / 1000.0, 0.05))

        # Backfill older closed candles when newer contracts fall short of their quota.
        if len(rows) < TARGET:
            for market, normalized, earliest, first_count in market_history:
                if len(rows) >= TARGET:
                    break
                if first_count < 100:
                    continue
                try:
                    page = ex.fetch_ohlcv(market["symbol"], TIMEFRAME,
                        since=max(START, earliest - 1000 * 3_600_000), limit=1000)
                    older = []
                    now = ex.milliseconds()
                    for candle in page or []:
                        ts, op, hi, lo, cl, vol = candle
                        if ts >= earliest or ts + 3_600_000 > now:
                            continue
                        if not all(math.isfinite(float(x)) for x in candle):
                            continue
                        if min(op,hi,lo,cl) <= 0 or vol < 0 or hi < max(op,cl) or lo > min(op,cl):
                            continue
                        older.append((int(ts),float(op),float(hi),float(lo),float(cl),float(vol)))
                    older = sorted({r[0]: r for r in older}.values(), key=lambda r:r[0])
                    rows.extend((exchange_id.upper(), normalized, TIMEFRAME, *r) for r in older)
                    print(f"{exchange_id}: backfill {normalized}: +{len(older)}; total={len(rows)}", flush=True)
                except Exception as exc:
                    errors.append(f"{market['symbol']} backfill: {type(exc).__name__}: {exc}")
                time.sleep(max(ex.rateLimit / 1000.0, 0.05))

        rows.sort(key=lambda r: (r[1], r[3]))
        if len(rows) < TARGET:
            raise RuntimeError(f"{exchange_id}: collected {len(rows):,}; the full {TARGET:,} real-candle target is required; errors={errors[:5]}")
        return rows[:TARGET]
    finally:
        ex.close()

def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    rows, failures = None, []
    for exchange in ("binance", "xt"):
        try:
            rows = collect(exchange)
            break
        except Exception as exc:
            failures.append(f"{exchange}: {type(exc).__name__}: {exc}")
            print(f"Source unavailable; trying the other named exchange only: {failures[-1]}", file=sys.stderr, flush=True)
    if rows is None:
        raise SystemExit("Insufficient real exchange data; no synthetic rows were written. " + " | ".join(failures))
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
