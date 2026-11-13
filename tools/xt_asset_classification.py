"""Classify XT futures underlyings using XT's own spot currency metadata.

CCXT maps XT's public spot currency field type='FT' to currency type='crypto';
all other explicit currency types are treated as non-crypto. A futures market
is admitted only when its underlying base currency is explicitly in that list.
Unknown assets are quarantined, never inferred from ticker text.
"""
from __future__ import annotations
import re


def classify_xt_market(market, spot_currencies):
    """Return (classification, evidence) for one XT futures market."""
    if not isinstance(market, dict):
        return "unknown", "market_metadata_not_object"
    if not isinstance(spot_currencies, dict) or not spot_currencies:
        return "unknown", "XT_spot_currency_metadata_missing"

    base = str(market.get("base") or "").upper()
    if not base:
        return "unknown", "futures_base_currency_missing"

    candidates = [base]
    multiplier = re.match(r"^([0-9]+)([A-Z][A-Z0-9]*)$", base)
    if multiplier:
        candidates.append(multiplier.group(2))

    for candidate in candidates:
        currency = spot_currencies.get(candidate) or spot_currencies.get(candidate.lower())
        if not isinstance(currency, dict):
            continue
        info = currency.get("info") if isinstance(currency.get("info"), dict) else {}
        currency_type = str(currency.get("type") or "").strip().lower()
        raw_type = str(info.get("type") or "").strip().upper()
        if currency_type == "crypto" or raw_type == "FT":
            return "crypto", f"XT_spot_currency:{candidate}:type={currency_type or raw_type}"
        if currency_type == "other" or (raw_type and raw_type != "FT"):
            return "non_crypto", f"XT_spot_currency:{candidate}:type={currency_type or raw_type}"

    return "unknown", f"base_not_confirmed_in_XT_spot_crypto_universe:{base}"
