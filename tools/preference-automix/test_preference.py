import json,sys,unittest
from pathlib import Path
import numpy as np
from train_faders import objective,pack,predict,unpack

class OptimizerTests(unittest.TestCase):
    def setUp(self):
        rng=np.random.default_rng(30)
        self.model={'mean':[0]*5,'scale':[1]*5,'layers':[{'weight':rng.normal(0,.3,(4,5)).tolist(),'bias':[.13,.25,.19,.3]},{'weight':rng.normal(0,.3,(2,4)).tolist(),'bias':[.2,-.1]}]}
        self.x=rng.normal(size=(9,5));self.y=rng.uniform(.2,1.1,(9,2));self.weights=np.ones((9,2))/18
    def test_analytic_gradient_against_finite_difference(self):
        theta=pack(self.model);loss,grad=objective(theta,self.model,self.x,self.y,self.weights)
        for i in range(len(theta)):
            plus=theta.copy();minus=theta.copy();plus[i]+=1e-6;minus[i]-=1e-6
            numerical=(objective(plus,self.model,self.x,self.y,self.weights)[0]-objective(minus,self.model,self.x,self.y,self.weights)[0])/2e-6
            self.assertAlmostEqual(grad[i],numerical,places=6)
    def test_pack_roundtrip_preserves_predictions(self):
        rebuilt=dict(self.model,layers=[{'weight':w.tolist(),'bias':b.tolist()} for w,b in unpack(pack(self.model),self.model)])
        np.testing.assert_array_equal(predict(rebuilt,self.x),predict(self.model,self.x))
    def test_masked_target_cannot_affect_loss_or_gradient(self):
        weights=self.weights.copy();weights[2,1]=0;y=self.y.copy();y[2,1]=999
        a=objective(pack(self.model),self.model,self.x,self.y,weights);b=objective(pack(self.model),self.model,self.x,y,weights)
        self.assertEqual(a[0],b[0]);np.testing.assert_array_equal(a[1],b[1])


class FrozenProtocolTests(unittest.TestCase):
    def test_modified_checkpoint_is_rejected(self):
        import hashlib,tempfile
        from run_auto import verify_release
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp);(p/'model.json').write_text('{}')
            digest=hashlib.sha256((p/'model.json').read_bytes()).hexdigest()
            (p/'freeze.json').write_text(json.dumps({'artifact_sha256':{'model.json':digest},'inference_code_sha256':{}}))
            verify_release(p)
            (p/'model.json').write_text('{"changed":true}')
            with self.assertRaisesRegex(ValueError,'Frozen artifact changed'):verify_release(p)
    def test_cli_has_no_manual_offset_override(self):
        import subprocess
        script=Path(__file__).with_name('run_auto.py')
        result=subprocess.run([sys.executable,str(script),'a.wav','b.wav','--release','release','--out','out','--offset','.1'],capture_output=True,text=True)
        self.assertEqual(result.returncode,2);self.assertIn('unrecognized arguments',result.stderr)
    def test_playback_joins_are_continuous_and_reach_both_endpoints(self):
        from run_auto import envelope
        rate=48000;t=np.arange(6*rate)/rate-1;x=np.zeros((len(t),2),dtype=np.float32)
        model={'mean':[0]*87,'scale':[1]*87,'layers':[{'weight':np.zeros((2,87)).tolist(),'bias':[0,0]}]}
        g,_=envelope(model,x,x,t,4,.0175)
        np.testing.assert_array_equal(g[0],[1,0]);np.testing.assert_array_equal(g[-1],[0,1])
        self.assertLess(np.max(abs(np.diff(g,axis=0))),.003)
        self.assertTrue(np.all(np.diff(g[:,0])<=1e-10));self.assertTrue(np.all(np.diff(g[:,1])>=-1e-10))

if __name__=='__main__':unittest.main()
