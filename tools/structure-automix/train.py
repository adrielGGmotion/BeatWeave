#!/usr/bin/env python3
"""Bounded validation-selected training; test artists are scored only after freeze."""
import argparse
import json
import os
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
os.environ.setdefault('OMP_NUM_THREADS','2')
from pathlib import Path
import shutil
import sys
import time
import numpy as np
from scipy.optimize import linear_sum_assignment
from scipy.signal import find_peaks
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.metrics import f1_score,precision_score,recall_score,average_precision_score
import sklearn,scipy
from features import NAMES,STEP,sha
from prepare_rwc import save,SEED
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'corpus-automix'))
from common import export_booster,predict


def peaks(times,score,threshold):
    indices,_=find_peaks(score,height=threshold,distance=round(4/STEP))
    return times[indices]


def events(reference,estimated,tolerance=3.):
    matches=0
    if len(reference) and len(estimated):
        distance=abs(reference[:,None]-estimated)
        cost=np.where(distance<=tolerance,distance/1000,1000.)
        a,b=linear_sum_assignment(cost);matches=int(np.sum(distance[a,b]<=tolerance))
    p=matches/len(estimated) if len(estimated) else 0.
    r=matches/len(reference) if len(reference) else 0.
    return dict(precision=p,recall=r,f1=2*p*r/(p+r) if p+r else 0.,
        matches=matches,predicted=len(estimated),reference=len(reference))


def assess(data,scores,task,threshold):
    rows=[]
    for d,s in zip(data,scores):
        if task=='chorus':
            y=d[task];yp=s>=threshold
            m=dict(f1=float(f1_score(y,yp,zero_division=0)),precision=float(precision_score(y,yp,zero_division=0)),
                recall=float(recall_score(y,yp,zero_division=0)),average_precision=float(average_precision_score(y,s)))
        else:
            m=events(d['boundaries'],peaks(d['times'],s,threshold))
            m['frame_average_precision']=float(average_precision_score(d[task],s))
        rows.append(dict(id=d['id'],artist=d['artist'],**m))
    return dict(songs=len(rows),macro_f1=float(np.mean([x['f1'] for x in rows])),
        macro_precision=float(np.mean([x['precision'] for x in rows])),
        macro_recall=float(np.mean([x['recall'] for x in rows])),per_song=rows)


def select_threshold(data,scores,task):
    values=np.linspace(.1,.9,17)
    results=[assess(data,scores,task,float(v)) for v in values]
    i=max(range(len(values)),key=lambda i:(results[i]['macro_f1'],-abs(values[i]-.5)))
    return float(values[i]),results[i]


def weights(data,task):
    result=[]
    for d in data:
        y=d[task];result.append(np.where(y==1,.5/np.sum(y==1),.5/np.sum(y==0)))
    result=np.concatenate(result);return result*len(result)/result.sum()


def bootstrap_difference(after,before):
    # Resample held-out artists, retaining all songs of an artist together.
    a={r['id']:r for r in after['per_song']};b={r['id']:r for r in before['per_song']}
    groups=sorted({v['artist'] for v in a.values()});rng=np.random.default_rng(SEED+2)
    differences={g:[a[k]['f1']-b[k]['f1'] for k in a if a[k]['artist']==g] for g in groups}
    estimates=[np.mean([v for g in rng.choice(groups,len(groups),replace=True) for v in differences[g]]) for _ in range(1500)]
    return dict(artists=len(groups),mean_difference=after['macro_f1']-before['macro_f1'],
        percentile_95_interval=np.quantile(estimates,[.025,.975]).tolist(),resampling='held-out artist groups')


