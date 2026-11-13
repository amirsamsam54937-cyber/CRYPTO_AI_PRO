import unittest

from tools.challenger_gate import evaluate_challenger


def folds(log_loss, brier=0.20, balanced=0.55, ece=0.05):
    return [
        {"log_loss": log_loss, "brier_score": brier,
         "balanced_accuracy": balanced,
         "expected_calibration_error": ece, "samples": 500}
        for _ in range(3)
    ]


class ChallengerGateTests(unittest.TestCase):
    def test_promotes_consistent_improvement(self):
        result = evaluate_challenger(folds(0.69), folds(0.67, brier=0.19))
        self.assertTrue(result["approved"])
        self.assertEqual(result["status"], "approved_for_challenger_review")

    def test_rejects_no_improvement(self):
        result = evaluate_challenger(folds(0.69), folds(0.69))
        self.assertFalse(result["approved"])

    def test_rejects_metric_regression(self):
        result = evaluate_challenger(
            folds(0.69), folds(0.66, brier=0.25, balanced=0.52, ece=0.08)
        )
        self.assertFalse(result["approved"])
        self.assertIn("Brier score", result["reason"])

    def test_requires_matched_folds_and_sample_counts(self):
        with self.assertRaises(ValueError):
            evaluate_challenger(folds(0.69), folds(0.66)[:2])
        challenger = folds(0.66)
        challenger[0]["samples"] = 499
        with self.assertRaises(ValueError):
            evaluate_challenger(folds(0.69), challenger)

    def test_insufficient_folds_fails_closed(self):
        result = evaluate_challenger(folds(0.69)[:2], folds(0.66)[:2])
        self.assertFalse(result["approved"])
        self.assertEqual(result["status"], "insufficient_folds")


if __name__ == "__main__":
    unittest.main()
