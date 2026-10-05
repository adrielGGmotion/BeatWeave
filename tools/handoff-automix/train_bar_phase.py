#!/usr/bin/env python3
"""Four-beat bar phase from human bar annotations, conditional on a beat grid.

Audio/album groups and documented recording replicas stay in one fold. This
does not estimate tempo, validate arbitrary meters, or prove pop-song accuracy.
"""
import argparse
import hashlib
import json
import os
os.environ.setdefault('OMP_NUM_THREADS','2');os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import time
import numpy as np
from scipy.signal import stft
from sklearn.ensemble import HistGradientBoostingClassifier
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'corpus-automix'))
from common import export_booster,predict,write_json

RATE=11025;HOP=128
BANDS=[(35,100),(100,250),(250,600),(600,1500),(1500,3500),(3500,5000)]
FEATURES=[f'{kind}_band{b}_quarter{q}' for kind in ['energy','flux'] for b in range(6) for q in range(16)]

def audio_features(path):
    raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-ac','1','-ar',str(RATE),'-f','f32le','-'])
    x=np.frombuffer(raw,dtype='<f4');f,t,z=stft(x,fs=RATE,nperseg=1024,noverlap=1024-HOP)
    mag=np.abs(z);energy=[];flux=[]
    for lo,hi in BANDS:
        s=mag[(f>=lo)&(f<hi)]
        energy.append((s*s).mean(axis=0))
        flux.append(np.maximum(np.diff(s,prepend=s[:,:1],axis=1),0).mean(axis=0))
    return t,np.array(energy+flux)

def phase_features(times,channels,beats):
    """Four bar hypotheses, each pooling four bars of quarter-beat evidence."""
    if len(beats)<21:raise ValueError('Need 21 consecutive beats')
    active=(times>=beats[0])&(times<=beats[20])
    norm=np.maximum(channels[:,active].mean(axis=1),1e-12)
    result=[]
    for phase in range(4):
        beat_positions=phase+np.arange(4)[:,None]*4+np.arange(16)[None,:]/4
        probes=np.interp(beat_positions,np.arange(len(beats)),beats)
        row=[np.log1p(np.interp(probes,times,signal).mean(axis=0)/scale) for signal,scale in zip(channels,norm)]
        result.append(np.array(row).ravel())
    return np.array(result)

def prepare(archive,annotations,work):
    h=hashlib.md5()
    with archive.open('rb') as f:
        for c in iter(lambda:f.read(8*1024*1024),b''):h.update(c)
    if h.hexdigest()!='2872a3e52070bc342a4510a95e2fa0b8':raise ValueError('Ballroom archive checksum mismatch')
    audio=work/'ballroom-audio';audio.mkdir(exist_ok=True)
    tracks=[]
    with tarfile.open(archive) as tar:
        for m in tar:
            if not m.isfile() or not m.name.lower().endswith('.wav'):continue
            relative=Path(*Path(m.name).parts[1:])
            if '..' in relative.parts or relative.is_absolute():raise ValueError('Unsafe archive path')
            path=audio/relative;path.parent.mkdir(exist_ok=True)
            if not path.exists():path.write_bytes(tar.extractfile(m).read())
            annotation=annotations/(path.stem+'.beats')
            if not annotation.exists():continue
            beats=np.loadtxt(annotation)
            if int(np.max(beats[:,1]))!=4:continue
            tracks.append({'path':path,'relative':str(relative),'beats':beats})
    # Album grouping prevents samples from the same release crossing folds.
    parent={}
    def group(name):
        stem=Path(name).stem
        return stem.rsplit('-',1)[0] if stem.startswith('Albums-') else stem
    def find(k):
        parent.setdefault(k,k)
        if parent[k]!=k:parent[k]=find(parent[k])
        return parent[k]
    def union(a,b):parent[find(a)]=find(b)
    readme=(annotations/'README.md').read_text()
    for a,b in re.findall(r'(\S+\.wav)\s+matches\s+(\S+\.wav)',readme):union(group(a),group(b))
    by_hash={}
    for t in tracks:
        t['group']=group(t['relative']);t['sha256']=hashlib.sha256(t['path'].read_bytes()).hexdigest()
        if t['sha256'] in by_hash:union(t['group'],by_hash[t['sha256']])
        by_hash[t['sha256']]=t['group']
    rows=[];labels=[];queries=[];manifest=[]
    for number,track in enumerate(tracks):
        g=find(track['group']);v=int(hashlib.sha256(('bar-phase-v1:'+g).encode()).hexdigest()[:8],16)%100
        split='train' if v<70 else 'validation' if v<85 else 'test'
        t,c=audio_features(track['path']);beats=track['beats']
        if not np.all((np.diff(beats[:,1])%4)==1):continue
        for start in range(0,len(beats)-20,8):
            window=beats[start:start+21];target=int(np.where(window[:4,1]==1)[0][0])
            x=phase_features(t,c,window[:,0]);first=len(rows);rows.extend(x);labels.extend(np.arange(4)==target)
            queries.append({'recording':track['relative'],'group':g,'split':split,'first':first,'target_phase':target})
        manifest.append({'recording':track['relative'],'sha256':track['sha256'],'group':g,'split':split})
        if (number+1)%100==0:print('Bar features',number+1,'recordings',len(queries),'windows',flush=True)
    np.savez_compressed(work/'bar-examples.npz',x=np.array(rows,dtype=np.float32),y=np.array(labels))
    write_json(work/'bar-queries.json',queries);write_json(work/'ballroom-manifest.json',manifest)