def train(root):
    protocol=json.loads((root/'protocol.json').read_text())
    manifest=json.loads((root/'manifest.json').read_text());assert len(manifest)==100
    assert not (root/'selection.json').exists(),'An evaluated selection cannot be overwritten'
    model_dir=root/'models';model_dir.mkdir()
    data={s:[] for s in ['train','validation','test']}
    for m in manifest:
        assert sha(root/'features'/f'{m["id"]}.npz')==m['feature_sha256']
        # Test feature arrays are deliberately not opened during model selection.
        if m['partition']=='test':continue
        with np.load(root/'features'/f'{m["id"]}.npz') as file:d={k:file[k] for k in file.files}
        d.update(id=m['id'],artist=m['artist']);data[m['partition']].append(d)
    x=np.concatenate([d['x'] for d in data['train']]);history=[];selection={}
    for task in ['chorus','boundary']:
        y=np.concatenate([d[task] for d in data['train']]);w=weights(data['train'],task)
        selection[task]={}
        for family,columns in [('audio',list(range(len(NAMES)))),('position',[NAMES.index('position'),NAMES.index('duration_over_600')])]:
            candidates=[]
            for i,config in enumerate(protocol['models']):
                start=time.monotonic()
                estimator=HistGradientBoostingClassifier(max_iter=config['iterations'],max_leaf_nodes=config['leaves'],
                    learning_rate=config['learning_rate'],l2_regularization=config['l2'],min_samples_leaf=40,
                    max_bins=128,early_stopping=False,random_state=SEED)
                estimator.fit(x[:,columns],y,sample_weight=w)
                vs=[estimator.predict_proba(d['x'][:,columns])[:,1] for d in data['validation']]
                threshold,metric=select_threshold(data['validation'],vs,task)
                name=f'{task}-{family}-{i}'
                exported=export_booster(estimator,[NAMES[c] for c in columns],name)
                exported.update(model_id='beatweave-structure-round8-'+name,input_columns=columns,
                    research_only=True,production_enabled=False,trained_by_beatweave=True,threshold=threshold,
                    source='RWC-P audio and AIST human section labels',license='CC BY-NC 4.0')
                subset=x[::max(1,len(x)//256),:][:,columns]
                np.testing.assert_allclose(predict(exported,subset,True),estimator.predict_proba(subset)[:,1],atol=1e-12,rtol=0)
                save(model_dir/f'{name}.json',exported)
                entry=dict(name=name,task=task,family=family,config=config,threshold=threshold,validation=metric,
                    seconds=time.monotonic()-start,trees=len(exported['trees']),
                    learned_nodes=sum(len(t) for t in exported['trees']),export_parity=True)
                candidates.append(entry);history.append(entry);save(root/'validation-history.json',history)
                print(name,'validation F1',round(metric['macro_f1'],4),'threshold',threshold,flush=True)
            chosen=max(candidates,key=lambda c:(c['validation']['macro_f1'],-c['learned_nodes']))
            selection[task][family]=chosen
            shutil.copy2(model_dir/f'{chosen["name"]}.json',model_dir/f'{task}-{family}-selected.json')
    # Choose an untrained novelty baseline using validation only, under the same event tolerance.
    novelty=[]
    for column in ['novelty_4s_energy','novelty_4s_timbre','novelty_8s_chroma']:
        scores=[]
        for d in data['validation']:
            values=d['x'][:,NAMES.index(column)]
            scores.append(np.argsort(np.argsort(values)).astype(float)/max(1,len(values)-1))
        threshold,metric=select_threshold(data['validation'],scores,'boundary')
        novelty.append(dict(column=column,threshold=threshold,validation=metric))
    selection['novelty_baseline']=max(novelty,key=lambda c:c['validation']['macro_f1'])
    save(root/'selection.json',selection)
    frozen=dict(protocol_sha256=sha(root/'protocol.json'),selection_sha256=sha(root/'selection.json'),
        feature_code_sha256=sha(Path(__file__).with_name('features.py')),training_code_sha256=sha(Path(__file__)),
        portable_inference_code_sha256=sha(Path(__file__).resolve().parents[1]/'corpus-automix/common.py'),
        models={str(p.relative_to(root)):sha(p) for p in model_dir.glob('*-selected.json')},
        numpy=np.__version__,scipy=scipy.__version__,sklearn=sklearn.__version__,
        frozen_before_test_evaluation=True,production_enabled=False)
    save(root/'freeze.json',frozen)
    # Only now open the test feature arrays and score the already frozen choices.
    for m in manifest:
        if m['partition']!='test':continue
        with np.load(root/'features'/f'{m["id"]}.npz') as file:d={k:file[k] for k in file.files}
        d.update(id=m['id'],artist=m['artist']);data['test'].append(d)
    report=dict(tasks={},novelty_baseline={},freeze_sha256=sha(root/'freeze.json'),
        limitations=['100 mostly Japanese pop songs; held-out artists within one corpus, not broad cross-genre validation.',
            'Annotation boundaries include verse/chorus subsections and are not exact DJ transition-quality labels.',
            'Offline 0.5-second structure clock and 3-second evaluation tolerance are not beat-alignment accuracy.',
            'Scores are class-balanced model outputs, not calibrated guarantees.',
            'Beat detectors, transition length scorer and full-span faders were not trained here.'])
    all_predictions={}
    for task in ['chorus','boundary']:
        report['tasks'][task]={}
        for family in ['audio','position']:
            model=json.loads((model_dir/f'{task}-{family}-selected.json').read_text())
            result={}
            for split in ['train','validation','test']:
                predictions=[predict(model,d['x'][:,model['input_columns']],True) for d in data[split]]
                result[split]=assess(data[split],predictions,task,model['threshold'])
                for d,s in zip(data[split],predictions):all_predictions[f'{d["id"]}__{task}__{family}']=s
            report['tasks'][task][family]=result
        report['tasks'][task]['test_difference']=bootstrap_difference(report['tasks'][task]['audio']['test'],report['tasks'][task]['position']['test'])
    nb=selection['novelty_baseline'];scores=[]
    for d in data['test']:
        values=d['x'][:,NAMES.index(nb['column'])]
        scores.append(np.argsort(np.argsort(values)).astype(float)/max(1,len(values)-1))
    report['novelty_baseline']=dict(selection=nb,test=assess(data['test'],scores,'boundary',nb['threshold']))
    save(root/'training-results.json',report);np.savez_compressed(root/'predictions.npz',**all_predictions)
    print(json.dumps({t:{f:report['tasks'][t][f]['test']['macro_f1'] for f in ['audio','position']} for t in ['chorus','boundary']}),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);train(p.parse_args().root)
