import sys
import types
import pytest
from tools import build_real_market_seed as collector


class FakeXT:
    def __init__(self, config):
        self.markets = {
            "BTC/USDT:USDT": {"active": True, "swap": True, "linear": True, "quote": "USDT", "settle": "USDT", "base": "BTC", "symbol": "BTC/USDT:USDT"},
            "AAPLX/USDT:USDT": {"active": True, "swap": True, "linear": True, "quote": "USDT", "settle": "USDT", "base": "AAPLX", "symbol": "AAPLX/USDT:USDT"},
            "MYSTERY/USDT:USDT": {"active": True, "swap": True, "linear": True, "quote": "USDT", "settle": "USDT", "base": "MYSTERY", "symbol": "MYSTERY/USDT:USDT"},
        }
    def load_markets(self): return self.markets
    def fetch_currencies(self):
        return {
            "BTC": {"type": "crypto", "name": "Bitcoin", "info": {"type": "FT", "fullName": "Bitcoin"}},
            "AAPLX": {"type": "other", "name": "Apple Tokenized Stock", "info": {"type": "OT"}},
        }
    def milliseconds(self): return 1_800_000_000_000
    def fetch_ohlcv(self, symbol, timeframe, since=None, limit=1000):
        start=self.milliseconds()-10*3_600_000
        return [[start+i*3_600_000,100.0,102.0,99.0,101.0,12.0] for i in range(4)]
    def close(self): pass


def test_collector_returns_auditable_crypto_provenance(monkeypatch):
    monkeypatch.setitem(sys.modules,"ccxt",types.SimpleNamespace(xt=FakeXT))
    monkeypatch.setattr(collector,"TARGET",2)
    monkeypatch.setattr(collector,"fetch_crypto_registry",
        lambda: ({"BTC":[{"id":"bitcoin","name":"Bitcoin"}]}, "a"*64, 1))
    rows, provenance=collector.collect("xt")
    assert len(rows)==2 and all(row[1]=="BTCUSDT" for row in rows)
    assert provenance["candidate_perpetuals_count"]==3
    assert provenance["accepted_crypto_perpetuals"]==1
    assert provenance["rejected_non_crypto_count"]==1
    assert provenance["unknown_perpetuals_count"]==1
    assert provenance["crypto_symbols"]==["BTCUSDT"]


def test_collector_refuses_non_xt_source():
    with pytest.raises(ValueError): collector.collect("binance")
