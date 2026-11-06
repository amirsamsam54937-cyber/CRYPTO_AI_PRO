import pytest

from app.exchanges.xt_futures import XTCredentials, XTFuturesClient, XTAPIError


class FakeResponse:
    def __init__(self, data):
        self._data = data
    def raise_for_status(self):
        return None
    def json(self):
        return self._data


class FakeSession:
    def __init__(self):
        self.posts = []
        self.gets = []
    def get(self, url, **kwargs):
        self.gets.append((url, kwargs))
        return FakeResponse({"returnCode": 0, "result": {
            "symbol": "btc_usdt", "contractSize": 0.001,
            "quantityPrecision": 0, "minQty": 1, "tradeSwitch": True,
            "isOpenApi": True
        }})
    def post(self, url, **kwargs):
        self.posts.append((url, kwargs))
        return FakeResponse({"returnCode": 0, "result": {"orderId": "123"}})


def client(session):
    return XTFuturesClient(
        credentials=XTCredentials("test-key", "test-secret"),
        session=session,
    )


def test_contract_detail_uses_public_symbol_endpoint():
    session = FakeSession()
    result = client(session).contract_detail("BTC_USDT")
    assert result["contractSize"] == 0.001
    assert session.gets[0][1]["params"] == {"symbol": "btc_usdt"}


def test_live_order_is_disabled_by_default(monkeypatch):
    monkeypatch.delenv("LIVE_TRADING_ENABLED", raising=False)
    monkeypatch.delenv("LIVE_TRADING_ACK", raising=False)
    session = FakeSession()
    with pytest.raises(XTAPIError, match="disabled"):
        client(session).create_protected_market_order(
            symbol="btc_usdt", side="BUY", quantity_contracts=1,
            stop_loss=60000, take_profit=70000, position_side="LONG",
            client_order_id="unit-test-order",
        )
    assert session.posts == []


def test_live_order_requires_both_gates(monkeypatch):
    monkeypatch.setenv("LIVE_TRADING_ENABLED", "true")
    monkeypatch.setenv("LIVE_TRADING_ACK", "wrong")
    session = FakeSession()
    with pytest.raises(XTAPIError, match="acknowledgement"):
        client(session).create_protected_market_order(
            symbol="btc_usdt", side="BUY", quantity_contracts=1,
            stop_loss=60000, take_profit=70000, position_side="LONG",
            client_order_id="unit-test-order",
        )
    assert session.posts == []


def test_order_quantity_must_be_whole_contracts(monkeypatch):
    monkeypatch.setenv("LIVE_TRADING_ENABLED", "true")
    monkeypatch.setenv("LIVE_TRADING_ACK", "ENABLE_XT_LIVE_ORDERS")
    session = FakeSession()
    with pytest.raises(ValueError, match="whole number"):
        client(session).create_protected_market_order(
            symbol="btc_usdt", side="BUY", quantity_contracts=1.5,
            stop_loss=60000, take_profit=70000, position_side="LONG",
            client_order_id="unit-test-order",
        )
    assert session.posts == []
