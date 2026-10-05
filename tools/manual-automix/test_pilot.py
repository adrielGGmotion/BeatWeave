import unittest
import numpy as np
from render import curve, excerpt, controls, RATE
from train import expand, mixture

class PilotContract(unittest.TestCase):
    def test_bezier_handles_are_not_timed_knots(self):
        raw={'curves':[{'start':0,'end':1,'points':[{'x':0,'y':0},{'x':0,'y':1},{'x':1,'y':1}]}]}
        # x=t^2: graph x=.25 corresponds to parameter .5 and y=.75.
        self.assertAlmostEqual(curve(raw,np.array([.25]))[0],.75,places=8)

    def test_zero_width_changes_are_right_continuous(self):
        raw={'curves':[{'start':0,'end':1,'points':[{'x':0,'y':1},{'x':1,'y':1}]},
                       {'start':1,'end':1,'points':[{'x':0,'y':1},{'x':1,'y':0}]}]}
        np.testing.assert_array_equal(curve(raw,np.array([.5,.999,1.])),[1,1,0])

    def test_negative_source_offset_pads_without_advancing_recording(self):
        pcm=np.ones((100,2),dtype=np.float32)
        actual=excerpt(pcm,-5/RATE,10)
        np.testing.assert_array_equal(actual[:5],0)
        np.testing.assert_array_equal(actual[5:],1)

    def test_full_overlap_does_not_begin_fading_at_midpoint(self):
        flat={'curves':[{'start':0,'end':1,'points':[{'x':0,'y':1},{'x':1,'y':1}]}]}
        empty={'curves':[]};side={k:empty for k in ['low','mid','high','cutoff','resonance']};side['volume']=flat
        t=np.arange(-RATE,3*RATE)/RATE
        c=controls([{'outgoing':side}],[1],t,2,'outgoing')
        self.assertAlmostEqual(c['volume'][2*RATE],1,places=8)
        self.assertLess(c['volume'][-1],1e-8)

    def test_out_of_distribution_automation_stays_a_convex_mixture(self):
        model={'automation':{'centers':[[0,0],[1,1]],'scale':[1,1],'coefficients':[[1,0],[0,1]]}}
        w=mixture(model,np.array([1e6,1e6]))
        self.assertTrue(all(x>=0 for x in w));self.assertAlmostEqual(sum(w),1)

if __name__=='__main__':unittest.main()
