import numpy as np
import pandas as pd

from app.multi_agent import analyze_consensus


def make_frame(trend=0.001, n=320):
    close = 100 * np.exp(np.arange(n) * trend)
    wobble = np.sin(np.arange(n) / 5) * 0.25
    close = close + wobble
    open_ = np.r_[close[0], close[:-1]]
    high = np.maximum(open_, close) + 0.5
    low = np.minimum(open_, close) - 0.5
    volume = 1000 + (np.arange(n) % 17) * 8
    return pd.DataFrame({"open": open_, "high": high, "low": low,
                         "close": close, "volume": volume})


def test_consensus_returns_ten_explainable_votes_and_risk_plan():
    result = analyze_consensus(make_frame())
    assert len(result["votes"]) == 10
    assert result["direction"] in {"LONG", "SHORT", "WAIT"}
    assert result["is_probability"] is False
    assert result["strength_index"] in range(0, 96)
    assert result["risk_plan"]["entry_reference"] > 0
    if result["direction"] == "LONG":
        assert result["risk_plan"]["stop_loss"] < result["risk_plan"]["entry_reference"]
        assert len(result["risk_plan"]["take_profits"]) == 3
    elif result["direction"] == "SHORT":
        assert result["risk_plan"]["stop_loss"] > result["risk_plan"]["entry_reference"]
        assert len(result["risk_plan"]["take_profits"]) == 3
    else:
        assert result["risk_plan"]["stop_loss"] is None


def test_consensus_rejects_short_or_invalid_data():
    try:
        analyze_consensus(make_frame(n=30))
    except ValueError:
        pass
    else:
        raise AssertionError("Expected validation failure for too few candles")
