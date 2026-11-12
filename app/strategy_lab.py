"""Automatic strategy discovery with chronological train/validation/holdout gates.

The lab evolves interpretable rule parameters, never trains on the final holdout,
and refuses to publish a strategy unless out-of-sample evidence is adequate.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from itertools import product
import random
from typing import Any

import pandas as pd

from app.backtest import run_backtest


@dataclass(frozen=True)
class StrategyGenome:
    min_adx: float
    min_volume_ratio: float
    min_confidence: float
    min_abs_score: int
    atr_multiple: float
    reward_risk: float
    allowed_regime: str


def _metric(result: dict[str, Any], key: str) -> float:
    value = result.get(key, 0.0)
    if value == "infinity":
        return 10.0
    if value is None:
        return 0.0
    return float(value)


def _fitness(result: dict[str, Any], min_trades: int) -> float:
    trades = int(result.get("trade_count", 0))
    pf = min(_metric(result, "profit_factor"), 5.0)
    ret = _metric(result, "return_percent")
    dd = _metric(result, "max_drawdown_percent")
    if trades < min_trades or ret <= 0 or pf <= 1.0 or dd > 20.0:
        return -1_000.0 + min(ret, 0.0) - dd
    # Reward net growth and profit factor; penalize drawdown and tiny samples.
    return ret + 4.0 * (pf - 1.0) - 1.5 * dd + min(trades, 100) * 0.02


def _evaluate(frame: pd.DataFrame, genome: StrategyGenome, warmup: int) -> dict:
    return run_backtest(
        frame,
        atr_multiple=genome.atr_multiple,
        reward_risk=genome.reward_risk,
        allowed_regime=genome.allowed_regime,
        min_adx=genome.min_adx,
        min_volume_ratio=genome.min_volume_ratio,
        min_confidence=genome.min_confidence,
        min_abs_score=genome.min_abs_score,
        warmup=warmup,
    )


def discover_strategy(
    frame: pd.DataFrame,
    *,
    warmup: int = 100,
    min_train_trades: int = 12,
    min_validation_trades: int = 8,
    min_holdout_trades: int = 8,
    max_candidates: int = 72,
) -> dict:
    """Search rule combinations; select on validation; report holdout exactly once.

    Returns accepted=False when the candidate fails independent validation.
    The caller must not enable live signals from a rejected result.
    """
    if not isinstance(frame, pd.DataFrame):
        raise TypeError("frame must be a pandas DataFrame")
    required = {"open", "high", "low", "close", "volume"}
    if not required.issubset(frame.columns):
        raise ValueError("OHLCV columns are required")
    if len(frame) < max(3 * (warmup + 80), 600):
        raise ValueError("At least 600 chronological candles are required")

    data = frame.copy().reset_index(drop=True)
    a, b = int(len(data) * 0.60), int(len(data) * 0.80)
    train, validation, holdout = data.iloc[:a], data.iloc[a:b], data.iloc[b:]
    if min(len(train), len(validation), len(holdout)) <= warmup + 2:
        raise ValueError("Each chronological split must exceed warmup")

    space = product(
        (18.0, 22.0, 26.0, 30.0),
        (0.8, 1.0, 1.25),
        (55.0, 65.0, 75.0),
        (2, 3, 4),
        (1.25, 1.5, 1.8),
        (1.8, 2.0, 2.4, 3.0),
        ("ANY", "TRENDING", "RANGING"),
    )
    all_values = list(space)
    # Deterministic coverage across the complete parameter space, not a biased prefix.
    rng = random.Random(20261113)
    chosen = rng.sample(all_values, min(len(all_values), max(1, max_candidates)))
    genomes = [StrategyGenome(*values) for values in chosen]
    if not genomes:
        raise ValueError("No strategy candidates generated")

    ranked = []
    for genome in genomes:
        try:
            result = _evaluate(train, genome, warmup)
            ranked.append((_fitness(result, min_train_trades), genome, result))
        except (ValueError, ArithmeticError, KeyError):
            continue
    ranked.sort(key=lambda row: row[0], reverse=True)
    if not ranked or ranked[0][0] <= -999:
        return {
            "accepted": False,
            "status": "NO_TRAIN_CANDIDATE",
            "candidate_count": len(genomes),
            "reason": "No candidate passed minimum training evidence and profitability gates.",
            "strategy": None,
        }

    # Only top training candidates reach validation. Holdout remains untouched.
    finalists = ranked[: min(8, len(ranked))]
    validated = []
    for train_score, genome, train_result in finalists:
        try:
            val = _evaluate(validation, genome, warmup)
            score = _fitness(val, min_validation_trades)
            validated.append((score, genome, train_result, val))
        except (ValueError, ArithmeticError, KeyError):
            continue
    validated.sort(key=lambda row: row[0], reverse=True)
    if not validated or validated[0][0] <= -999:
        return {
            "accepted": False,
            "status": "VALIDATION_REJECTED",
            "candidate_count": len(genomes),
            "reason": "Candidates failed out-of-sample validation; no live strategy was promoted.",
            "strategy": None,
        }

    _, winner, train_result, validation_result = validated[0]
    holdout_result = _evaluate(holdout, winner, warmup)
    holdout_trades = int(holdout_result.get("trade_count", 0))
    holdout_pf = _metric(holdout_result, "profit_factor")
    holdout_return = _metric(holdout_result, "return_percent")
    holdout_dd = _metric(holdout_result, "max_drawdown_percent")
    accepted = (
        holdout_trades >= min_holdout_trades
        and holdout_return > 0.0
        and holdout_pf > 1.0
        and holdout_dd <= 20.0
    )
    return {
        "accepted": accepted,
        "status": "PROMOTABLE" if accepted else "HOLDOUT_REJECTED",
        "candidate_count": len(genomes),
        "finalist_count": len(finalists),
        "strategy": asdict(winner) if accepted else None,
        "proposed_strategy": asdict(winner),
        "training": {k: train_result[k] for k in ("trade_count", "return_percent", "profit_factor", "max_drawdown_percent")},
        "validation": {k: validation_result[k] for k in ("trade_count", "return_percent", "profit_factor", "max_drawdown_percent")},
        "holdout": {k: holdout_result[k] for k in ("trade_count", "return_percent", "profit_factor", "max_drawdown_percent")},
        "promotion_rules": {
            "minimum_holdout_trades": min_holdout_trades,
            "holdout_net_return_positive": True,
            "holdout_profit_factor_above": 1.0,
            "maximum_holdout_drawdown_percent": 20.0,
            "holdout_used_for_selection": False,
        },
        "reason": "Promote only after independent holdout gates pass." if accepted else
                  "Final unseen holdout failed; keep the strategy in research mode.",
    }