def train(archive,annotations,work,out):
    started=time.monotonic();work.mkdir(parents=True,exist_ok=True);out.mkdir(parents=True,exist_ok=True)
    if not (work/'bar-examples.npz').exists():prepare(archive,annotations,work)
    d=np.load(work/'bar-examples.npz');x,y=d['x'],d['y'];queries=json.loads((work/'bar-queries.json').read_text())
    split=np.repeat([q['split'] for q in queries],4);mask=split=='train'
    model=HistGradientBoostingClassifier(max_iter=160,max_leaf_nodes=7,max_depth=4,learning_rate=.05,
        l2_regularization=5,early_stopping=False,random_state=20261006)
    model.fit(x[mask],y[mask],sample_weight=np.where(y[mask],3.,1.))
    exported=export_booster(model,FEATURES,'human-annotated-four-beat-bar-phase-v1')
    write_json(out/'bar-phase-model.json',exported)
    np.testing.assert_allclose(predict(exported,x[::17],True),model.predict_proba(x[::17])[:,1],atol=1e-10)
    scores=model.predict_proba(x)[:,1];results=[];summary={}
    for q in queries:
        idx=q['first'];s=scores[idx:idx+4];best=int(np.argmax(s));base=int(np.argmax(x[idx:idx+4,0]))
        results.append({**q,'predicted_phase':best,'correct':best==q['target_phase'],
            'baseline_correct':base==q['target_phase'],'scores':s.tolist(),'margin':float(np.sort(s)[-1]-np.sort(s)[-2])})
    for ss in ['train','validation','test']:
        r=[q for q in results if q['split']==ss]
        summary[ss]={'recordings':len({q['recording'] for q in r}),'album_or_recording_groups':len({q['group'] for q in r}),
            'windows':len(r),'phase_accuracy':float(np.mean([q['correct'] for q in r])),
            'strongest_low_energy_baseline_accuracy':float(np.mean([q['baseline_correct'] for q in r]))}
    manifest=json.loads((work/'ballroom-manifest.json').read_text())
    for a,b in [('train','validation'),('train','test'),('validation','test')]:
        assert not({q['group'] for q in manifest if q['split']==a}&{q['group'] for q in manifest if q['split']==b})
        assert not({q['sha256'] for q in manifest if q['split']==a}&{q['sha256'] for q in manifest if q['split']==b})
    # The acceptance threshold uses validation only and is frozen before test summaries.
    validation=[r for r in results if r['split']=='validation'];threshold=None
    for candidate in [.1,.2,.3,.4,.5,.6,.7,.8,.9]:
        selected=[r for r in validation if r['margin']>=candidate]
        if len(selected)>=20 and np.mean([r['correct'] for r in selected])>=.9:
            threshold=candidate;break
    report={'seed':20261006,'features':len(FEATURES),'candidate_examples':len(x),'splits':summary,
        'validation_threshold_for_90_percent':threshold,'export_parity':True,'seconds':time.monotonic()-started,
        'source':'Ballroom audio and CPJKU manually corrected beat/bar annotations',
        'limitations':['Conditional on annotated input beats in evaluation; not end-to-end tracking.',
            'Four-beat bars only; Ballroom dance repertoire differs from the user pop songs.',
            'Album groups, documented replicas and byte-identical sources share folds. Undocumented alternate masters may remain.',
            'Model scores are not calibrated probabilities; insufficient validation support disables automatic bar-phase correction.']}
    write_json(out/'bar-phase-results.json',report);write_json(out/'bar-phase-predictions.json',results)
    write_json(out/'ballroom-manifest.json',manifest)
    print(json.dumps(report),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('archive',type=Path);p.add_argument('annotations',type=Path);p.add_argument('work',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();train(a.archive,a.annotations,a.work,a.out)
