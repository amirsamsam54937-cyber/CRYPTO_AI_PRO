import unittest

from tools.market_drift import (
    AdaptiveWindowDrift,
    MarketDriftMonitor,
    PageHinkleyDrift,
)


class MarketDriftTests(unittest.TestCase):
    def test_adaptive_window_ignores_stable_stream(self):
        detector = AdaptiveWindowDrift(min_window=12, max_window=128)
        reports = [detector.update(0.5) for _ in range(80)]
        self.assertFalse(any(report.detected for report in reports))

    def test_adaptive_window_detects_abrupt_shift(self):
        detector = AdaptiveWindowDrift(min_window=12, max_window=128)
        for _ in range(80):
            detector.update(0.2)
        reports = [detector.update(0.8) for _ in range(80)]
        self.assertTrue(any(report.detected for report in reports))
        self.assertGreaterEqual(detector.drift_count, 1)
        self.assertLessEqual(detector.sample_count, 128)

    def test_page_hinkley_detects_gradual_mean_change(self):
        detector = PageHinkleyDrift(
            delta=0.002, threshold=2.0, min_samples=20
        )
        for _ in range(60):
            detector.update(0.2)
        reports = [detector.update(0.7) for _ in range(40)]
        self.assertTrue(any(report.detected for report in reports))

    def test_rejects_nan_and_out_of_range_values(self):
        detector = AdaptiveWindowDrift()
        for invalid in (float("nan"), float("inf"), -0.01, 1.01):
            with self.assertRaises(ValueError):
                detector.update(invalid)

    def test_combined_monitor_recommends_revalidation_after_drift(self):
        monitor = MarketDriftMonitor()
        for _ in range(80):
            monitor.update(0.15)
        reports = [monitor.update(0.85) for _ in range(80)]
        self.assertTrue(any(r["drift_detected"] for r in reports))
        self.assertTrue(
            any(
                r["recommended_action"]
                == "pause_new_signal_approval_and_revalidate"
                for r in reports
            )
        )


if __name__ == "__main__":
    unittest.main()
