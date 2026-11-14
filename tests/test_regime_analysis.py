import unittest

from tools.regime_analysis import (
    classify_regime, regime_labels, specialist_regime_breakdown,
)


class RegimeAnalysisTests(unittest.TestCase):
    def test_classifies_five_distinct_market_conditions(self):
        rows = [
            [1, 0, .4, 0, 0, .7, 0],
            [-1, 0, .4, 0, 0, -.2, 0],
            [1, 0, .8, 0, 0, .2, 0],
            [-1, 0, .8, 0, 0, .2, 0],
            [1, 0, .2, 0, 0, .2, 0],
        ]
        self.assertEqual(regime_labels(rows), [
            "high_volatility", "low_volatility", "trend_up", "trend_down", "range"
        ])

    def test_rejects_bad_feature_shapes_and_nonfinite_values(self):
        with self.assertRaises(ValueError):
            classify_regime([1, 2])
        with self.assertRaises(ValueError):
            classify_regime([1, 0, .2, 0, 0, float("nan"), 0])

    def test_breakdown_reports_metrics_and_small_sample_status(self):
        rows = [[1, 0, .8, 0, 0, .2, 0] for _ in range(40)]
        targets = [0, 1] * 20
        probabilities = [0.1, 0.9] * 20
        report = specialist_regime_breakdown(rows, targets, probabilities)
        self.assertEqual(report["trend_up"]["status"], "evaluated")
        self.assertEqual(report["trend_up"]["balanced_accuracy"], 1.0)
        self.assertEqual(report["trend_up"]["brier_score"], .01)
        self.assertEqual(report["range"]["status"], "insufficient_samples")

    def test_rejects_mismatched_or_invalid_predictions(self):
        with self.assertRaises(ValueError):
            specialist_regime_breakdown([[1, 0, .2, 0, 0, 0, 0]], [], [])
        with self.assertRaises(ValueError):
            specialist_regime_breakdown([[1, 0, .2, 0, 0, 0, 0]], [1], [1.2])


if __name__ == "__main__":
    unittest.main()
