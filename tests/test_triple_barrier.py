import unittest

from tools.triple_barrier import triple_barrier_labels


class TripleBarrierTests(unittest.TestCase):
    def test_upper_barrier_first(self):
        labels = triple_barrier_labels(
            [100, 100, 100, 100], [100, 102, 100, 100],
            [100, 100, 100, 100], [100, 101, 100, 100],
            profit_target=0.01, stop_loss=0.01, horizon=2,
        )
        self.assertEqual(labels[0], 1)

    def test_stop_wins_when_both_barriers_hit_same_bar(self):
        labels = triple_barrier_labels(
            [100, 100, 100], [100, 103, 100],
            [100, 97, 100], [100, 100, 100],
            profit_target=0.01, stop_loss=0.01, horizon=2,
        )
        self.assertEqual(labels[0], -1)

    def test_vertical_barrier_uses_net_cost_adjusted_return(self):
        labels = triple_barrier_labels(
            [100, 100, 100], [100, 100, 100],
            [100, 100, 100], [100, 100.5, 100.5],
            profit_target=0.05, stop_loss=0.05, horizon=1,
            round_trip_cost=0.01,
        )
        self.assertEqual(labels[0], -1)

    def test_incomplete_horizon_is_none(self):
        labels = triple_barrier_labels(
            [100, 100], [100, 100], [100, 100], [100, 100],
            horizon=2,
        )
        self.assertEqual(labels, [None, None])

    def test_rejects_bad_ohlc_and_parameters(self):
        with self.assertRaises(ValueError):
            triple_barrier_labels([100], [99], [100], [100])
        with self.assertRaises(ValueError):
            triple_barrier_labels([100], [100], [100], [100], horizon=0)


if __name__ == "__main__":
    unittest.main()
