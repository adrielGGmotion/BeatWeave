import unittest
from unittest.mock import patch
import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
from common import export_booster, predict, is_development_recording
from acoustic_cues import temporal_peaks
from propose import correction


class CorpusContracts(unittest.TestCase):
    def test_development_song_versions_are_excluded_without_excluding_other_artists(self):
        self.assertTrue(is_development_recording('Darude - Sandstorm (Paul Oakenfold 2015 Remix)'))
        self.assertTrue(is_development_recording('The Chainsmokers Feat. Halsey - Closer (BLKWHT Remix)'))
        self.assertFalse(is_development_recording('Pig&Dan - Sandstorm [ELEVATE]'))

    def test_export_matches_training_runtime_on_unseen_and_missing_values(self):
        rng=np.random.default_rng(17)
        x=rng.normal(size=(500,4));y=x[:,0]*x[:,1]+x[:,3]>.1
        x[::7,2]=np.nan
        m=HistGradientBoostingClassifier(max_iter=20,early_stopping=False,random_state=17).fit(x,y)
        exported=export_booster(m,['a','b','c','d'],'test')
        probe=rng.normal(size=(123,4));probe[::3,2]=np.nan
        np.testing.assert_allclose(predict(exported,probe,True),m.predict_proba(probe)[:,1],atol=1e-12)

    def test_peak_suppression_uses_seconds_not_candidate_indices(self):
        # Dense overlapping detections around one boundary cannot erase a
        # separate musical boundary merely because they are adjacent in a list.
        self.assertEqual(temporal_peaks([1.,1.02,1.05,9.],[.7,.9,.8,.85],2),[1,3])

    def test_phase_adjustment_aligns_audible_events_after_tempo_conversion(self):
        plan={'a':20.,'b':0.,'duration':16.,'bars':8,'speed':1.1}
        with patch('propose.phase',side_effect=[{'offset':.01,'score':.9,'margin':.3},
                                               {'offset':.05,'score':.9,'margin':.3}]):
            result=correction({},[None,None],plan)
        incoming_event_output_time=(.05-result['applied_incoming_shift'])/1.1
        self.assertAlmostEqual(incoming_event_output_time,.01,places=12)
        self.assertTrue(result['applied'])

    def test_ambiguous_and_halfbeat_phase_proposals_are_not_applied(self):
        for offset,margin in [(.05,.01),(.25,.8)]:
            with self.subTest(offset=offset),patch('propose.phase',side_effect=[
                    {'offset':0.,'score':.9,'margin':.8},{'offset':offset,'score':.9,'margin':margin}]):
                result=correction({},[None,None],{'a':20.,'b':0.,'duration':16.,'bars':8,'speed':1.})
                self.assertFalse(result['applied']);self.assertEqual(result['applied_incoming_shift'],0.)


if __name__=='__main__':unittest.main()
