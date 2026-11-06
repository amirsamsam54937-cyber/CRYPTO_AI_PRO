from app.exchanges import xt_ccxt


class FakeXT:
    def __init__(self):
        self.loaded = False

    def load_markets(self):
        self.loaded = True
        return {"BTC/USDT:USDT": {"swap": True}}

    def fetch_balance(self):
        return {"USDT": {"free": 0, "used": 0, "total": 0}}

    def fetch_positions(self):
        return [
            {"symbol": "BTC/USDT:USDT", "contracts": 0},
            {"symbol": "ETH/USDT:USDT", "contracts": 2, "side": "long",
             "entryPrice": 3000, "markPrice": 3010, "unrealizedPnl": 20,
             "leverage": 3},
        ]

    def fetch_open_orders(self):
        return [{"id": "123", "symbol": "BTC/USDT:USDT", "side": "buy",
                 "type": "limit", "status": "open", "amount": 1,
                 "filled": 0, "remaining": 1, "price": 60000}]


def test_xt_ccxt_requires_environment_credentials(monkeypatch):
    monkeypatch.delenv("XT_API_KEY", raising=False)
    monkeypatch.delenv("XT_API_SECRET", raising=False)
    try:
        xt_ccxt.build_xt_exchange()
    except xt_ccxt.XTCCXTError as exc:
        assert "XT_API_KEY" in str(exc)
    else:
        raise AssertionError("Missing credentials must fail closed")


def test_xt_ccxt_account_snapshot_handles_empty_wallet(monkeypatch):
    monkeypatch.setenv("XT_API_KEY", "test-key")
    monkeypatch.setenv("XT_API_SECRET", "test-secret")
    fake = FakeXT()
    monkeypatch.setattr(xt_ccxt.ccxt, "xt", lambda config: fake)

    snapshot = xt_ccxt.xt_account_snapshot()

    assert snapshot["connected"] is True
    assert snapshot["markets_loaded"] == 1
    assert snapshot["balance"]["currency"] == "USDT"
    assert snapshot["balance"]["total"] == 0
    assert snapshot["balance"]["is_empty"] is True
    assert snapshot["positions"]["count"] == 1
    assert snapshot["positions"]["items"][0]["symbol"] == "ETH/USDT:USDT"
    assert snapshot["open_orders"]["count"] == 1
    assert snapshot["mode"] == "authenticated-read-only"
