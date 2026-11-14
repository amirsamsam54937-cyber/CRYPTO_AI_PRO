"""Reliability-aware consensus for ZORO's specialist ensemble.

All calibration observations must be chronological out-of-sample predictions.
This module is advisory only: it never places orders or turns a probability into
a guaranteed win rate. It is dependency-free and suitable for mobile inference.
"""
from __future__ import annotations

import math
from typing import Iterable, Mapping, Sequence


def _probability(value: float, name: str) -> float:
    value = float(value)
    if not math.isfinite(value) or not 0.0 <= value <= 1.0:
        raise ValueError(f"{name} must be finite and within [0, 1]")
    return value


def brier_score(probabilities: Sequence[float], outcomes: Sequence[int]) -> float:
    """Mean squared probability error for binary outcomes."""
    if len(probabilities) != len(outcomes) or not probabilities:
        raise ValueError("probabilities and outcomes must have equal nonzero length")
    errors = []
    for probability, outcome in zip(probabilities, outcomes):
        p = _probability(probability, "probability")
        if outcome not in (0, 1):
            raise ValueError("outcomes must contain only 0 or 1")
        errors.append((p - int(outcome)) ** 2)
    return sum(errors) / len(errors)


def expected_calibration_error(
    probabilities: Sequence[float],
    outcomes: Sequence[int],
    bins: int = 10,
) -> float:
    """Equal-width-bin ECE; report with sample count because ECE is noisy."""
    if len(probabilities) != len(outcomes) or not probabilities:
        raise ValueError("probabilities and outcomes must have equal nonzero length")
    if bins < 2 or bins > 100:
        raise ValueError("bins must be between 2 and 100")
    buckets = [[] for _ in range(bins)]
    for probability, outcome in zip(probabilities, outcomes):
        p = _probability(probability, "probability")
        if outcome not in (0, 1):
            raise ValueError("outcomes must contain only 0 or 1")
        index = min(int(p * bins), bins - 1)
        buckets[index].append((p, int(outcome)))
    total = len(probabilities)
    return sum(
        len(bucket) / total
        * abs(
            sum(p for p, _ in bucket) / len(bucket)
            - sum(y for _, y in bucket) / len(bucket)
        )
        for bucket in buckets
        if bucket
    )


def _bounded_weights(raw: Mapping[str, float], minimum: float, maximum: float) -> dict:
    """Project positive scores onto a capped simplex with deterministic order."""
    names = sorted(raw)
    count = len(names)
    if minimum < 0 or maximum <= 0 or count * minimum > 1.0 + 1e-12:
        raise ValueError("infeasible minimum weight")
    # A fixed cap such as 0.20 is infeasible for fewer than five members.
    # Relax only the cap needed to make the simplex feasible; never relax the floor.
    maximum = max(maximum, 1.0 / count)
    scores = {name: max(float(raw[name]), 1e-15) for name in names}
    total = sum(scores.values())
    weights = {name: scores[name] / total for name in names}
    fixed = set()
    # Iterative capped-simplex projection. At least one weight is fixed per pass.
    while True:
        changed = False
        free = [name for name in names if name not in fixed]
        remaining = 1.0 - sum(weights[name] for name in fixed)
        free_total = sum(scores[name] for name in free)
        if not free:
            break
        for name in free:
            candidate = remaining * scores[name] / free_total if free_total else remaining / len(free)
            if candidate < minimum:
                weights[name] = minimum
                fixed.add(name)
                changed = True
                break
            if candidate > maximum:
                weights[name] = maximum
                fixed.add(name)
                changed = True
                break
        if not changed:
            for name in free:
                weights[name] = remaining * scores[name] / free_total if free_total else remaining / len(free)
            break
    # Remove floating-point residue without violating caps.
    residue = 1.0 - sum(weights.values())
    for name in names:
        if minimum - 1e-12 <= weights[name] + residue <= maximum + 1e-12:
            weights[name] += residue
            break
    return weights


