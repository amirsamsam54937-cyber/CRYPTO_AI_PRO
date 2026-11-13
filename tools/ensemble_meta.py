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
    if count * maximum < 1.0 - 1e-12:
        raise ValueError("infeasible maximum weight")
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
