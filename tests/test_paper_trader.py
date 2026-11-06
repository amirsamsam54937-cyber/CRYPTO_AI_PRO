import pytest
from app.engine.paper_trader import PaperBroker, Side


def test_long_stop_and_take_profit_are_simulated():
    broker = PaperBroker(1000)
    p = broker.open_position("btc_usdt", Side.LONG, 100, 95, reward_risk=2)
    assert p.take_profit_1 == 105
    broker.on_price(p.position_id, 105)
    assert p.remaining_fraction < 1
    assert p.stop == 100
    broker.on_price(p.position_id, 100)
    assert p.status == "CLOSED"


def test_short_stop():
    broker = PaperBroker(1000)
    p = broker.open_position("eth_usdt", "SHORT", 100, 105)
    broker.on_price(p.position_id, 105)
    assert p.status == "CLOSED"
    assert p.exit_reason == "STOP_LOSS"


def test_duplicate_symbol_and_invalid_stop_rejected():
    broker = PaperBroker(1000)
    broker.open_position("btc_usdt", "LONG", 100, 95)
    with pytest.raises(ValueError):
        broker.open_position("btc_usdt", "LONG", 100, 95)
    with pytest.raises(ValueError):
        broker.open_position("eth_usdt", "LONG", 100, 101)


def test_max_position_count():
    broker = PaperBroker(1000, max_positions=1)
    broker.open_position("btc_usdt", "LONG", 100, 95)
    with pytest.raises(ValueError):
        broker.open_position("eth_usdt", "SHORT", 100, 105)
