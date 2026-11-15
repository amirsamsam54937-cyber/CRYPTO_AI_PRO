#!/usr/bin/env python3
"""Collect a verifiable real-candle training corpus from public USDT perpetual markets.

Sole source: XT USDT-M public API. No cross-exchange fallback is permitted.
Only exchange-returned, closed candles are stored. No synthetic rows are generated.
"""
import csv, json, math, os, sys, time, hashlib, urllib.request
from datetime import datetime, timezone
try:
    from tools.xt_asset_classification import classify_xt_market
except ModuleNotFoundError:
    from xt_asset_classification import classify_xt_market

TARGET = 898_920
MINIMUM = 898_920
TIMEFRAME = "1h"
OUT = "app/src/main/assets/market_seed.csv"
META = "market_seed_manifest.json"
START = 1577836800000
FIELDS = ["exchange","symbol","timeframe","timestamp","open","high","low","close","volume"]

COINGECKO_COIN_LIST = "https://api.coingecko.com/api/v3/coins/list?include_platform=false"


def fetch_crypto_asset_registry():
    """Fetch the independent public crypto identity list; fail closed on errors."""
    request = urllib.request.Request(
        COINGECKO_COIN_LIST,
        headers={"User-Agent": "ZORO-Research/1.0", "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=45) as response:
        if response.status != 200:
            raise RuntimeError(f"CoinGecko crypto registry HTTP {response.status}")
        payload = response.read()
    records = json.loads(payload)
    if not isinstance(records, list) or len(records) < 1000:
        raise RuntimeError("CoinGecko crypto registry is empty or unexpectedly small.")
    digest = hashlib.sha256(payload).hexdigest()
    return records, digest


def normalized_symbol(market):
    return (market.get("base", "") + market.get("quote", "")).upper()

def summarize_candle_timestamps(timestamps, interval_ms=3_600_000):
    """Report duplicate/order/gap integrity without treating missing hours as rows."""
    values = [int(value) for value in timestamps]
    unique = sorted(set(values))
    missing = 0
    irregular = 0
    segments = 0
    previous = None
    for current in unique:
        if previous is None:
            segments += 1
        else:
            delta = current - previous
            if delta != interval_ms:
                segments += 1
            if delta > interval_ms and delta % interval_ms == 0:
                missing += delta // interval_ms - 1
            elif delta % interval_ms != 0:
                irregular += 1
        previous = current
    return {
        "duplicate_timestamps": len(values) - len(unique),
        "out_of_order_candles": sum(1 for left, right in zip(values, values[1:]) if right <= left),
        "missing_1h_candles": int(missing),
        "irregular_gap_transitions": int(irregular),
        "contiguous_segments": int(segments),
    }


def collect(exchange_id):
    import ccxt
    if exchange_id != "xt":
        raise ValueError("Only XT USDT-M futures history is permitted.")
    ex = ccxt.xt({"enableRateLimit": True, "timeout": 30000,
                  "options": {"defaultType": "swap"}})
    try:
        ex.load_markets()
        # XT's type=crypto marker alone can include tokenized equities, FX and indices.
        # Require a second, independent identity match against a public crypto asset registry.
        spot_currencies = ex.fetch_currencies()
        if not spot_currencies:
            raise RuntimeError("XT public spot currency metadata is empty; refusing unclassified futures.")
        registry_records, crypto_asset_registry_sha256 = fetch_crypto_asset_registry()
        try:
            from tools.xt_asset_classification import build_registry_index
        except ModuleNotFoundError:
            from xt_asset_classification import build_registry_index
        crypto_registry = build_registry_index(registry_records)
        currency_payload = json.dumps(spot_currencies, sort_keys=True, separators=(",", ":"), default=str)
        currency_metadata_sha256 = hashlib.sha256(currency_payload.encode("utf-8")).hexdigest()
        xt_crypto_type_currency_count = sum(
            1 for currency in spot_currencies.values()
            if isinstance(currency, dict) and (
                str(currency.get("type") or "").lower() == "crypto" or
                str((currency.get("info") or {}).get("type") or "").upper() == "FT"
            )
        )
        perpetuals = [m for m in ex.markets.values()
                      if m.get("active") is not False and m.get("swap")
                      and m.get("linear") and m.get("quote") == "USDT"
                      and m.get("settle") == "USDT"]
        markets, excluded_non_crypto, excluded_unknown = [], [], []
        classification_by_symbol = {}
        for market in perpetuals:
            classification, reason = classify_xt_market(market, spot_currencies, crypto_registry)
            symbol = market.get("symbol", "UNKNOWN")
            if classification == "crypto":
                markets.append(market)
                classification_by_symbol[normalized_symbol(market)] = {"asset_class": "crypto", "evidence": reason}
            elif classification == "non_crypto":
                excluded_non_crypto.append({"symbol": symbol, "reason": reason})
            else:
                excluded_unknown.append({"symbol": symbol, "reason": reason})
        markets.sort(key=lambda m: m["symbol"])
        if not markets:
            raise RuntimeError(
                f"{exchange_id}: no perpetuals with explicit crypto asset-class metadata; "
                f"eligible_perpetuals={len(perpetuals)}, "
                f"non_crypto={len(excluded_non_crypto)}, unknown={len(excluded_unknown)}. "
                "Failing closed rather than guessing asset class."
            )
        # TARGET is a minimum, not an early-stop limit. Visit every eligible
        # market and retain its real closed candles up to a deterministic per-symbol
        # cap; never let alphabetically earlier markets consume the whole corpus.
        per_symbol = max(1, math.ceil((TARGET * 1.6) / len(markets)))
        rows, errors, coverage = [], [], []
        now = ex.milliseconds()
        for market_index, market in enumerate(markets, 1):
            symbol = market["symbol"]
            # Paginate each eligible market independently. Do not stop after the
            # global row threshold: doing so silently omits the tail of the universe.
            cursor = max(START, now - (per_symbol + 96) * 3_600_000)
            local = {}
            pages = 0
            invalid_candles = 0
            unclosed_candles = 0
            before_cursor_candles = 0
            duplicate_candles = 0
            out_of_order_candles = 0
            accepted_timestamps = []
            last_raw_timestamp = None
            market_error = None

            def add_candle(candle, minimum_timestamp=None):
                nonlocal invalid_candles, unclosed_candles, before_cursor_candles
                nonlocal duplicate_candles, out_of_order_candles, last_raw_timestamp
                try:
                    if len(candle) < 6:
                        invalid_candles += 1
                        return None
                    raw_ts = float(candle[0])
                    if not math.isfinite(raw_ts) or not raw_ts.is_integer():
                        invalid_candles += 1
                        return None
                    ts = int(raw_ts)
                    if last_raw_timestamp is not None and ts <= last_raw_timestamp:
                        out_of_order_candles += 1
                    last_raw_timestamp = ts
                    if minimum_timestamp is not None and ts < minimum_timestamp:
                        before_cursor_candles += 1
                        return None
                    if ts + 3_600_000 > now:
                        unclosed_candles += 1
                        return None
                    op, hi, lo, cl, vol = [float(value) for value in candle[1:6]]
                    if (not all(math.isfinite(value) for value in (op, hi, lo, cl, vol))
                            or min(op, hi, lo, cl) <= 0 or vol < 0
                            or hi < max(op, cl) or lo > min(op, cl) or hi < lo):
                        invalid_candles += 1
                        return None
                    if ts in local:
                        duplicate_candles += 1
                    local[ts] = (ts, op, hi, lo, cl, vol)
                    accepted_timestamps.append(ts)
                    return ts
                except (TypeError, ValueError, OverflowError, IndexError):
                    invalid_candles += 1
                    return None

            try:
                while len(local) < per_symbol and pages < 40:
                    page = ex.fetch_ohlcv(symbol, TIMEFRAME, since=cursor, limit=1000)
                    pages += 1
                    if not page:
                        break
                    accepted = []
                    for candle in page:
                        accepted_ts = add_candle(candle, minimum_timestamp=cursor)
                        if accepted_ts is not None:
                            accepted.append(accepted_ts)
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
                    # Retry the latest page while retaining the same validation rules.
                    page = ex.fetch_ohlcv(symbol, TIMEFRAME, since=None, limit=1000)
                    pages += 1
                    last_raw_timestamp = None
                    for candle in page or []:
                        add_candle(candle)
            except Exception as exc:
                market_error = f"{type(exc).__name__}: {exc}"
                errors.append(f"{symbol}: {market_error}")

            candles = [local[key] for key in sorted(local)]
            selected_candles = candles[-per_symbol:]
            normalized = normalized_symbol(market)
            rows.extend((exchange_id.upper(), normalized, TIMEFRAME, *item)
                        for item in selected_candles)
            integrity = summarize_candle_timestamps([item[0] for item in selected_candles])
            coverage.append({
                "symbol": normalized,
                "valid_closed_candles_available": len(candles),
                "candles_selected": len(selected_candles),
                "pages": pages,
                "first_timestamp_ms": selected_candles[0][0] if selected_candles else None,
                "last_timestamp_ms": selected_candles[-1][0] if selected_candles else None,
                "duplicate_timestamps": duplicate_candles,
                "out_of_order_candles": out_of_order_candles,
                "invalid_candles": invalid_candles,
                "unclosed_candles": unclosed_candles,
                "before_cursor_candles": before_cursor_candles,
                "missing_1h_candles": integrity["missing_1h_candles"],
                "irregular_gap_transitions": integrity["irregular_gap_transitions"],
                "contiguous_segments": integrity["contiguous_segments"],
                "error": market_error,
            })
            print(f"{exchange_id}: {symbol}: {len(selected_candles)} selected from "
                  f"{len(candles)} valid closed candles ({pages} pages); "
                  f"total={len(rows)}; market={market_index}/{len(markets)}",
                  flush=True)

        rows.sort(key=lambda row: (row[1], row[3]))
        coverage_summary = {
            "eligible_perpetuals": len(markets),
            "symbols_with_data": sum(1 for item in coverage if item["valid_closed_candles_available"] > 0),
            "symbols_without_data": sum(1 for item in coverage if item["valid_closed_candles_available"] == 0),
            "symbols_with_api_errors": sum(1 for item in coverage if item["error"] is not None),
            "valid_closed_candles_available": sum(item["valid_closed_candles_available"] for item in coverage),
            "selected_candles": len(rows),
            "duplicate_timestamps": sum(item["duplicate_timestamps"] for item in coverage),
            "out_of_order_candles": sum(item["out_of_order_candles"] for item in coverage),
            "invalid_candles": sum(item["invalid_candles"] for item in coverage),
            "unclosed_candles": sum(item["unclosed_candles"] for item in coverage),
            "before_cursor_candles": sum(item["before_cursor_candles"] for item in coverage),
            "missing_1h_candles": sum(item["missing_1h_candles"] for item in coverage),
            "irregular_gap_transitions": sum(item["irregular_gap_transitions"] for item in coverage),
            "contiguous_segments": sum(item["contiguous_segments"] for item in coverage),
        }
        if len(rows) < TARGET:
            raise RuntimeError(
                f"{exchange_id}: collected {len(rows):,} real candles; "
                f"target is {TARGET:,}; markets={len(markets)}; "
                f"covered_symbols={len(coverage)}; errors={errors[:8]}"
            )
        provenance = {
            "spot_currency_universe_count": len(spot_currencies),
            "xt_crypto_type_currency_count": xt_crypto_type_currency_count,
            "crypto_asset_registry_source": "COINGECKO_PUBLIC_COINS_LIST",
            "crypto_asset_registry_sha256": crypto_asset_registry_sha256,
            "crypto_asset_registry_symbol_count": len(crypto_registry),
            "currency_metadata_sha256": currency_metadata_sha256,
            "eligible_perpetuals": len(markets),
            "excluded_non_crypto_count": len(excluded_non_crypto),
            "excluded_unknown_count": len(excluded_unknown),
            "crypto_symbols": sorted(classification_by_symbol),
            "excluded_non_crypto_symbols": excluded_non_crypto,
            "excluded_unknown_symbols": excluded_unknown,
            "asset_classification": classification_by_symbol,
        }
        provenance["market_coverage"] = coverage
        provenance["coverage_summary"] = coverage_summary
        provenance["collection_policy"] = "ALL_ELIGIBLE_MARKETS_UP_TO_PER_SYMBOL_CAP_NO_EARLY_STOP"
        return rows, provenance
    finally:
        ex.close()

def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    try:
        rows, provenance = collect("xt")
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
        "data_source_policy": "XT_USDT_M_CRYPTO_ONLY",
        "asset_class_policy": "XT_SPOT_CRYPTO_AND_COINGECKO_IDENTITY_MATCH",
        "currency_universe_source": "XT_SPOT_PUBLIC_SUPPORTED_CURRENCIES+COINGECKO_PUBLIC_COINS_LIST",
        "crypto_only_verified": True,
        **provenance,
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
