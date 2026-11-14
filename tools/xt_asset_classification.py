"""Fail-closed XT futures asset classification.

A futures quote/settlement currency and ticker spelling never establish crypto.
Admission requires XT's public spot-currency record to mark the underlying as
crypto AND an exact identity match to the public CoinGecko crypto registry.
Ambiguous or unmatched assets are quarantined.
"""
from __future__ import annotations
import re
from collections import defaultdict


def _normalise_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", str(value or "").lower())


def build_registry_index(records):
    index = defaultdict(list)
    for item in records or []:
        if not isinstance(item, dict):
            continue
        symbol = str(item.get("symbol") or "").strip().upper()
        coin_id = str(item.get("id") or "").strip()
        name = str(item.get("name") or "").strip()
        if symbol and coin_id and name:
            index[symbol].append({"id": coin_id, "name": name})
    return dict(index)


def _xt_currency_name(currency):
    info = currency.get("info") if isinstance(currency.get("info"), dict) else {}
    return currency.get("name") or info.get("fullName") or info.get("full_name") or info.get("name") or ""


def classify_xt_market(market, spot_currencies, crypto_registry):
    """Return crypto, non_crypto, or unknown with auditable evidence."""
    if not isinstance(market, dict):
        return "unknown", "market_metadata_not_object"
    if not isinstance(spot_currencies, dict) or not spot_currencies:
        return "unknown", "XT_spot_currency_metadata_missing"
    if not isinstance(crypto_registry, dict) or not crypto_registry:
        return "unknown", "external_crypto_asset_registry_missing"
    base = str(market.get("base") or "").strip().upper()
    if not base:
        return "unknown", "futures_base_currency_missing"

    candidates = [base]
    multiplier = re.match(r"^([0-9]+)([A-Z][A-Z0-9]*)$", base)
    if multiplier:
        candidates.append(multiplier.group(2))

    unresolved, saw_xt_crypto = [], False
    for candidate in candidates:
        currency = spot_currencies.get(candidate) or spot_currencies.get(candidate.lower())
        if not isinstance(currency, dict):
            continue
        info = currency.get("info") if isinstance(currency.get("info"), dict) else {}
        currency_type = str(currency.get("type") or "").strip().lower()
        raw_type = str(info.get("type") or "").strip().upper()
        if currency_type == "other" or (raw_type and raw_type != "FT"):
            return "non_crypto", f"XT_spot_currency:{candidate}:type={currency_type or raw_type}"
        if not (currency_type == "crypto" or raw_type == "FT"):
            continue

        saw_xt_crypto = True
        matches = crypto_registry.get(candidate, [])
        if not matches:
            unresolved.append(f"no_external_crypto_identity:{candidate}")
            continue
        xt_name = _normalise_name(_xt_currency_name(currency))
        if xt_name:
            named = [coin for coin in matches if _normalise_name(coin.get("name")) == xt_name]
            if len(named) == 1:
                coin = named[0]
                return "crypto", f"XT_spot_currency:{candidate}:type=crypto;CoinGecko:{coin['id']}:{coin['name']}:exact_name_match"
            unresolved.append(f"ambiguous_or_mismatched_external_name:{candidate}:{xt_name}")
            continue
        if len(matches) == 1:
            coin = matches[0]
            return "crypto", f"XT_spot_currency:{candidate}:type=crypto;CoinGecko:{coin['id']}:{coin['name']}:unique_symbol"
        unresolved.append(f"ambiguous_external_symbol:{candidate}")

    if unresolved:
        return "unknown", ";".join(unresolved)
    if saw_xt_crypto:
        return "unknown", f"XT_crypto_marker_without_identity_match:{base}"
    return "unknown", f"base_not_confirmed_in_XT_crypto_universe:{base}"
