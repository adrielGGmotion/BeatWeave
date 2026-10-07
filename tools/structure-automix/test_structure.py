import unittest
import numpy as np
from features import extract,RATE
from train import events


class StructureChecks(unittest.TestCase):
    def test_one_estimate_cannot_match_two_boundaries(self):
        score=events(np.array([10.,12.]),np.array([11.]))
        self.assertEqual(score['matches'],1)
        self.assertEqual(score['recall'],.5)

    def test_event_assignment_maximizes_valid_matches(self):
        score=events(np.array([10.,14.]),np.array([7.,11.]),3.)
        self.assertEqual(score['matches'],2)

    def test_source_clock_and_silent_audio(self):
        times,x=extract(np.zeros(RATE*30))
        np.testing.assert_array_equal(times,np.arange(60)*.5+.25)
        self.assertTrue(np.isfinite(x).all())

    def test_gain_invariance(self):
        rng=np.random.default_rng(12);y=rng.normal(size=RATE*25).astype(np.float32)*.02
        _,a=extract(y);_,b=extract(y*.5)
        np.testing.assert_allclose(a,b,atol=2e-5)


if __name__=='__main__':unittest.main()
