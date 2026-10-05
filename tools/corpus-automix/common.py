"""Portable inference helpers shared by training, evaluation and the listening renderer."""
import hashlib
import json
from pathlib import Path
import numpy as np
from scipy.special import expit

SEED=20261005

def is_development_recording(title):
    """Exclude the six review works, including recognizable remix/version titles."""
    text=title.casefold().replace('_',' ')
    return any(all(token in text for token in tokens) for tokens in [
        ('madison beer','yes baby'),('darude','sandstorm'),('jennie','mantra'),
        ('subway surfers',),('ellie goulding','love me like you do'),('chainsmokers','closer')])

def partition(key):
    v=int(hashlib.sha256(('beatweave-corpus-v1:'+key).encode()).hexdigest()[:8],16)%100
    return 'train' if v<70 else ('validation' if v<85 else 'test')

def export_booster(model,features,kind):
    trees=[]
    for iteration in model._predictors:
        if len(iteration)!=1:raise ValueError('Binary models only')
        n=iteration[0].nodes
        if n['is_categorical'].any():raise ValueError('Numeric features only')
        trees.append([{k:(bool(v[k]) if k in ['is_leaf','missing_go_to_left'] else
                          int(v[k]) if k in ['feature_idx','left','right'] else float(v[k]))
                       for k in ['value','feature_idx','num_threshold','left','right','is_leaf','missing_go_to_left']}
                      for v in n])
    return {'schema':1,'kind':kind,'features':features,'baseline':float(model._baseline_prediction.ravel()[0]),'trees':trees}

def predict(model,x,probability=False):
    x=np.atleast_2d(x)
    if x.shape[1]!=len(model['features']):raise ValueError('Feature count mismatch')
    score=np.full(len(x),model['baseline'])
    for nodes in model['trees']:
        values=np.array([n['value'] for n in nodes]);leaves=np.array([n['is_leaf'] for n in nodes])
        features=np.array([n['feature_idx'] for n in nodes]);thresholds=np.array([n['num_threshold'] for n in nodes])
        lefts=np.array([n['left'] for n in nodes]);rights=np.array([n['right'] for n in nodes])
        missing=np.array([n['missing_go_to_left'] for n in nodes])
        indices=np.zeros(len(x),dtype=int)
        active=np.arange(len(x))
        while len(active):
            idx=indices[active];leaf=leaves[idx]
            done=active[leaf];score[done]+=values[indices[done]]
            active=active[~leaf]
            if not len(active):break
            idx=indices[active];value=x[active,features[idx]]
            left=np.where(np.isnan(value),missing[idx],value<=thresholds[idx])
            indices[active]=np.where(left,lefts[idx],rights[idx])
    return expit(score) if probability else score

STYLE_FEATURES=['outgoing_start_fraction','incoming_start_fraction','outgoing_end_fraction',
    'incoming_end_fraction','log2_bars','overlap_seconds_over_60','outgoing_duration_over_600',
    'incoming_duration_over_600','outgoing_bpm_over_160','incoming_bpm_over_160','relative_speed']

def style_features(a,b,bars,da,db,ba,bb):
    duration=bars*240/ba;speed=ba/bb
    return np.array([a/da,b/db,(a+duration)/da,(b+duration*speed)/db,np.log2(bars),duration/60,
                     da/600,db/600,ba/160,bb/160,speed],dtype=float)

def write_json(path,data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,indent=2,allow_nan=False)+'\n')
