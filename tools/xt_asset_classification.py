"""Fail-closed XT perpetual underlying classification.

XT's currency type alone is not a reliable real-world asset-class boundary:
wrapped/tokenized stocks, indices and FX products can also be represented as
crypto-like spot currencies. Require BOTH XT's explicit crypto currency marker
AND an identity match against CoinGecko's public crypto asset registry.
"""
from __future__ import annotations

import re
from collections import defaultdict


def _normalise_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", str(value or "").lower())


# XT lists some tokenized equities/ETFs as FT-like currencies. They are not
# cryptocurrency underlyings and must never enter a crypto-futures training set.
_TOKENIZED_STOCK_BASES = {
    "AAPLX", "TSLAX", "AMZNX", "GOOGLX", "GOOGX", "MSFTX", "NVDAX", "METAX",
    "MSTRX", "COINX", "HOODX", "NFLXX", "PLTRX", "ORCLX", "BABAX", "JPMX",
    "BRKX", "WMTX", "DISX", "INTCX", "AVGOX", "AMDX", "QQQX", "SPYX",
    "IWMX", "DIAX", "GLDX", "SLVX", "TLTX", "ARKKX", "COSTX", "CRMX"
}
_NON_CRYPTO_REGISTRY_MARKERS = (
    "xstock", "tokenizedstock", "tokenizedequity", "stocktoken",
    "equitytoken", "forex", "etf", "index-token"
)


def build_registry_index(records):
    """Index CoinGecko coins by symbol, preserving ambiguity instead of guessing.

    Tokenized securities and other non-crypto underlyings are deliberately
    excluded even if an aggregator lists them in its broad coin registry.
    """
    index = defaultdict(list)
    for item in records or []:
        if not isinstance(item, dict):
            continue
        symbol = str(item.get("symbol") or "").strip().upper()
        coin_id = str(item.get("id") or "").strip()
        name = str(item.get("name") or "").strip()
        identity = _normalise_name(coin_id + " " + name)
        if any(marker in identity for marker in _NON_CRYPTO_REGISTRY_MARKERS):
            continue
        if symbol and coin_id and name:
            index[symbol].append({"id": coin_id, "name": name})
    return dict(index)


def _xt_currency_name(currency):
    info = currency.get("info") if isinstance(currency.get("info"), dict) else {}
    return (
        currency.get("name")
        or info.get("fullName")
        or info.get("full_name")
        or info.get("name")
        or ""
    )


def classify_xt_market(market, spot_currencies, crypto_registry):
    """Return (classification, evidence) for one XT futures market.

    No ticker-only inference is permitted. Unknown, ambiguous, and mismatched
    symbols are quarantined instead of being silently admitted.
    """
    if not isinstance(market, dict):
        return "unknown", "market_metadata_not_object"
    if not isinstance(spot_currencies, dict) or not spot_currencies:
        return "unknown", "XT_spot_currency_metadata_missing"
    if not isinstance(crypto_registry, dict) or not crypto_registry:
        return "unknown", "external_crypto_asset_registry_missing"

    base = str(market.get("base") or "").upper()
    if not base:
        return "unknown", "futures_base_currency_missing"
    if base in _TOKENIZED_STOCK_BASES:
        return "non_crypto", f"tokenized_security_underlying:{base}"

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

        if currency_type == "other" or (raw_type and raw_type != "FT"):
            return "non_crypto", f"XT_spot_currency:{candidate}:type={currency_type or raw_type}"
        if not (currency_type == "crypto" or raw_type == "FT"):
            continue

        registry_matches = crypto_registry.get(candidate, [])
        if not registry_matches:
            return "non_crypto", f"not_in_external_crypto_registry:{candidate}"
        xt_name = _normalise_name(_xt_currency_name(currency))
        if xt_name:
            named = [coin for coin in registry_matches if _normalise_name(coin.get("name")) == xt_name]
            if len(named) == 1:
                coin = named[0]
                return "crypto", (
                    f"XT_spot_currency:{candidate}:type=crypto;"
                    f"CoinGecko:{coin['id']}:{coin['name']}:exact_name_match"
                )
            if len(named) > 1:
                return "unknown", f"ambiguous_external_crypto_name:{candidate}:{xt_name}"
            return "unknown", f"XT_name_does_not_match_external_crypto_registry:{candidate}:{xt_name}"

        if len(registry_matches) == 1:
            coin = registry_matches[0]
            return "crypto", (
                f"XT_spot_currency:{candidate}:type=crypto;"
                f"CoinGecko:{coin['id']}:{coin['name']}:unique_symbol"
            )
        return "unknown", f"ambiguous_external_crypto_symbol:{candidate}"

    return "unknown", f"base_not_confirmed_in_XT_crypto_and_external_registry:{base}"
