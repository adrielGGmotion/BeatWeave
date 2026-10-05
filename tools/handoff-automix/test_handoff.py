"""Regressions for fader units, unobservable labels and actual warped attacks."""
import tempfile
from pathlib import Path
import subprocess
import unittest
import numpy as np
from audio_features import isotonic,constrained_gains,source_features
from train_handoff import infer_teacher,gain_observability,error
from render_review import bounded_envelope,ROOT,RATE

class GainTests(unittest.TestCase):
    def test_power_teacher_returns_amplitude(self):
        rng=np.random.default_rng(31);a=rng.uniform(.01,1,(32,32));b=rng.uniform(.01,1,(32,32))
        expected=np.tile([.8,.35],(32,1));mixture=a*.8**2+b*.35**2
        actual,_=infer_teacher(a,b,mixture)
        np.testing.assert_allclose(actual,expected,atol=.002)
        self.assertLess(error(a,b,mixture,actual),.01)
    def test_zero_padded_source_is_not_a_gain_label(self):
        a=np.ones((20,32));a[:5]=0;b=np.ones((20,32))
        mask=gain_observability(a,b)
        self.assertFalse(mask[:5,0].any());self.assertTrue(mask[5:,0].all());self.assertTrue(mask[:,1].all())
    def test_independent_gains_can_hold_both_decks(self):
        g=constrained_gains(np.array([[1.,.8],[1.,1.],[.8,1.]]))
        self.assertGreater(float(g[1].sum()),1.9)
        self.assertTrue(np.all(np.diff(g[:,0])<=0));self.assertTrue(np.all(np.diff(g[:,1])>=0))
    def test_pooled_violations_have_known_solution(self):
        np.testing.assert_allclose(isotonic([0,3,1,4]),[0,2,2,4])
    def test_source_features_are_finite_with_silent_deck(self):
        a=np.ones((64,32));b=np.zeros_like(a)
        x=source_features(a,b,np.arange(64)/4)
        self.assertEqual(x.shape,(64,87));self.assertTrue(np.isfinite(x).all())
    def test_boundary_joins_do_not_jump_at_mix_edges(self):
        t=np.arange(-1,6,1/48000);g=bounded_envelope([0,4],np.array([[.7,.2],[.3,.8]]),t,4)
        np.testing.assert_allclose(g[t<0],np.tile([1.,0.],(sum(t<0),1)))
        np.testing.assert_allclose(g[t>=4],np.tile([0.,1.],(sum(t>=4),1)))
        self.assertLess(np.abs(np.diff(g,axis=0)).max(),.0001)

class WarpTests(unittest.TestCase):
    def test_independent_synthetic_attacks_follow_requested_map(self):
        binary=ROOT/'build/handoff-training/warp-r3'
        if not binary.exists():self.fail('Compile warp-r3 before running native regression')
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp);count=8*RATE;x=np.zeros((count,2),dtype='<f4')
            source=np.array([1.,2.,3.,4.,5.,6.]);target=np.array([1.1,2.05,3.2,4.1,5.15,6.2])
            rng=np.random.default_rng(99)
            for s in source:
                start=round(s*RATE);burst=rng.normal(0,.3,1200)*np.exp(-np.arange(1200)/150)
                x[start:start+1200]=burst[:,None]
            x.tofile(p/'in.f32');(p/'map.txt').write_text('0 0\n'+''.join(f'{round(s*RATE)} {round(t*RATE)}\n' for s,t in zip(source,target))+f'{count-1} {count-1}\n')
            subprocess.run([str(binary),str(p/'in.f32'),str(p/'out.f32'),str(count),str(p/'map.txt')],check=True)
            y=np.fromfile(p/'out.f32',dtype='<f4').reshape(-1,2)
            self.assertLessEqual(abs(len(y)-count),4)
            errors=[]
            for expected in target:
                lo=round((expected-.15)*RATE);hi=round((expected+.15)*RATE)
                peak=lo+int(np.argmax(np.abs(y[lo:hi,0])));errors.append(abs(peak/RATE-expected))
            self.assertLess(max(errors),.03,errors)
            self.assertEqual(float(np.max(abs(y[:,0]-y[:,1]))),0.)
            print('Synthetic mapped attack maximum error seconds:',max(errors))
    def test_reversed_map_is_rejected(self):
        binary=ROOT/'build/handoff-training/warp-r3'
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp);np.zeros((48000,2),dtype='<f4').tofile(p/'in.f32')
            (p/'map.txt').write_text('0 0\n24000 30000\n30000 25000\n')
            r=subprocess.run([str(binary),str(p/'in.f32'),str(p/'out.f32'),'48000',str(p/'map.txt')],capture_output=True)
            self.assertNotEqual(r.returncode,0);self.assertFalse((p/'out.f32').exists())

if __name__=='__main__':unittest.main()
