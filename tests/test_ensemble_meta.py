import unittest

from tools.ensemble_meta import (
    brier_score,
    estimate_member_weights,
    evaluate_consensus,
    expected_calibration_error,
    estimate_regime_member_weights,
    select_abstention_threshold,
)


class EnsembleMetaTests(unittest.TestCase):
    def test_brier_and_ece_are_bounded(self):
        self.assertAlmostEqual(brier_score([0.9, 0.1], [1, 0]), 0.01)
        self.assertLessEqual(expected_calibration_error([0.9, 0.1], [1, 0]), 0.1)

    def test_insufficient_history_returns_uniform_weights(self):
        history = {"m1": [(0.8, 1)], "m2": [(0.2, 0)]}
        result = estimate_member_weights(history, min_observations=20)
        self.assertEqual(result["weights"], {"m1": 0.5, "m2": 0.5})

    def test_reliable_member_gets_more_weight_but_is_capped(self):
        good = [(0.9, 1), (0.1, 0)] * 50
        weak = [(0.9, 0), (0.1, 1)] * 50
        result = estimate_member_weights(
            {"good": good, "weak": weak},
            min_observations=20,
            min_weight=0.05,
            max_weight=0.8,
        )
        self.assertGreater(result["weights"]["good"], result["weights"]["weak"])
        self.assertLessEqual(max(result["weights"].values()), 0.8)
        self.assertAlmostEqual(sum(result["weights"].values()), 1.0)

    def test_two_member_default_weight_cap_is_feasible(self):
        good = [(0.9, 1), (0.1, 0)] * 30
        weak = [(0.9, 0), (0.1, 1)] * 30
        result = estimate_member_weights({"good": good, "weak": weak})
        self.assertAlmostEqual(sum(result["weights"].values()), 1.0)
        self.assertLessEqual(max(result["weights"].values()), 0.5 + 1e-12)

    def test_regime_weights_require_sufficient_oos_history(self):
        good = [(0.9, 1), (0.1, 0)] * 25
        weak = [(0.9, 0), (0.1, 1)] * 25
        members = {"best": good, **{f"weak_{i}": weak for i in range(5)}}
        sparse = {name: rows[:4] for name, rows in members.items()}
        global_weights = {"best": 0.3, **{f"weak_{i}": 0.14 for i in range(5)}}
        regimes = {"trend": members, "range": sparse}
        result = estimate_regime_member_weights(
            regimes, global_weights=global_weights, min_observations=20,
        )
        self.assertEqual(result["regimes"]["trend"]["source"], "regime_oos")
        self.assertGreater(
            result["regimes"]["trend"]["weights"]["best"],
            result["regimes"]["trend"]["weights"]["weak_0"],
        )
        self.assertEqual(result["regimes"]["range"]["source"], "global_fallback")
        self.assertEqual(result["regimes"]["range"]["weights"], global_weights)
        self.assertFalse(result["metadata"]["runtime_weighting_enabled"])

    def test_regime_weighting_rejects_mismatched_specialists(self):
        with self.assertRaises(ValueError):
            estimate_regime_member_weights({
                "trend": {"a": [(0.8, 1)]},
                "range": {"b": [(0.2, 0)]},
            })

    def test_consensus_approves_agreement_and_exposes_member_votes(self):
        ps = {f"m{i}": 0.75 + i * 0.01 for i in range(10)}
        weights = {name: 0.1 for name in ps}
        result = evaluate_consensus(ps, weights)
        self.assertTrue(result["approved"])
        self.assertEqual(result["direction"], "UP")
        self.assertEqual(len(result["member_votes"]), 10)

    def test_conflicting_members_force_wait(self):
        ps = {f"m{i}": (0.95 if i < 5 else 0.05) for i in range(10)}
        weights = {name: 0.1 for name in ps}
        result = evaluate_consensus(ps, weights)
        self.assertFalse(result["approved"])
        self.assertEqual(result["direction"], "WAIT")


    def test_abstention_gate_uses_calibration_quality_and_coverage(self):
        outcomes = [1, 0] * 100
        probabilities = [0.9, 0.1] * 100
        result = select_abstention_threshold(
            outcomes, probabilities, min_samples=100, minimum_balanced_accuracy=0.6,
        )
        self.assertTrue(result["enabled"])
        self.assertEqual(result["threshold"], 0.55)
        self.assertAlmostEqual(result["coverage"], 1.0)
        self.assertAlmostEqual(result["balanced_accuracy"], 1.0)

    def test_abstention_gate_fails_closed_when_calibration_is_weak(self):
        outcomes = [0, 1] * 100
        probabilities = [0.9, 0.1] * 100
        result = select_abstention_threshold(
            outcomes, probabilities, min_samples=100, minimum_balanced_accuracy=0.6,
        )
        self.assertFalse(result["enabled"])
        self.assertGreater(result["threshold"], 1.0)

    def test_abstention_gate_rejects_mismatched_or_invalid_inputs(self):
        with self.assertRaises(ValueError):
            select_abstention_threshold([1], [0.8, 0.9])
        with self.assertRaises(ValueError):
            select_abstention_threshold([2, 0], [0.8, 0.2])

    def test_rejects_nonfinite_probability_and_mismatched_members(self):
        with self.assertRaises(ValueError):
            evaluate_consensus({"m1": float("nan")}, {"m1": 1.0})
        with self.assertRaises(ValueError):
            evaluate_consensus({"m1": 0.8}, {"m2": 1.0})


if __name__ == "__main__":
    unittest.main()
