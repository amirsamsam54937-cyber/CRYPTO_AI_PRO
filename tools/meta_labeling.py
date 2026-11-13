"""Meta-label targets for deciding whether a primary directional signal is worth taking.

The primary signal must be generated out-of-sample for every row used to train
a meta-model. This module only constructs labels; it does not fit or execute a
model and it never emits an order.
"""
from __future__ import annotations

from collections.abc import Sequence


def build_meta_labels(
    primary_signals: Sequence[int],
    barrier_outcomes: Sequence[int | None],
) -> list[int | None]:
    """Label a primary signal as 1 when its side wins the barrier outcome.

    Signal convention: +1=long, -1=short, 0=no primary signal.
    Barrier outcome convention: +1=up/long win, -1=down/short win,
    0=neither barrier / non-positive timeout, None=incomplete horizon.
    Rows without a primary signal and rows without a complete horizon are None.
    """
    if len(primary_signals) != len(barrier_outcomes):
        raise ValueError("primary_signals and barrier_outcomes must align")
    labels: list[int | None] = []
    for index, (signal, outcome) in enumerate(zip(primary_signals, barrier_outcomes)):
        if signal not in (-1, 0, 1):
            raise ValueError(f"invalid primary signal at row {index}: {signal!r}")
        if outcome not in (-1, 0, 1, None):
            raise ValueError(f"invalid barrier outcome at row {index}: {outcome!r}")
        if signal == 0 or outcome is None:
            labels.append(None)
        else:
            labels.append(1 if signal == outcome else 0)
    return labels
