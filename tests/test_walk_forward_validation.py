import numpy as np
import pytest

from tools.walk_forward_validation import deterministic_symbol_holdout, expanding_walk_forward_masks


def test_walk_forward_folds_are_ordered_purged_and_timestamp_grouped():
    times = np.repeat(np.arange(200, dtype=np.int64), 3) * 3_600_000
    folds = expanding_walk_forward_masks(times, n_splits=5, min_train_fraction=0.5)
    assert len(folds) == 5
    previous_test_end = -1
    for train, test, purged, cutoff in folds:
        assert train.any() and test.any() and purged > 0
        assert np.all(times[train] < cutoff - 6 * 3_600_000)
        assert np.all(times[test] >= cutoff)
        assert not np.any(train & test)
        assert int(times[test].min()) > previous_test_end
        previous_test_end = int(times[test].max())
        for timestamp in np.unique(times):
            rows = times == timestamp
            assert len(np.unique(train[rows])) == 1
            assert len(np.unique(test[rows])) == 1


def test_walk_forward_rejects_invalid_fold_requests():
    with pytest.raises(ValueError):
        expanding_walk_forward_masks(np.arange(20), n_splits=1)
    with pytest.raises(ValueError):
        expanding_walk_forward_masks(np.arange(20), min_train_fraction=0.95)


def test_symbol_holdout_is_deterministic_and_disjoint():
    symbols = np.repeat(np.array(["BTC", "ETH", "SOL", "XRP", "DOGE"]), 4)
    train, test = deterministic_symbol_holdout(symbols, holdout_fraction=0.2)
    assert train.any() and test.any() and not np.any(train & test)
    assert len(set(symbols[test])) == 1
    train2, test2 = deterministic_symbol_holdout(symbols, holdout_fraction=0.2)
    assert np.array_equal(train, train2) and np.array_equal(test, test2)


def test_hash_ranked_symbol_holdout_keeps_full_tickers():
    symbols=np.repeat(np.array(["BTCUSDT","ETHUSDT","SOLUSDT","XRPUSDT","DOGEUSDT"],dtype=object),3)
    train,test=deterministic_symbol_holdout(symbols,holdout_fraction=0.2)
    assert set(symbols[train]).isdisjoint(set(symbols[test]))
    assert len(set(symbols[test]))==1
    assert all(len(s)>4 and s.endswith("USDT") for s in symbols[test])
