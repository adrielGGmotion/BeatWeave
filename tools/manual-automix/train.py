#!/usr/bin/env python3
"""Small, explicit personal-preference pilot. Train only on supplied positive transitions.

Alternative candidates are assumed less preferred according to source-time distance, not
human-rated negatives. Held-out evaluation excludes a complete pair and both its tracks.
"""
import argparse
import hashlib
import json
import os
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
os.environ.setdefault('OMP_NUM_THREADS','2')
from pathlib import Path
import subprocess
import time
import numpy as np
from scipy.optimize import minimize
from scipy.special import logsumexp

FEATURES=['outgoing_position','incoming_position','log2_bars_over_5','duration_over_60',
          'outgoing_start_change','outgoing_end_change','incoming_start_change',
          'incoming_end_change','incoming_build','overlap_level_balance',
          'global_affinity','old_outgoing_score','old_incoming_score']

def expand(z):
    return np.column_stack([z]+[(z[:,i]*z[:,j])[:,None] for i in range(z.shape[1]) for j in range(i,z.shape[1])])

def nearest(c,r):
    bar=r['duration']/r['bars']
    cost=((c[:,0]-r['a'])/bar)**2+((c[:,1]-r['b'])/(bar*r['speed']))**2
    # Train the requested duration class; do not trade a different length for closer cues.
    cost[c[:,4]!=r['bars']]=np.inf
    return int(np.argmin(cost))

def fit(candidates, refs, indices):
    raw=np.vstack([candidates[i][:,6:] for i in indices])
    mean=raw.mean(axis=0); scale=np.maximum(raw.std(axis=0),0.05)
    xs=[expand((candidates[i][:,6:]-mean)/scale) for i in indices]
    positives=[nearest(candidates[i],refs[i]) for i in indices]
    dim=xs[0].shape[1]
    # Fixed before evaluation; no hyperparameter search using held-out outcomes.
    regularization=0.03
    def objective(w):
        loss=regularization*np.dot(w,w)/2; grad=regularization*w.copy()
        for x,p in zip(xs,positives):
            s=x@w; norm=logsumexp(s)
            loss+=(norm-s[p])/len(xs)
            grad+=(x.T@np.exp(s-norm)-x[p])/len(xs)
        return loss,grad
    start=time.monotonic()
    opt=minimize(objective,np.zeros(dim),jac=True,method='L-BFGS-B',options={'maxiter':250,'ftol':1e-10,'gtol':1e-6})
    centers=np.stack([candidates[i][nearest(candidates[i],refs[i]),6:] for i in indices])
    automation_scale=np.maximum(centers.std(axis=0),0.1)
    distance=((centers[:,None,:]-centers[None,:,:])/automation_scale)**2
    kernel=np.exp(-distance.mean(axis=2)/2)
    coefficients=np.linalg.solve(kernel+1e-3*np.eye(len(indices)),np.eye(len(indices)))
    return {'schema':1,'model_id':'beatweave-manual-pilot-v1','feature_names':FEATURES,
            'mean':mean.tolist(),'scale':scale.tolist(),'weights':opt.x.tolist(),
            'feature_expansion':'standardized linear terms, then z[i]*z[j] for i <= j',
            'regularization':regularization,'optimization':{'success':bool(opt.success),'message':str(opt.message),
                'iterations':int(opt.nit),'loss':float(opt.fun),'seconds':time.monotonic()-start},
            'automation':{'kind':'rbf-kernel-ridge-convex-envelope-mixture','ridge':0.001,
                'centers':centers.tolist(),'scale':automation_scale.tolist(),'coefficients':coefficients.tolist(),
                'templates':[refs[i]['automation'] for i in indices]},
            'training_pair_indices':[i+1 for i in indices],
            'limitations':['Three positive pairs; no human-rated negative examples.',
                'No chorus or vocal classifier. Reference grids are provider inputs, not verified beat truth.',
                'Alternative cue labels are proximity-to-reference proxies, not perceptual-quality judgments.',
                'Automation DSP mapping is explicitly our own; not verified Spotify audio behavior.']}

def predict(model,c):
    scores=expand((c[:,6:]-np.array(model['mean']))/np.array(model['scale']))@np.array(model['weights'])
    return int(np.argmax(scores)),scores

def mixture(model,x):
    a=model['automation']; centers=np.array(a['centers'])
    k=np.exp(-np.mean(((centers-x)/np.array(a['scale']))**2,axis=1)/2)
    weights=np.maximum(k@np.array(a['coefficients']),0)
    # A far out-of-distribution sample may underflow. Pick nearest training center explicitly.
    if weights.sum()<1e-15:
        weights=np.zeros(len(centers));weights[np.argmin(np.mean(((centers-x)/np.array(a['scale']))**2,axis=1))]=1
    return (weights/weights.sum()).tolist()

