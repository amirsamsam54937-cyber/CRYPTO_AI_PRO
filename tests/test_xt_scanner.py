from app.xt_scanner import _frame
import pytest


def test_frame_drops_forming_candle():
    rows = [[i * 900000, 100, 102, 99, 101, 10] for i in range(60)]
    frame = _frame(rows)
    assert len(frame) == 59
    assert frame.iloc[-1]["close"] == 101


def test_frame_rejects_insufficient_candles():
    with pytest.raises(ValueError):
        _frame([[i, 1, 1, 1, 1, 1] for i in range(20)])
