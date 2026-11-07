#!/usr/bin/env python3
"""Collect exactly 500,000 real, closed USDT perpetual 1h candles; never synthesize rows.

Primary source: canonical Binance USD-M public API.
Fallback: XT USDT-M public API through CCXT, as a separate exchange source.
The output is an auditable CSV asset with exchange, symbol, timeframe and UTC timestamps.
"""
import csv, json, math, os, sys, time
from datetime import datetime, timezone

TARGET = 500_000
TIMEFRAME = "1h"
OUT = "app/src/main/assets/market_seed.csv"
META = "market_seed_manifest.json"
START = 1577836800000  # 2020-01-01 UTC
FIELDS = ["exchange","symbol","timeframe","timestamp","open","high","low","close","volume"]

def normalized_symbol(market):
    base, quote = market.get("base",""), market.get("quote","")
    return (base + quote).upper()

def collect(exchange_id):
    import ccxt
    if exchange_id == "binance":
        ex = ccxt.binanceusdm({"enableRateLimit": True, "timeout": 30000})
    else:
        ex = ccxt.xt({"enableRateLimit": True, "timeout": 30000,
                      "options": {"defaultType": "swap"}})
    ex.load_markets()
    markets = [m for m in ex.markets.values()
               if m.get("active") is not False and m.get("swap")
               and m.get("linear") and m.get("quote") == "USDT"
               and m.get("settle") == "USDT"]
    markets.sort(key=lambda m: m["symbol"])
    if not markets:
        raise RuntimeError(f"{exchange_id}: no active linear USDT perpetual markets")
    rows = []
    errors = []
    per_symbol = math.ceil(TARGET / len(markets))
    for market in markets:
        symbol = market["symbol"]
        since = START
        local = []
        while len(local) < per_symbol:
            page = ex.fetch_ohlcv(symbol, TIMEFRAME, since=since, limit=1000)
            if not page:
                break
            now = ex.milliseconds()
            # Exclude the still-forming candle and reject malformed / duplicate rows.
            for candle in page:
                ts, op, hi, lo, cl, vol = candle
                if ts + 3_600_000 > now:
                    continue
                if not all(math.isfinite(float(x)) for x in candle):
                    continue
                if min(op,hi,lo,cl) <= 0 or vol < 0 or hi < max(op,cl) or lo > min(op,cl):
                    continue
                local.append((int(ts),float(op),float(hi),float(lo),float(cl),float(vol)))
            next_since = int(page[-1][0]) + 3_600_000
            if next_since <= since or len(page) < 2:
                break
            since = next_since
            if len(local) >= per_symbol:
                break
            time.sleep(ex.rateLimit / 1000.0)
        # Deduplicate and preserve exchange-provided chronological ordering.
        unique = {r[0]: r for r in local}
        local = [unique[k] for k in sorted(unique)]
        if local:
            normalized = normalized_symbol(market)
            rows.extend((exchange_id.upper(), normalized, TIMEFRAME, *r) for r in local[:per_symbol])
        print(f"{exchange_id}: {symbol}: {len(local)} real candles; total={len(rows)}", flush=True)
        if len(rows) >= TARGET:
            break
    ex.close()
    if len(rows) < TARGET:
        raise RuntimeError(f"{exchange_id}: collected only {len(rows):,} of {TARGET:,} required real candles")
    rows = rows[:TARGET]
    rows.sort(key=lambda r: (r[1], r[3]))
    return rows

def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    rows = None
    failures = []
    for exchange in ("binance","xt"):
        try:
            rows = collect(exchange)
            break
        except Exception as exc:
            failures.append(f"{exchange}: {type(exc).__name__}: {exc}")
            print(f"Source unavailable; moving to independent exchange provider: {failures[-1]}", file=sys.stderr, flush=True)
    if rows is None or len(rows) != TARGET:
        raise SystemExit("Cannot meet the real-data requirement; no synthetic data will be written. " + " | ".join(failures))
    with open(OUT,"w",newline="",encoding="utf-8") as f:
        writer=csv.writer(f)
        writer.writerow(FIELDS)
        writer.writerows(rows)
    manifest={
        "status":"verified_real_exchange_data",
        "rows":len(rows),
        "target_rows":TARGET,
        "timeframe":TIMEFRAME,
        "source_exchanges":sorted({r[0] for r in rows}),
        "symbols":len({r[1] for r in rows}),
        "first_timestamp_ms":min(r[3] for r in rows),
        "last_timestamp_ms":max(r[3] for r in rows),
        "generated_at_utc":datetime.now(timezone.utc).isoformat(),
        "sha256":__import__("hashlib").sha256(open(OUT,"rb").read()).hexdigest(),
        "synthetic_rows":0
    }
    with open(META,"w",encoding="utf-8") as f: json.dump(manifest,f,indent=2)
    print(json.dumps(manifest,indent=2),flush=True)

if __name__=="__main__":
    main()
