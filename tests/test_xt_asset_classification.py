import pytest

from tools.xt_asset_classification import build_registry_index, classify_xt_market


@pytest.fixture
def xt_currencies():
    return {
        "BTC": {"type": "crypto", "name": "Bitcoin", "info": {"type": "FT", "fullName": "Bitcoin"}},
        "ETH": {"type": "crypto", "name": "Ethereum", "info": {"type": "FT", "fullName": "Ethereum"}},
        "PEPE": {"type": "crypto", "name": "Pepe", "info": {"type": "FT", "fullName": "Pepe"}},
        "AAPLX": {"type": "other", "name": "Apple Tokenized Stock", "info": {"type": "OT"}},
        "AUD": {"type": "other", "name": "Australian Dollar", "info": {"type": "OT"}},
        "NAS100": {"type": "other", "name": "Nasdaq 100", "info": {"type": "OT"}},
    }


@pytest.fixture
def registry():
    return build_registry_index([
        {"id": "bitcoin", "symbol": "btc", "name": "Bitcoin"},
        {"id": "ethereum", "symbol": "eth", "name": "Ethereum"},
        {"id": "pepe", "symbol": "pepe", "name": "Pepe"},
        {"id": "pepe-2", "symbol": "pepe", "name": "Pepe Token"},
    ])


@pytest.mark.parametrize("base", ["BTC", "ETH"])
def test_xt_crypto_requires_matching_public_registry_identity(base, xt_currencies, registry):
    result, reason = classify_xt_market({"base": base}, xt_currencies, registry)
    assert result == "crypto"
    assert "CoinGecko:" in reason


@pytest.mark.parametrize("base", ["AAPLX", "AUD", "NAS100"])
def test_explicit_xt_non_crypto_is_rejected_even_if_quote_is_usdt(base, xt_currencies, registry):
    assert classify_xt_market({"base": base, "quote": "USDT"}, xt_currencies, registry)[0] == "non_crypto"


def test_numeric_contract_multiplier_resolves_underlying_identity(xt_currencies, registry):
    result, reason = classify_xt_market({"base": "1000PEPE"}, xt_currencies, registry)
    assert result == "crypto" and "PEPE" in reason


def test_xt_crypto_without_exact_external_identity_is_unknown(xt_currencies):
    assert classify_xt_market({"base": "BTC"}, xt_currencies,
        {"BTC": [{"id": "wrapped-bitcoin", "name": "Wrapped Bitcoin"}]})[0] == "unknown"


def test_ambiguous_symbol_without_exact_name_is_unknown(xt_currencies, registry):
    xt_currencies["PEPE"].pop("name")
    xt_currencies["PEPE"]["info"].pop("fullName")
    assert classify_xt_market({"base": "PEPE"}, xt_currencies, registry)[0] == "unknown"


def test_missing_registry_or_xt_metadata_is_unknown(xt_currencies):
    assert classify_xt_market({"base": "BTC"}, xt_currencies, {})[0] == "unknown"
    assert classify_xt_market({"base": "BTC"}, {}, {"BTC": [{"id": "bitcoin", "name": "Bitcoin"}]})[0] == "unknown"
