import pytest

from tools.xt_asset_classification import classify_xt_market


@pytest.mark.parametrize("market", [
    {"base":"BTC","quote":"USDT","info":{"assetType":"CRYPTO"}},
    {"base":"ETH","quote":"USDT","info":{"asset_class":"digital_asset"}},
    {"base":"SOL","quote":"USDT","assetCategory":"token"},
])
def test_only_explicit_crypto_metadata_is_admitted(market):
    classification, reason = classify_xt_market(market)
    assert classification == "crypto"
    assert reason.startswith("explicit_")


@pytest.mark.parametrize("market", [
    {"symbol":"AAPLX/USDT:USDT","info":{"assetType":"EQUITY"}},
    {"symbol":"AUDCAD/USDT:USDT","info":{"asset_class":"FOREX"}},
    {"symbol":"NAS100/USDT:USDT","info":{"category":"INDEX"}},
    {"symbol":"COPPER/USDT:USDT","info":{"assetCategory":"COMMODITY"}},
])
def test_non_crypto_products_are_excluded(market):
    classification, _ = classify_xt_market(market)
    assert classification == "non_crypto"


@pytest.mark.parametrize("market", [
    {"base":"MYSTERY","quote":"USDT","info":{}},
    {"base":"BTC","quote":"USDT","info":{"contractType":"PERPETUAL"}},
    {"base":"BTC","quote":"USDT","info":{"assetType":"UNKNOWN"}},
])
def test_missing_or_ambiguous_metadata_fails_closed(market):
    classification, _ = classify_xt_market(market)
    assert classification == "unknown"


def test_does_not_infer_crypto_from_symbol_name():
    classification, _ = classify_xt_market({"symbol":"BTC/USDT:USDT","info":{}})
    assert classification == "unknown"
