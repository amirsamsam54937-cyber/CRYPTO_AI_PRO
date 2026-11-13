import unittest

from tools.probability_calibration import (
    apply_platt_calibrator,
    chronological_calibration_split,
    fit_platt_calibrator,
)


class ProbabilityCalibrationTests(unittest.TestCase):
    def test_chronological_split_is_ordered_disjoint_and_purged(self):
        parts = chronological_calibration_split(100, purge=6)
        self.assertLess(max(parts["train"]), min(parts["calibration"]))
        self.assertLess(max(parts["calibration"]), min(parts["test"]))
        self.assertGreaterEqual(min(parts["calibration"]) - max(parts["train"]), 7)
        self.assertGreaterEqual(min(parts["test"]) - max(parts["calibration"]), 7)
        self.assertFalse(set(parts["train"]) & set(parts["calibration"]))
        self.assertFalse(set(parts["calibration"]) & set(parts["test"]))

    def test_platt_fit_returns_ordered_probabilities(self):
        logits = [-3, -2, -1, -0.5, 0.5, 1, 2, 3] * 5
        labels = [0, 0, 0, 0, 1, 1, 1, 1] * 5
        model = fit_platt_calibrator(logits, labels)
        probs = apply_platt_calibrator([-2, 0, 2], model)
        self.assertEqual(model["status"], "fitted")
        self.assertTrue(0 < probs[0] < probs[1] < probs[2] < 1)

    def test_rejects_small_or_single_class_calibration_set(self):
        with self.assertRaises(ValueError):
            fit_platt_calibrator([0.1] * 10, [0, 1] * 5)
        with self.assertRaises(ValueError):
            fit_platt_calibrator([0.1] * 30, [1] * 30)

    def test_rejects_nonfinite_values(self):
        with self.assertRaises(ValueError):
            fit_platt_calibrator([float("nan")] * 30, [0, 1] * 15)


if __name__ == "__main__":
    unittest.main()
