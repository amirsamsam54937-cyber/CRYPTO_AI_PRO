import unittest
import numpy as np
from tools.time_series_validation import three_way_chronological_masks

class ThreeWayChronologicalTests(unittest.TestCase):
    def test_partitions_are_ordered_disjoint_and_purged(self):
        ts=np.repeat(np.arange(100,dtype=np.int64)*3600000,3)
        train,cal,test,purged,first,second=three_way_chronological_masks(ts)
        self.assertFalse(np.any(train & cal))
        self.assertFalse(np.any(train & test))
        self.assertFalse(np.any(cal & test))
        self.assertTrue(np.all(ts[train] < ts[cal].min()))
        self.assertTrue(np.all(ts[cal] < ts[test].min()))
        self.assertGreater(purged,0)
        self.assertGreater(second,first)

    def test_rejects_too_short_history(self):
        with self.assertRaises(ValueError):
            three_way_chronological_masks(np.arange(10))

if __name__ == "__main__":
    unittest.main()
