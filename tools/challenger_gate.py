"""Conservative champion/challenger promotion gate for matched walk-forward folds.

This is a research gate, not an automatic deployment mechanism. Both models
must be evaluated on identical chronological folds and untouched labels.
"""
from __future__ import annotations

import math
from collections.abc import Sequence

_REQUIRED = ("log_loss", "brier_score", "balanced_accuracy", "expected_calibration_error", "samples")


def evaluate_challenger(
    champion_folds: Sequence[dict],
    challenger_folds: Sequence[dict],
    *,
    min_folds: int = 3,
    min_relative_log_loss_gain: float = 0.005,
    max_balanced_accuracy_drop: float = 0.01,
    max_brier_relative_regression: float = 0.02,
    max_ece_absolute_regression: float = 0.01,
    max_single_fold_log_loss_regression: float = 0.05,
) -> dict:
    """Approve research promotion only when matched out-of-sample evidence improves."""
    if len(champion_folds) != len(challenger_folds):
        raise ValueError("champion and challenger must have the same fold count")
    if len(champion_folds) < min_folds:
        return {"approved": False, "status": "insufficient_folds",
                "reason": f"at least {min_folds} matched folds are required"}
    limits = (min_relative_log_loss_gain, max_balanced_accuracy_drop,
              max_brier_relative_regression, max_ece_absolute_regression,
              max_single_fold_log_loss_regression)
    if any(not math.isfinite(float(x)) or float(x) < 0 for x in limits):
        raise ValueError("promotion thresholds must be finite and non-negative")

    paired = []
    for index, (champion, challenger) in enumerate(zip(champion_folds, challenger_folds)):
        for key in _REQUIRED:
            if key not in champion or key not in challenger:
                raise ValueError(f"fold {index} is missing metric {key}")
            a, b = float(champion[key]), float(challenger[key])
            if not math.isfinite(a) or not math.isfinite(b):
                raise ValueError(f"fold {index} has non-finite {key}")
        if int(champion["samples"]) != int(challenger["samples"]) or int(champion["samples"]) <= 0:
            raise ValueError(f"fold {index} is not evaluated on the same positive sample count")
        for metric in ("log_loss", "brier_score", "expected_calibration_error"):
            if float(champion[metric]) < 0 or float(challenger[metric]) < 0:
                raise ValueError(f"fold {index} has negative {metric}")
        for metric in ("balanced_accuracy",):
            if not (0 <= float(champion[metric]) <= 1 and 0 <= float(challenger[metric]) <= 1):
                raise ValueError(f"fold {index} has invalid {metric}")
        paired.append((champion, challenger))

    mean = lambda model, key: sum(float(row[key]) for row, _ in paired) / len(paired) if model == "champion" else sum(float(row[key]) for _, row in paired) / len(paired)
    c_loss, n_loss = mean("champion", "log_loss"), mean("challenger", "log_loss")
    c_brier, n_brier = mean("champion", "brier_score"), mean("challenger", "brier_score")
    c_bal, n_bal = mean("champion", "balanced_accuracy"), mean("challenger", "balanced_accuracy")
    c_ece, n_ece = mean("champion", "expected_calibration_error"), mean("challenger", "expected_calibration_error")
    relative_gain = (c_loss - n_loss) / max(c_loss, 1e-12)
    reasons = []
    if relative_gain < min_relative_log_loss_gain:
        reasons.append("mean log-loss improvement is below the promotion threshold")
    if n_bal < c_bal - max_balanced_accuracy_drop:
        reasons.append("balanced accuracy regressed beyond the allowed tolerance")
    if n_brier > c_brier * (1 + max_brier_relative_regression) + 1e-12:
        reasons.append("Brier score regressed beyond the allowed tolerance")
    if n_ece > c_ece + max_ece_absolute_regression + 1e-12:
        reasons.append("calibration error regressed beyond the allowed tolerance")
    worst_fold_regression = max(
        (float(new["log_loss"]) - float(old["log_loss"])) / max(float(old["log_loss"]), 1e-12)
        for old, new in paired
    )
    if worst_fold_regression > max_single_fold_log_loss_regression:
        reasons.append("at least one fold has excessive log-loss regression")
    return {
        "approved": not reasons,
        "status": "approved_for_challenger_review" if not reasons else "rejected",
        "reason": "all matched-fold gates passed" if not reasons else "; ".join(reasons),
        "fold_count": len(paired),
        "relative_log_loss_gain": relative_gain,
        "champion": {"log_loss": c_loss, "brier_score": c_brier,
                     "balanced_accuracy": c_bal, "expected_calibration_error": c_ece},
        "challenger": {"log_loss": n_loss, "brier_score": n_brier,
                       "balanced_accuracy": n_bal, "expected_calibration_error": n_ece},
        "worst_fold_log_loss_regression": worst_fold_regression,
    }
