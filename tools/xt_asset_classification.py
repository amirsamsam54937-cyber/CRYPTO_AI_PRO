"""Fail-closed XT instrument asset classification.

A USDT-settled linear perpetual is not sufficient evidence that its underlying
is a cryptocurrency. Only explicit exchange metadata may admit an instrument.
"""
from __future__ import annotations

CRYPTO_VALUES = {
    "CRYPTO", "CRYPTOCURRENCY", "DIGITAL ASSET", "DIGITAL_ASSET",
    "DIGITAL CURRENCY", "DIGITAL_CURRENCY", "COIN", "TOKEN",
}
NON_CRYPTO_VALUES = {
    "EQUITY", "STOCK", "ETF", "INDEX", "INDICES", "FOREX", "FX",
    "COMMODITY", "TRADFI", "TRADITIONAL FINANCE", "TRADITIONAL_FINANCE",
    "METAL", "ENERGY",
}
ASSET_CLASS_KEYS = (
    "assetType", "asset_type", "assetClass", "asset_class",
    "underlyingType", "underlying_type", "baseAssetType", "base_asset_type",
    "coinType", "coin_type", "assetCategory", "asset_category", "category",
)


def classify_xt_market(market):
    """Return (classification, reason); unknown metadata is never admitted."""
    if not isinstance(market, dict):
        return "unknown", "market_metadata_not_object"
    info = market.get("info")
    sources = [market]
    if isinstance(info, dict):
        sources.append(info)
    seen = []
    for source in sources:
        for key in ASSET_CLASS_KEYS:
            value = source.get(key)
            if value is None:
                continue
            normalized = str(value).strip().upper().replace("-", "_")
            normalized_space = normalized.replace("_", " ")
            seen.append((key, normalized))
            if normalized in CRYPTO_VALUES or normalized_space in CRYPTO_VALUES:
                return "crypto", f"explicit_{key}={normalized}"
            if normalized in NON_CRYPTO_VALUES or normalized_space in NON_CRYPTO_VALUES:
                return "non_crypto", f"explicit_{key}={normalized}"
    if seen:
        return "unknown", "unrecognized_asset_class_metadata:" + ",".join(
            f"{key}={value}" for key, value in seen
        )
    return "unknown", "missing_explicit_asset_class_metadata"
