import copy
import unittest
import numpy as np
from full_span_faders import objective,pack,rates_to_gains,teacher_rates

class FullSpanTests(unittest.TestCase):
    def test_extreme_rates_still_fade_through_entire_overlap(self):
        for n in [8,41,129]:
            rates=np.ones((n,2));rates[:n//2]=[.5,1.5];rates[n//2:]=[1.5,.5]
            g=rates_to_gains(rates)
            np.testing.assert_array_equal(g[0],[1,0]);np.testing.assert_array_equal(g[-1],[0,1])
            self.assertTrue((np.diff(g[:,0])<0).all());self.assertTrue((np.diff(g[:,1])>0).all())
            self.assertTrue(((g>=0)&(g<=1)).all())
            self.assertLess(g[n//4,0],.995);self.assertGreater(g[n//4,1],.05)
            self.assertGreater(g[-2,0],0);self.assertLess(g[-2,1],1)

    def test_rate_gradient_matches_finite_differences(self):
        rng=np.random.default_rng(730)
        base={'mean':[0,0,0],'scale':[1,1,1],'layers':[
            {'weight':rng.normal(0,.1,(4,3)).tolist(),'bias':[.5]*4},
            {'weight':rng.normal(0,.1,(2,4)).tolist(),'bias':[0.,0.]}]}
        theta=pack(base)+rng.normal(0,.01,len(pack(base)));x=rng.normal(size=(7,3));y=rng.uniform(.5,1.5,(7,2));weights=np.ones((7,2))/14
        loss,gradient=objective(theta,base,x,y,weights)
        for _ in range(5):
            v=rng.normal(size=len(theta));v/=np.linalg.norm(v);eps=1e-6
            numerical=(objective(theta+eps*v,base,x,y,weights)[0]-objective(theta-eps*v,base,x,y,weights)[0])/(2*eps)
            self.assertAlmostEqual(float(gradient@v),numerical,places=7)

    def test_unobservable_teacher_values_do_not_affect_targets(self):
        q=np.linspace(0,1,101);g=np.c_[1-q,q];mask=np.ones_like(g,dtype=bool);mask[:15,1]=False
        d={'gains':g.copy(),'observable':mask};before,_=teacher_rates(d)
        d['gains'][:15,1]=999;after,_=teacher_rates(d)
        np.testing.assert_array_equal(before,after)

    def test_invalid_rates_rejected(self):
        with self.assertRaises(ValueError):rates_to_gains(np.array([[1,np.nan],[1,1]]))

if __name__=='__main__':unittest.main()
