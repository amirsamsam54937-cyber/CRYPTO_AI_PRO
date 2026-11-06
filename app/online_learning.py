"""Small, explainable online logistic learner for post-trade research.

The learner only updates from explicitly resolved paper-trade outcomes. It does not
predict returns from future candles, place orders, or change live execution settings.
SQLite persistence makes updates survive process restarts.
"""
from __future__ import annotations

import json
import math
import os
import sqlite3
from pathlib import Path
from typing import Any

FEATURES = ("trend_score", "rsi_centered", "macd_direction", "adx_scaled",
            "volume_scaled", "bollinger_centered")
DEFAULT_WEIGHTS = (0.35, 0.15, 0.15, 0.10, 0.10, 0.15)


def feature_vector(metrics: dict[str, Any]) -> list[float]:
    """Convert technical diagnostics to bounded, comparable model inputs."""
    score = float(metrics.get("score", 0.0))
    rsi = float(metrics.get("rsi", 50.0))
    macd_hist = float(metrics.get("macd_histogram", 0.0))
    adx = float(metrics.get("adx", 0.0))
    volume = float(metrics.get("volume_ratio", 1.0))
    bb = float(metrics.get("bb_position", 0.5))
    values = [
        max(-1.0, min(1.0, score / 5.0)),
        max(-1.0, min(1.0, (rsi - 50.0) / 50.0)),
        1.0 if macd_hist > 0 else -1.0 if macd_hist < 0 else 0.0,
        max(0.0, min(1.0, adx / 50.0)),
        max(0.0, min(1.0, volume / 3.0)),
        max(-1.0, min(1.0, (bb - 0.5) * 2.0)),
    ]
    if not all(math.isfinite(v) for v in values):
        raise ValueError("features must be finite")
    return values


class OnlineLearner:
    def __init__(self, db_path: str | None = None, learning_rate: float = 0.03):
        configured = db_path or os.getenv("WOLF_LEARNING_DB", "data/wolf_learning.sqlite3")
        self.db_path = str(configured)
        self.learning_rate = max(0.001, min(float(learning_rate), 0.1))
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self._init_db()

    def _connect(self):
        connection = sqlite3.connect(self.db_path, timeout=10)
        connection.row_factory = sqlite3.Row
        return connection

    def _init_db(self):
        with self._connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS learner_state (
                id INTEGER PRIMARY KEY CHECK(id=1),
                weights TEXT NOT NULL,
                bias REAL NOT NULL,
                updates INTEGER NOT NULL
            )""")
            db.execute("""CREATE TABLE IF NOT EXISTS learner_feedback (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                direction TEXT NOT NULL,
                outcome INTEGER NOT NULL,
                features TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )""")
            db.execute(
                "INSERT OR IGNORE INTO learner_state(id,weights,bias,updates) VALUES(1,?,?,0)",
                (json.dumps(DEFAULT_WEIGHTS), 0.0),
            )

    @staticmethod
    def _sigmoid(value: float) -> float:
        value = max(-30.0, min(30.0, value))
        return 1.0 / (1.0 + math.exp(-value))

    def predict(self, metrics: dict[str, Any]) -> dict[str, Any]:
        x = feature_vector(metrics)
        with self._connect() as db:
            state = db.execute("SELECT weights,bias,updates FROM learner_state WHERE id=1").fetchone()
        weights = json.loads(state["weights"])
        probability = self._sigmoid(float(state["bias"]) + sum(w * v for w, v in zip(weights, x)))
        return {
            "win_probability_estimate": round(probability, 4),
            "updates": int(state["updates"]),
            "features": dict(zip(FEATURES, x)),
            "model": "online-logistic-v1",
            "calibrated": int(state["updates"]) >= 100,
            "notice": "Research estimate only; not a guarantee and not a standalone entry signal.",
        }

    def record_resolved_trade(self, symbol: str, direction: str,
                              metrics: dict[str, Any], outcome: int) -> dict[str, Any]:
        """Train once per resolved paper trade: outcome 1=win, 0=loss."""
        if outcome not in (0, 1):
            raise ValueError("outcome must be 1 for win or 0 for loss")
        symbol = symbol.strip().upper()
        direction = direction.strip().upper()
        direction = {"LONG_BIAS": "LONG", "SHORT_BIAS": "SHORT"}.get(direction, direction)
        if not symbol or direction not in ("LONG", "SHORT"):
            raise ValueError("symbol and LONG/SHORT direction are required")
        x = feature_vector(metrics)
        with self._connect() as db:
            state = db.execute("SELECT weights,bias,updates FROM learner_state WHERE id=1").fetchone()
            weights = json.loads(state["weights"])
            bias = float(state["bias"])
            probability = self._sigmoid(bias + sum(w * v for w, v in zip(weights, x)))
            error = float(outcome) - probability
            weights = [max(-3.0, min(3.0, w + self.learning_rate * error * v))
                       for w, v in zip(weights, x)]
            bias = max(-3.0, min(3.0, bias + self.learning_rate * error))
            updates = int(state["updates"]) + 1
            db.execute("UPDATE learner_state SET weights=?,bias=?,updates=? WHERE id=1",
                       (json.dumps(weights), bias, updates))
            db.execute(
                "INSERT INTO learner_feedback(symbol,direction,outcome,features) VALUES(?,?,?,?)",
                (symbol, direction, outcome, json.dumps(x)),
            )
        return {"accepted": True, "updates": updates, "previous_probability": round(probability, 4)}

    def status(self) -> dict[str, Any]:
        with self._connect() as db:
            state = db.execute("SELECT weights,bias,updates FROM learner_state WHERE id=1").fetchone()
            total = db.execute("SELECT COUNT(*) AS n FROM learner_feedback").fetchone()["n"]
        updates = int(state["updates"])
        return {
            "model": "online-logistic-v1",
            "updates": updates,
            "resolved_paper_trades": int(total),
            "calibrated": updates >= 100,
            "weights": dict(zip(FEATURES, json.loads(state["weights"]))),
            "learning_rate": self.learning_rate,
            "mode": "paper-feedback-only",
        }
