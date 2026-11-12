import numpy as np
import pandas as pd
import pytest

from app.strategy_lab import discover_strategy


def test_strategy_lab_requires_sufficient_chronological_history():
    frame = pd.DataFrame({
        "open": np.ones(300), "high": np.ones(300) * 1.01,
        "low": np.ones(300) * 0.99, "close": np.ones(300),
        "volume": np.ones(300),
    })
    with pytest.raises(ValueError, match="600"):
        discover_strategy(frame)


def test_strategy_lab_rejects_missing_ohlcv_columns():
    frame = pd.DataFrame({"close": np.ones(700)})
    with pytest.raises(ValueError, match="OHLCV"):
        discover_strategy(frame)
