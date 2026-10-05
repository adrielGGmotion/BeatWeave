#!/usr/bin/env python3
"""Strong simple personal baseline: mean gain profile, with pair-level exclusion."""
import argparse,json,sys
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'handoff-automix'))
from train_handoff import error,save

def evaluate(work,out):
    user=[dict(np.load(work/f'user-{i}.npz')) for i in [1,2,3]];q=np.linspace(0,1,129)
    profiles=[np.stack([np.interp(q,np.linspace(0,1,len(d['gains'])),d['gains'][:,side]) for side in [0,1]],axis=1) for d in user]
    def predict(profile,n):return np.stack([np.interp(np.linspace(0,1,n),q,profile[:,side]) for side in [0,1]],axis=1)
    rows=[]
    for i,d in enumerate(user):
        mean=np.mean([g for j,g in enumerate(profiles) if j!=i],axis=0);g=predict(mean,len(d['x']))
        rows.append({'pair':i+1,'gain_mae':float(abs(g-d['gains'])[d['observable']].mean())})
    mean=np.mean(profiles,axis=0);external=[]
    for e in json.loads((out/'replay-manifest.json').read_text())['examples']:
        if e['split']=='train':continue
        d=dict(np.load(work/f"replay-{e['id']}.npz"));g=predict(mean,len(d['x']))
        external.append({'id':e['id'],'gain_mae':float(abs(g-d['gains'])[d['observable']].mean()),'spectral_mae_db':error(d['a'],d['b'],d['y'],g)})
    save(out/'profile-baseline-results.json',{'method':'mean of accepted envelopes on normalized time; leave-one-pair-out excludes that pair',
        'leave_one_pair_out':rows,'external_regression':external,'training_independent_preferences':3})
    save(out/'mean-profile-baseline.json',{'q':q.tolist(),'gains':mean.tolist()})

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('work',type=Path);p.add_argument('out',type=Path);a=p.parse_args();evaluate(a.work,a.out)
