import math
import unittest

from tools.wilder_indicators import true_range_series, wilder_atr, wilder_adx


class WilderIndicatorTests(unittest.TestCase):
    def test_true_range_uses_previous_close_for_gaps(self):
        tr = true_range_series([10, 12, 13], [9, 11, 12], [9.5, 11.5, 12.5])
        self.assertTrue(math.isnan(tr[0]))
        self.assertEqual(tr[1:], [2.5, 1.5])

    def test_atr_seed_and_recursive_smoothing_match_wilder(self):
        high = [10, 12, 13, 14, 13]
        low = [9, 11, 12, 12, 11]
        close = [9.5, 11.5, 12.5, 13, 11.5]
        atr = wilder_atr(high, low, close, period=3)
        self.assertTrue(all(math.isnan(x) for x in atr[:3]))
        # TR(1..3) = 2.5, 1.5, 2.0; seed = 2.0
        self.assertAlmostEqual(atr[3], 2.0)
        # TR(4) = 2.0; Wilder recurrence keeps ATR at 2.0
        self.assertAlmostEqual(atr[4], 2.0)

    def test_adx_has_correct_warmup_and_is_bounded(self):
        high = [10, 11, 12, 13, 14, 15, 16, 17]
        low = [9, 10, 11, 12, 13, 14, 15, 16]
        close = [9.5, 10.5, 11.5, 12.5, 13.5, 14.5, 15.5, 16.5]
        adx = wilder_adx(high, low, close, period=3)
        self.assertTrue(all(math.isnan(x) for x in adx[:5]))
        self.assertAlmostEqual(adx[5], 100.0)
        self.assertTrue(all(0.0 <= x <= 100.0 for x in adx[5:]))

    def test_rejects_invalid_period_lengths_and_ohlc(self):
        with self.assertRaises(ValueError):
            wilder_atr([1], [1], [1], period=1)
        with self.assertRaises(ValueError):
            wilder_atr([2], [1, 1], [1])
        with self.assertRaises(ValueError):
            wilder_adx([1], [2], [1], period=2)


if __name__ == "__main__":
    unittest.main()
