import sys
import types

import pytest

from tools import build_real_market_seed as collector


class FakeXT:
    def __init__(self, config):
        self.markets = {
            "BTC/USDT:USDT": {
                "active": True, "swap": True, "linear": True,
                "quote": "USDT", "settle": "USDT", "base": "BTC",
                "symbol": "BTC/USDT:USDT",
            }
        }

    def load_markets(self):
        return self.markets

    def fetch_currencies(self):
        return {"BTC": {"type": "crypto", "name": "Bitcoin", "info": {"type": "FT", "fullName": "Bitcoin"}},
            "AAPLX": {"type": "crypto", "name": "Apple-linked", "info": {"type": "FT", "fullName": "Apple-linked"}}}

    def milliseconds(self):
        return 1_800_000_000_000

    def fetch_ohlcv(self, symbol, timeframe, since=None, limit=1000):
        start = self.milliseconds() - 10 * 3_600_000
        return [
            [start + i * 3_600_000, 100.0, 102.0, 99.0, 101.0, 12.0]
            for i in range(4)
        ]

    def close(self):
        pass


def test_collector_returns_auditable_crypto_provenance(monkeypatch):
    fake_ccxt = types.SimpleNamespace(xt=FakeXT)
    monkeypatch.setitem(sys.modules, "ccxt", fake_ccxt)
    monkeypatch.setattr(collector, "TARGET", 2)
    monkeypatch.setattr(collector, "fetch_crypto_asset_registry", lambda: ([{"id": "bitcoin", "symbol": "btc", "name": "Bitcoin"}], "a" * 64))

    rows, provenance = collector.collect("xt")

    assert len(rows) == 2
    assert all(row[1] == "BTCUSDT" for row in rows)
    assert provenance["spot_currency_universe_count"] == 2
    assert provenance["xt_crypto_type_currency_count"] == 2
    assert provenance["crypto_asset_registry_source"] == "COINGECKO_PUBLIC_COINS_LIST"
    assert provenance["crypto_asset_registry_sha256"] == "a" * 64
    assert len(provenance["currency_metadata_sha256"]) == 64
    assert provenance["crypto_symbols"] == ["BTCUSDT"]
    assert provenance["asset_classification"]["BTCUSDT"]["asset_class"] == "crypto"
    assert "AAPLX" not in provenance["crypto_symbols"]


def test_collector_refuses_non_xt_source():
    with pytest.raises(ValueError):
        collector.collect("binance")
