#!/usr/bin/env python3
"""Learn beat-phase scoring from recording-disjoint, published audio/clock examples."""
import argparse
import json
import os
os.environ.setdefault('OMP_NUM_THREADS','2');os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
from pathlib import Path
import subprocess
import time
import numpy as np
from scipy.signal import stft
from sklearn.ensemble import HistGradientBoostingClassifier
from common import SEED,export_booster,predict,write_json

RATE=11025;HOP=64
BANDS=[(35,160),(160,400),(400,1500),(1500,5000)]
OFFSETS=np.array([-.12,-.08,-.04,0,.04,.08,.12,.20,.32,.50,.68,.80])
FEATURES=[f'onset_band{b}_beat_offset{o:g}' for b in range(4) for o in OFFSETS]

def onset_features(audio):
    f,t,z=stft(audio,fs=RATE,nperseg=512,noverlap=512-HOP,boundary='zeros')
    mag=np.abs(z)
    bands=np.array([np.maximum(np.diff(mag[(f>=lo)&(f<hi)],axis=1),0).sum(axis=0) for lo,hi in BANDS])
    # 12-ms triangular smoothing provides tolerance for spectral-window placement.
    bands=np.stack([np.convolve(v,[.25,.5,.25],mode='same') for v in bands])
    return t[1:],bands

def candidate_features(times,onsets,cue,period,span,shifts):
    centers=cue+np.arange(max(8,int(span/period)))*period
    outputs=[]
    active=(times>=centers[0]-period)&(times<=centers[-1]+period)
    norms=np.maximum(np.mean(onsets[:,active],axis=1),1e-7)
    for shift in shifts:
        probe=centers[:,None]+shift+OFFSETS[None,:]*period
        row=[]
        for band,norm in zip(onsets,norms):
            row.extend(np.mean(np.interp(probe,times,band,left=0,right=0),axis=0)/norm)
        outputs.append(row)
    return np.clip(np.array(outputs),0,30)

def load_audio(path):
    raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-ac','1','-ar',str(RATE),'-f','f32le','-'])
    return np.frombuffer(raw,dtype='<f4')

def build(root):
    corpus=json.loads((root/'audio-corpus.json').read_text());rows=[];labels=[];groups=[];splits=[];windows=[]
    grid=np.arange(-32,33)/64 # includes a full beat of possible phase, 7-10 ms resolution.
    for n,track in enumerate(corpus['tracks']):
        cache=root/'features'/(track['id']+'.npz');cache.parent.mkdir(exist_ok=True)
        if cache.exists():
            d=np.load(cache);times=d['times'];onsets=d['onsets']
        else:
            times,onsets=onset_features(load_audio(track['filename']));np.savez_compressed(cache,times=times,onsets=onsets)
        beats=np.array(track['beats']);cut=track['labels']['cutpoint'];end=track['labels']['duration']
        for region_a,region_b in [(max(2,track['labels']['cueinstart']),cut-1),(cut+1,end-2)]:
            valid=beats[(beats>=region_a)&(beats<=region_b)]
            for start in range(0,max(0,len(valid)-16),8):
                window=valid[start:start+17];period=float(np.median(np.diff(window)))
                if not .25<=period<=1.0:continue
                ideal=window[0]+np.arange(len(window))*period
                if np.max(np.abs(ideal-window))>.045:continue
                shifts=grid*period;x=candidate_features(times,onsets,window[0],period,period*16,shifts)
                first=len(rows);rows.extend(x);labels.extend((np.abs(shifts)<=.013).astype(int))
                groups.extend([track['id']]*len(x));splits.extend([track['partition']]*len(x))
                windows.append({'track':track['id'],'partition':track['partition'],'first':first,'count':len(x),
                                'period':period,'true_offset':0.,'shifts':shifts.tolist()})
        if (n+1)%25==0:print('Phase features:',n+1,'recordings,',len(windows),'windows',flush=True)
    np.savez_compressed(root/'phase-examples.npz',x=np.array(rows,dtype=np.float32),y=np.array(labels),
                        splits=np.array(splits),groups=np.array(groups))
    write_json(root/'phase-windows.json',windows)

def train(root,out):
    start=time.monotonic();out.mkdir(parents=True,exist_ok=True)
    if not (root/'phase-examples.npz').exists():build(root)
    d=np.load(root/'phase-examples.npz');x=d['x'];y=d['y'];splits=d['splits'];train=splits=='train'
    windows=json.loads((root/'phase-windows.json').read_text())
    model=HistGradientBoostingClassifier(max_iter=160,max_leaf_nodes=15,max_depth=5,learning_rate=.07,
        l2_regularization=2,early_stopping=False,max_bins=128,random_state=SEED)
    weights=np.where(y[train]>0,.5/max(y[train].mean(),1e-8),.5/max(1-y[train].mean(),1e-8))
    model.fit(x[train],y[train],sample_weight=weights)
    exported=export_booster(model,FEATURES,'beat-phase-ranking-v1');write_json(out/'phase-model.json',exported)
    np.testing.assert_allclose(predict(exported,x[::max(1,len(x)//100)],True),model.predict_proba(x[::max(1,len(x)//100)])[:,1],atol=1e-10)
    scores=model.predict_proba(x)[:,1];reports={};predictions=[]
    for split in ['train','validation','test']:
        errors=[];old_errors=[];ambiguous=[]
        for w in windows:
            if w['partition']!=split:continue
            a=w['first'];b=a+w['count'];offsets=np.array(w['shifts']);prob=scores[a:b]
            selected=int(np.argmax(prob));baseline=int(np.argmax(x[a:b,3])) # low-band pulse at zero.
            error=abs(offsets[selected]);old=abs(offsets[baseline]);errors.append(error);old_errors.append(old)
            far=np.abs(offsets-offsets[selected])>.1*w['period']
            margin=float(prob[selected]-np.max(prob[far]))
            predictions.append({**w,'predicted_shift':float(offsets[selected]),'baseline_shift':float(offsets[baseline]),'margin':margin})
        errors=np.array(errors);old_errors=np.array(old_errors)
        reports[split]={'recordings':len(set(d['groups'][splits==split])),'windows':len(errors),
            'median_error_ms':float(np.median(errors)*1000),'p90_error_ms':float(np.quantile(errors,.9)*1000),
            'within_30ms_fraction':float(np.mean(errors<=.030)),
            'low_band_peak_baseline_median_ms':float(np.median(old_errors)*1000),
            'low_band_peak_baseline_within_30ms_fraction':float(np.mean(old_errors<=.030))}
    ids={s:set(d['groups'][splits==s]) for s in ['train','validation','test']}
    assert not(ids['train']&ids['test'] or ids['train']&ids['validation'] or ids['validation']&ids['test'])
    report={'seed':SEED,'features':len(FEATURES),'candidate_examples':len(x),'positive_examples':int(y.sum()),
        'seconds':time.monotonic()-start,'splits':reports,'export_parity':True,
        'labels':'Published automatic IRCAM beat timestamps on UnmixDB excerpts; phase perturbations are generated.',
        'limitations':'Measures recovery of those clocks on unseen recordings, not human-rated transition quality or independent manual beat truth.'}
    write_json(out/'phase-training-results.json',report);write_json(out/'phase-predictions.json',predictions)
    print(json.dumps(report),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('out',type=Path);a=p.parse_args();train(a.root,a.out)
