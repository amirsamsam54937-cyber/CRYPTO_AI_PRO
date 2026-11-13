"""Streaming market-drift detectors for ZORO.

This module is deliberately dependency-free and advisory-only. It detects changes
in bounded, normalized telemetry (for example rolling model loss, calibration
error, or normalized volatility). It does not create or execute trade signals.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import math
from typing import Deque, Optional


@dataclass(frozen=True)
class DriftReport:
    detected: bool
    detector: str
    sample_count: int
    reference_mean: Optional[float]
    recent_mean: Optional[float]
    severity: float
    reason: str


class AdaptiveWindowDrift:
    """ADWIN-inspired two-window mean-shift detector for values in [0, 1].

    The oldest observations are discarded after a statistically significant
    split. A bounded window prevents unbounded memory growth on mobile devices.
    """

    def __init__(
        self,
        delta: float = 0.002,
        min_window: int = 24,
        max_window: int = 512,
    ) -> None:
        if not 0.0 < delta < 1.0:
            raise ValueError("delta must be between 0 and 1")
        if min_window < 4 or max_window < 2 * min_window:
            raise ValueError("max_window must be at least twice min_window")
        self.delta = float(delta)
        self.min_window = int(min_window)
        self.max_window = int(max_window)
        self._values: Deque[float] = deque(maxlen=self.max_window)
        self.drift_count = 0

    @property
    def sample_count(self) -> int:
        return len(self._values)

    def update(self, value: float) -> DriftReport:
        value = float(value)
        if not math.isfinite(value) or not 0.0 <= value <= 1.0:
            raise ValueError("drift input must be finite and within [0, 1]")
        self._values.append(value)
        detected = False
        old_mean = None
        new_mean = None
        severity = 0.0

        # Re-check after each cut: a large shift can require dropping more
        # than one old segment before the remaining window is homogeneous.
        while len(self._values) >= 2 * self.min_window:
            values = list(self._values)
            n = len(values)
            log_term = math.log(2.0 * math.log(max(n, 3)) / self.delta)
            prefix = [0.0]
            for item in values:
                prefix.append(prefix[-1] + item)

            best = None
            for cut in range(self.min_window, n - self.min_window + 1):
                left_n, right_n = cut, n - cut
                left_mean = prefix[cut] / left_n
                right_mean = (prefix[n] - prefix[cut]) / right_n
                bound = math.sqrt(
                    0.5 * log_term * (1.0 / left_n + 1.0 / right_n)
                )
                difference = abs(left_mean - right_mean)
                if difference > bound and (
                    best is None or difference / bound > best[0]
                ):
                    best = (
                        difference / bound,
                        cut,
                        left_mean,
                        right_mean,
                        difference,
                    )

            if best is None:
                break
            _, cut, old_mean, new_mean, severity = best
            for _ in range(cut):
                self._values.popleft()
            detected = True
            self.drift_count += 1

        return DriftReport(
            detected=detected,
            detector="adaptive-window",
            sample_count=len(self._values),
            reference_mean=old_mean,
            recent_mean=new_mean,
            severity=float(severity),
            reason=(
                "bounded-window mean shift detected"
                if detected
                else "no statistically significant window split"
            ),
        )


class PageHinkleyDrift:
    """Two-sided Page-Hinkley monitor for gradual mean changes."""

    def __init__(
        self,
        delta: float = 0.005,
        threshold: float = 5.0,
        min_samples: int = 30,
    ) -> None:
        if delta < 0 or threshold <= 0 or min_samples < 5:
            raise ValueError("invalid Page-Hinkley parameters")
        self.delta = float(delta)
        self.threshold = float(threshold)
        self.min_samples = int(min_samples)
        self.reset()

    def reset(self) -> None:
        self.count = 0
        self.mean = 0.0
        self.cumulative = 0.0
        self.minimum = 0.0
        self.maximum = 0.0
        self.drift_count = 0

    def update(self, value: float) -> DriftReport:
        value = float(value)
        if not math.isfinite(value) or not 0.0 <= value <= 1.0:
            raise ValueError("drift input must be finite and within [0, 1]")
        previous_mean = self.mean
        self.count += 1
        self.mean += (value - self.mean) / self.count
        self.cumulative += value - previous_mean - self.delta
        self.minimum = min(self.minimum, self.cumulative)
        self.maximum = max(self.maximum, self.cumulative)
        upward = self.cumulative - self.minimum
        downward = self.maximum - self.cumulative
        severity = max(upward, downward) / self.threshold
        detected = self.count >= self.min_samples and severity > 1.0
        if detected:
            self.drift_count += 1
            self.reset()
        return DriftReport(
            detected=detected,
            detector="page-hinkley",
            sample_count=self.count,
            reference_mean=previous_mean if self.count > 1 else None,
            recent_mean=value,
            severity=float(severity),
            reason=(
                "cumulative mean shift detected"
                if detected
                else "no cumulative mean shift detected"
            ),
        )


class MarketDriftMonitor:
    """Combines fast adaptive-window and gradual Page-Hinkley alarms."""

    def __init__(self) -> None:
        self.adaptive = AdaptiveWindowDrift()
        self.page_hinkley = PageHinkleyDrift()

    def update(self, metric: float) -> dict:
        adaptive = self.adaptive.update(metric)
        page_hinkley = self.page_hinkley.update(metric)
        return {
            "drift_detected": adaptive.detected or page_hinkley.detected,
            "adaptive_window": adaptive,
            "page_hinkley": page_hinkley,
            "recommended_action": (
                "pause_new_signal_approval_and_revalidate"
                if adaptive.detected or page_hinkley.detected
                else "continue_monitoring"
            ),
        }