def estimate_member_weights(
    history: Mapping[str, Iterable[tuple[float, int]]],
    *,
    min_observations: int = 40,
    prior_brier: float = 0.25,
    shrinkage: float = 40.0,
    temperature: float = 0.05,
    min_weight: float = 0.02,
    max_weight: float = 0.20,
) -> dict:
    """Estimate capped weights from strictly out-of-sample member predictions.

    Small samples shrink toward a neutral Brier score of 0.25. Better-than-
    baseline members receive more weight, but caps prevent a single specialist
    from dominating. Insufficient history yields uniform weights.
    """
    if not history:
        raise ValueError("at least one member is required")
    if min_observations < 1 or shrinkage < 0 or temperature <= 0:
        raise ValueError("invalid calibration parameters")
    if not 0.0 <= prior_brier <= 1.0:
        raise ValueError("prior_brier must be within [0, 1]")
    names = sorted(history)
    scores = {}
    diagnostics = {}
    for name in names:
        rows = list(history[name])
        if not rows:
            diagnostics[name] = {"observations": 0, "brier": None, "shrunk_brier": prior_brier}
            scores[name] = 1.0
            continue
        probabilities = [row[0] for row in rows]
        outcomes = [row[1] for row in rows]
        observed = brier_score(probabilities, outcomes)
        n = len(rows)
        shrunk = (n * observed + shrinkage * prior_brier) / (n + shrinkage)
        diagnostics[name] = {
            "observations": n,
            "brier": observed,
            "shrunk_brier": shrunk,
        }
        scores[name] = math.exp(max(-50.0, min(50.0, (prior_brier - shrunk) / temperature)))
    if all(item["observations"] < min_observations for item in diagnostics.values()):
        weights = {name: 1.0 / len(names) for name in names}
    else:
        # Members below the minimum retain only prior-shrunk evidence, not a
        # zero weight; the cap and shrinkage limit overreaction to small samples.
        weights = _bounded_weights(scores, min_weight, max_weight)
    return {"weights": weights, "diagnostics": diagnostics}


def evaluate_consensus(
    probabilities: Mapping[str, float],
    weights: Mapping[str, float],
    *,
    probability_threshold: float = 0.55,
    minimum_agreement: float = 0.70,
    maximum_disagreement: float = 0.18,
) -> dict:
    """Combine specialist probabilities and abstain on weak/conflicting votes."""
    if not probabilities or set(probabilities) != set(weights):
        raise ValueError("probability and weight members must match and be nonempty")
    threshold = _probability(probability_threshold, "probability_threshold")
    if not 0.5 <= threshold <= 1.0:
        raise ValueError("probability_threshold must be between 0.5 and 1")
    if not 0.5 <= minimum_agreement <= 1.0:
        raise ValueError("minimum_agreement must be between 0.5 and 1")
    if not 0.0 <= maximum_disagreement <= 0.5:
        raise ValueError("maximum_disagreement must be within [0, 0.5]")
    names = sorted(probabilities)
    ps = {name: _probability(probabilities[name], f"{name} probability") for name in names}
    ws = {name: _probability(weights[name], f"{name} weight") for name in names}
    total_weight = sum(ws.values())
    if total_weight <= 0:
        raise ValueError("weights must sum to a positive value")
    ws = {name: ws[name] / total_weight for name in names}
    up_weight = sum(ws[name] for name in names if ps[name] >= 0.5)
    down_weight = 1.0 - up_weight
    agreement = max(up_weight, down_weight)
    combined = sum(ws[name] * ps[name] for name in names)
    variance = sum(ws[name] * (ps[name] - combined) ** 2 for name in names)
    disagreement = math.sqrt(max(0.0, variance))
    direction = "UP" if combined >= 0.5 else "DOWN"
    approved = (
        agreement >= minimum_agreement
        and disagreement <= maximum_disagreement
        and (combined >= threshold or combined <= 1.0 - threshold)
    )
    return {
        "direction": direction if approved else "WAIT",
        "combined_probability_up": combined,
        "weighted_vote_agreement": agreement,
        "disagreement_stddev": disagreement,
        "approved": approved,
        "reason": (
            "consensus_within_risk_gates"
            if approved
            else "abstain_due_to_weak_consensus_or_disagreement"
        ),
        "member_votes": {
            name: {
                "probability_up": ps[name],
                "weight": ws[name],
                "vote": "UP" if ps[name] >= 0.5 else "DOWN",
            }
            for name in names
        },
    }


