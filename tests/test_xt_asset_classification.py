import pytest

from tools.xt_asset_classification import build_registry_index, classify_xt_market


@pytest.fixture
def xt_currencies():
    return {
        "BTC": {"type": "crypto", "name": "Bitcoin", "info": {"type": "FT", "fullName": "Bitcoin"}},
        "ETH": {"type": "crypto", "name": "Ethereum", "info": {"type": "FT", "fullName": "Ethereum"}},
        "PEPE": {"type": "crypto", "name": "Pepe", "info": {"type": "FT", "fullName": "Pepe"}},
        "AAPLX": {"type": "crypto", "name": "Apple-linked", "info": {"type": "FT", "fullName": "Apple-linked"}},
        "AUD": {"type": "crypto", "name": "Australian Dollar", "info": {"type": "FT", "fullName": "Australian Dollar"}},
        "NAS100": {"type": "crypto", "name": "Nasdaq 100", "info": {"type": "FT", "fullName": "Nasdaq 100"}},
    }


@pytest.fixture
def registry():
    return build_registry_index([
        {"id": "bitcoin", "symbol": "btc", "name": "Bitcoin"},
        {"id": "ethereum", "symbol": "eth", "name": "Ethereum"},
        {"id": "pepe", "symbol": "pepe", "name": "Pepe"},
        {"id": "one-a", "symbol": "one", "name": "Harmony"},
        {"id": "one-b", "symbol": "one", "name": "Another One"},
    ])


@pytest.mark.parametrize("base", ["BTC", "ETH"])
def test_xt_crypto_currency_must_also_match_external_crypto_registry(base, xt_currencies, registry):
    result, reason = classify_xt_market({"base": base}, xt_currencies, registry)
    assert result == "crypto"
    assert "CoinGecko:" in reason


@pytest.mark.parametrize("base", ["AAPLX", "AUD", "NAS100"])
def test_xt_crypto_type_alone_does_not_admit_synthetic_tradfi_assets(base, xt_currencies, registry):
    result, reason = classify_xt_market({"base": base}, xt_currencies, registry)
    assert result == "non_crypto"
    assert "not_in_external_crypto_registry" in reason


def test_numeric_contract_multiplier_resolves_underlying_crypto(xt_currencies, registry):
    result, reason = classify_xt_market({"base": "1000PEPE"}, xt_currencies, registry)
    assert result == "crypto"
    assert "PEPE" in reason


def test_ambiguous_symbol_without_matching_xt_name_is_quarantined(xt_currencies, registry):
    currencies = dict(xt_currencies)
    currencies["ONE"] = {"type": "crypto", "name": "Unrelated One", "info": {"type": "FT"}}
    assert classify_xt_market({"base": "ONE"}, currencies, registry)[0] == "unknown"


def test_missing_external_registry_fails_closed(xt_currencies):
    assert classify_xt_market({"base": "BTC"}, xt_currencies, {})[0] == "unknown"


def test_unknown_symbol_is_quarantined_not_guessed(xt_currencies, registry):
    assert classify_xt_market({"base": "MYSTERY"}, xt_currencies, registry)[0] == "unknown"
    assert classify_xt_market({"base": "BTC"}, {}, registry)[0] == "unknown"


def test_symbol_name_alone_never_proves_crypto(xt_currencies, registry):
    assert classify_xt_market({"base": "BTC"}, xt_currencies, {})[0] == "unknown"
