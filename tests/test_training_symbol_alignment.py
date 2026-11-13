import numpy as np

from tools.train_wolf_ensemble import repeat_symbol_labels


def test_repeated_symbol_labels_preserve_full_instrument_identifier():
    labels = repeat_symbol_labels("BTC/USDT:USDT", 4)
    assert labels.dtype == object
    assert labels.tolist() == ["BTC/USDT:USDT"] * 4
    assert np.unique(labels).tolist() == ["BTC/USDT:USDT"]


def test_repeated_symbol_labels_support_empty_arrays():
    assert repeat_symbol_labels("ETH/USDT:USDT", 0).size == 0