def plan(row):
    return dict(zip(['a','b','duration','speed','bars','baseline_score'],map(float,row[:6])))

def metrics(row,r):
    return {'outgoing_cue_error_seconds':abs(float(row[0])-r['a']),
            'incoming_cue_error_seconds':abs(float(row[1])-r['b']),
            'duration_error_seconds':abs(float(row[2])-r['duration']),
            'bars_match':int(row[4])==r['bars']}

def train(work,output,labels=None):
    output.mkdir(parents=True,exist_ok=True)
    reference=json.loads((work/'references.json').read_text()); refs=reference['pairs']
    if labels is not None:
        edits=json.loads(labels.read_text())
        if edits['source_sha256']!=reference['source_sha256'] or len(edits['pairs'])!=len(refs):
            raise ValueError('Labels belong to a different source capture')
        for old,new in zip(refs,edits['pairs']):
            if old['index']!=new['index']:raise ValueError('Pair order changed')
            if not (0<=new['a']<old['sources'][0]['decoded_duration_seconds'] and
                    -10<=new['b']<old['sources'][1]['decoded_duration_seconds'] and
                    0<new['duration']<=180 and .8<=new['speed']<=1.25 and new['bars'] in [2,4,8,16,32]):
                raise ValueError('Invalid reference timing')
            old.update({k:new[k] for k in ['a','b','duration','speed','bars','automation']})
        (work/'references.json').write_text(json.dumps(reference,indent=2)+'\n')
    c=[np.loadtxt(work/f'pair-{i+1}-candidates.csv',delimiter=',',skiprows=1) for i in range(len(refs))]
    model=fit(c,refs,list(range(len(refs))))
    baseline_sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=Path(__file__).resolve().parents[2],text=True).strip()
    model['baseline_commit']=baseline_sha
    (output/'personal-transition-model.json').write_text(json.dumps(model,indent=2)+'\n')
    (output/'model-runtime.tsv').write_text('\n'.join(','.join(map(str,model[k])) for k in ['mean','scale','weights'])+'\n')
    parity=[]
    for rows in c:
        for row in rows[np.linspace(0,len(rows)-1,12).astype(int)]:
            score=(expand(((row[None,6:]-np.array(model['mean']))/np.array(model['scale'])))@np.array(model['weights'])).item()
            parity.append(','.join(map(str,[*row[6:],score])))
    (work/'score-parity.csv').write_text('\n'.join(parity)+'\n')
    report={'schema':1,'baseline_commit':baseline_sha,'reference_sha256':reference['source_sha256'],
            'comparison':'Frozen BeatWeave musical ranking vs trained ranking, same supplied grids and candidate set.',
            'production_detector':'Unchanged small0; separate production-baseline files record acceptance/decline.',
            'training':model['optimization'],'pairs':[]}
    for i,r in enumerate(refs):
        chosen,scores=predict(model,c[i]); old=int(np.argmax(c[i][:,5])); target=nearest(c[i],r)
        remaining=[j for j in range(len(refs)) if i!=j]
        fold=fit(c,refs,remaining); held,_=predict(fold,c[i])
        (output/f'fold-excluding-pair-{i+1}.json').write_text(json.dumps(fold,indent=2)+'\n')
        item={'pair':i+1,'title':r['title'],'candidate_count':len(c[i]),
              'reference':{k:r[k] for k in ['a','b','duration','speed','bars']},
              'nearest_available':plan(c[i][target]),'candidate_grid_floor':metrics(c[i][target],r),
              'old':plan(c[i][old]),'learned':plan(c[i][chosen]),'held_out':plan(c[i][held]),
              'metrics':{'old':metrics(c[i][old],r),'learned_training_fit':metrics(c[i][chosen],r),'held_out':metrics(c[i][held],r)},
              'preferred_candidate_rank':int((scores>scores[target]).sum()+1),
              'automation_mixture':mixture(model,c[i][chosen,6:]),
              'held_out_automation_mixture':mixture(fold,c[i][held,6:]),
              'fold_optimization':fold['optimization']}
        report['pairs'].append(item)
        print(json.dumps({k:item[k] for k in ['pair','preferred_candidate_rank','metrics']}),flush=True)
    (output/'training-results.json').write_text(json.dumps(report,indent=2)+'\n')
    # Preserve editable labels and exact curves; omit private absolute source paths from output.
    editable=json.loads(json.dumps(reference))
    for r in editable['pairs']:
        for s in r['sources']:s['filename']=Path(s.pop('path')).name
    (output/'editable-reference-labels.json').write_text(json.dumps(editable,indent=2)+'\n')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('work',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--labels',type=Path,help='Edited copy of editable-reference-labels.json')
    a=p.parse_args();train(a.work,a.output,a.labels)
