import unittest

from tools.meta_labeling import build_meta_labels


class MetaLabelingTests(unittest.TestCase):
    def test_accepts_correct_long_and_short_signals(self):
        self.assertEqual(
            build_meta_labels([1, -1], [1, -1]),
            [1, 1],
        )

    def test_rejects_wrong_side_and_timeout(self):
        self.assertEqual(
            build_meta_labels([1, -1, 1], [-1, 1, 0]),
            [0, 0, 0],
        )

    def test_no_signal_and_incomplete_horizon_are_excluded(self):
        self.assertEqual(
            build_meta_labels([0, 1, -1], [1, None, None]),
            [None, None, None],
        )

    def test_rejects_misaligned_arrays_and_invalid_values(self):
        with self.assertRaises(ValueError):
            build_meta_labels([1], [])
        with self.assertRaises(ValueError):
            build_meta_labels([2], [1])
        with self.assertRaises(ValueError):
            build_meta_labels([1], [2])


if __name__ == "__main__":
    unittest.main()