def estimate_regime_member_weights(
    regime_history: Mapping[str, Mapping[str, Iterable[tuple[float, int]]]],
    *,
    global_weights: Mapping[str, float] | None = None,
    min_observations: int = 40,
    prior_brier: float = 0.25,
    shrinkage: float = 40.0,
    temperature: float = 0.05,
    min_weight: float = 0.02,
    max_weight: float = 0.20,
) -> dict:
    """Estimate specialist reliability per market regime with conservative fallback.

    Each regime must contain the same specialist set. Regime-specific weights are
    activated only when EVERY specialist has enough chronological OOS observations
    in that regime. Otherwise the supplied global weights (or uniform priors) are
    used. This prevents a sparse regime from silently producing unstable weights.
    """
    if not regime_history:
        raise ValueError("at least one regime is required")
    if min_observations < 1:
        raise ValueError("min_observations must be positive")

    regimes = sorted(regime_history)
    first_members = set(regime_history[regimes[0]])
    if not first_members:
        raise ValueError("each regime must contain at least one member")
    if any(set(regime_history[name]) != first_members for name in regimes):
        raise ValueError("all regimes must contain the same specialist set")

    names = sorted(first_members)
    if global_weights is not None:
        if set(global_weights) != first_members:
            raise ValueError("global weights must match the specialist set")
        normalized_global = {
            name: _probability(global_weights[name], f"{name} global weight")
            for name in names
        }
        total = sum(normalized_global.values())
        if total <= 0.0:
            raise ValueError("global weights must sum to a positive value")
        normalized_global = {name: value / total for name, value in normalized_global.items()}
    else:
        normalized_global = {name: 1.0 / len(names) for name in names}

    result = {}
    for regime in regimes:
        history = regime_history[regime]
        # Materialize once so generators are not consumed by the count check.
        materialized = {name: list(history[name]) for name in names}
        counts = {name: len(materialized[name]) for name in names}
        if all(counts[name] >= min_observations for name in names):
            estimate = estimate_member_weights(
                materialized,
                min_observations=min_observations,
                prior_brier=prior_brier,
                shrinkage=shrinkage,
                temperature=temperature,
                min_weight=min_weight,
                max_weight=max_weight,
            )
            result[regime] = {
                "weights": estimate["weights"],
                "diagnostics": estimate["diagnostics"],
                "source": "regime_oos",
                "observations_by_member": counts,
            }
        else:
            result[regime] = {
                "weights": normalized_global,
                "diagnostics": {
                    name: {"observations": counts[name], "brier": None}
                    for name in names
                },
                "source": "global_fallback" if global_weights is not None else "uniform_fallback",
                "observations_by_member": counts,
            }
    return {
        "regimes": result,
        "metadata": {
            "method": "chronological_oos_brier_with_shrinkage",
            "min_observations_per_member": min_observations,
            "sparse_regime_fallback": "global" if global_weights is not None else "uniform",
            "runtime_weighting_enabled": False,
        },
    }


def select_abstention_threshold(
    outcomes: Iterable[int],
    probabilities_up: Iterable[float],
    *,
    candidates: Iterable[float] = (0.55, 0.58, 0.60, 0.62, 0.65, 0.68, 0.70, 0.75, 0.80),
    min_samples: int = 100,
    minimum_balanced_accuracy: float = 0.52,
) -> dict:
    """Choose a confidence gate on calibration data only; never inspect final test.

    A prediction is emitted only when max(P(up), P(down)) clears the threshold.
    Among thresholds meeting minimum sample and balanced-accuracy requirements,
    select the one with greatest coverage. If none qualifies, fail closed (WAIT).
    """
    ys = [int(value) for value in outcomes]
    ps = [_probability(value, "probability_up") for value in probabilities_up]
    if len(ys) != len(ps) or not ys:
        raise ValueError("outcomes and probabilities must be nonempty and equal length")
    if any(value not in (0, 1) for value in ys):
        raise ValueError("outcomes must contain only binary labels")
    if min_samples < 2 or not 0.5 <= minimum_balanced_accuracy <= 1.0:
        raise ValueError("invalid abstention gate settings")
    candidates = sorted({_probability(value, "candidate_threshold") for value in candidates})
    if any(value <= 0.5 or value > 1.0 for value in candidates):
        raise ValueError("candidate thresholds must be in (0.5, 1]")
    scored = []
    for threshold in candidates:
        selected = [i for i, p in enumerate(ps) if max(p, 1.0 - p) >= threshold]
        if len(selected) < min_samples:
            continue
        truth = [ys[i] for i in selected]
        predicted = [1 if ps[i] >= 0.5 else 0 for i in selected]
        recalls = []
        for label in (0, 1):
            indices = [i for i, value in enumerate(truth) if value == label]
            if not indices:
                break
            recalls.append(sum(predicted[i] == label for i in indices) / len(indices))
        if len(recalls) != 2:
            continue
        balanced = sum(recalls) / 2.0
        scored.append({
            "threshold": threshold,
            "selected_samples": len(selected),
            "coverage": len(selected) / len(ys),
            "balanced_accuracy": balanced,
        })
    eligible = [item for item in scored if item["balanced_accuracy"] >= minimum_balanced_accuracy]
    if not eligible:
        return {
            "enabled": False, "threshold": 1.01, "reason": "no_calibration_threshold_met_quality_gate",
            "calibration_samples": len(ys), "candidates": scored,
        }
    best = max(eligible, key=lambda item: (item["coverage"], item["balanced_accuracy"], -item["threshold"]))
    return {
        "enabled": True, "threshold": best["threshold"], "reason": "calibration_quality_gate_passed",
        "calibration_samples": len(ys), "selected_samples": best["selected_samples"],
        "coverage": best["coverage"], "balanced_accuracy": best["balanced_accuracy"],
        "candidates": scored,
    }
