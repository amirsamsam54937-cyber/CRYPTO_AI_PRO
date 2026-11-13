import pytest

from tools.xt_asset_classification import classify_xt_market


@pytest.fixture
def xt_currencies():
    return {
        "BTC": {"type": "crypto", "info": {"type": "FT", "fullName": "Bitcoin"}},
        "ETH": {"type": "crypto", "info": {"type": "FT", "fullName": "Ethereum"}},
        "PEPE": {"type": "crypto", "info": {"type": "FT", "fullName": "Pepe"}},
        "AAPLX": {"type": "other", "info": {"type": "OT", "fullName": "Apple-linked"}},
        "AUD": {"type": "other", "info": {"type": "OT", "fullName": "Australian Dollar"}},
        "NAS100": {"type": "other", "info": {"type": "OT", "fullName": "Nasdaq 100"}},
    }


@pytest.mark.parametrize("base", ["BTC", "ETH"])
def test_xt_explicit_crypto_currency_is_admitted(base, xt_currencies):
    result, reason = classify_xt_market({"base": base}, xt_currencies)
    assert result == "crypto"
    assert "XT_spot_currency" in reason


@pytest.mark.parametrize("base", ["AAPLX", "AUD", "NAS100"])
def test_xt_explicit_non_crypto_currency_is_rejected(base, xt_currencies):
    result, _ = classify_xt_market({"base": base}, xt_currencies)
    assert result == "non_crypto"


def test_numeric_contract_multiplier_resolves_underlying_crypto(xt_currencies):
    result, reason = classify_xt_market({"base": "1000PEPE"}, xt_currencies)
    assert result == "crypto"
    assert "PEPE" in reason


def test_unknown_symbol_is_quarantined_not_guessed(xt_currencies):
    assert classify_xt_market({"base": "MYSTERY"}, xt_currencies)[0] == "unknown"
    assert classify_xt_market({"base": "BTC"}, {})[0] == "unknown"


def test_symbol_name_alone_never_proves_crypto(xt_currencies):
    assert classify_xt_market({"base": "BTC"}, {})[0] == "unknown"
