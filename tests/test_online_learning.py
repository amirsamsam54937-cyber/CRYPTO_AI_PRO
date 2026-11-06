import pytest

from app.online_learning import OnlineLearner, feature_vector


def metrics():
    return {
        "score": 4, "rsi": 68, "macd_histogram": 0.7,
        "adx": 32, "volume_ratio": 1.8, "bb_position": 0.8,
    }


def test_features_are_bounded():
    values = feature_vector(metrics())
    assert len(values) == 6
    assert all(-1.0 <= value <= 1.0 for value in values)


def test_online_learning_persists_resolved_paper_outcomes(tmp_path):
    path = tmp_path / "wolf.sqlite3"
    learner = OnlineLearner(str(path))
    before = learner.predict(metrics())
    result = learner.record_resolved_trade("BTC/USDT:USDT", "LONG", metrics(), 1)
    after = OnlineLearner(str(path)).predict(metrics())
    status = learner.status()

    assert result["accepted"] is True
    assert result["updates"] == 1
    assert after["updates"] == 1
    assert after["win_probability_estimate"] > before["win_probability_estimate"]
    assert status["resolved_paper_trades"] == 1
    assert status["calibrated"] is False


def test_online_learning_rejects_invalid_feedback(tmp_path):
    learner = OnlineLearner(str(tmp_path / "wolf.sqlite3"))
    with pytest.raises(ValueError):
        learner.record_resolved_trade("BTC", "BUY", metrics(), 1)
    with pytest.raises(ValueError):
        learner.record_resolved_trade("BTC", "LONG", metrics(), 2)
