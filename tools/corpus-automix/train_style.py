#!/usr/bin/env python3
"""Learn a population prior from observed DJ transitions, with recording-disjoint folds.

This is a timing/style prior, not an audio or chorus classifier. Negative options are
generated alternatives; they do not have human bad-transition ratings.
"""
import argparse
import collections
import json
import os
os.environ.setdefault('OMP_NUM_THREADS','2');os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
from pathlib import Path
import re
import time
import zipfile
import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
from common import SEED,STYLE_FEATURES,export_booster,partition,predict,style_features,write_json,is_development_recording

BAR_COUNTS=np.array([2,4,8,16,32])
DATASET_VERSION=2

def recording_key(track):
    title=re.sub(r'\[[^\]]*\]','',track.get('title','')).casefold()
    return re.sub(r'\W+','',title) or track['id']

def prepare(root):
    start=time.monotonic();rng=np.random.default_rng(SEED)
    examples=[];rows=[];targets=[];split_rows=[];reasons=collections.Counter();bpm_cache={}
    with zipfile.ZipFile(root/'raveform.zip') as z:
        names=set(z.namelist())
        tracks={r['id']:r for r in map(json.loads,z.read('raveform/tracks.jsonl').decode().splitlines())}
        mixes={r['id']:r for r in map(json.loads,z.read('raveform/mixes.jsonl').decode().splitlines())}
        def bpm(track):
            if track not in bpm_cache:
                name=f'raveform/beats/tracks/{track}.beat.json'
                if name not in names:bpm_cache[track]=np.nan
                else:
                    beats=json.loads(z.read(name))['beats'];diff=np.diff(beats);diff=diff[(diff>.2)&(diff<1.5)]
                    bpm_cache[track]=60/np.median(diff) if len(diff)>20 else np.nan
            return bpm_cache[track]
        files=sorted(n for n in names if '/alignments/' in n and n.endswith('.jsonl'))
        for number,name in enumerate(files):
            alignments=[json.loads(line) for line in z.read(name).decode().splitlines() if line.strip()]
            if not alignments:continue
            mid=alignments[0]['mix_id'];mix=mixes.get(mid)
            if not mix:continue
            by_id=collections.defaultdict(list)
            for a in alignments:by_id[a['track_id']].append(a)
            for entry_a,entry_b in zip(mix['tracklist'],mix['tracklist'][1:]):
                ia,ib=entry_a.get('id'),entry_b.get('id')
                if ia not in tracks or ib not in tracks or ia==ib:reasons['missing_track']+=1;continue
                if any(is_development_recording(tracks[i].get('title','')) for i in [ia,ib]):
                    reasons['development_recording']+=1;continue
                if len(by_id[ia])!=1 or len(by_id[ib])!=1:reasons['missing_or_ambiguous_alignment']+=1;continue
                a,b=by_id[ia][0],by_id[ib][0]
                da,db=tracks[ia]['duration'],tracks[ib]['duration'];ba,bb=bpm(ia),bpm(ib)
                if not(90<=da<=1200 and 90<=db<=1200 and 60<=ba<=200 and 60<=bb<=200):reasons['duration_or_tempo']+=1;continue
                if a['cost']>.5 or b['cost']>.5:reasons['alignment_cost']+=1;continue
                ma=a['mixout_time_mix']-a['mixin_time_mix'];mb=b['mixout_time_mix']-b['mixin_time_mix']
                if min(ma,mb)<30:reasons['short_matched_span']+=1;continue
                sa=(a['mixout_time_track']-a['mixin_time_track'])/ma
                sb=(b['mixout_time_track']-b['mixin_time_track'])/mb
                overlap=a['mixout_time_mix']-b['mixin_time_mix']
                if not(.8<=sa<=1.25 and .8<=sb<=1.25 and .8<=ba/bb<=1.25 and 3<=overlap<=90):
                    reasons['overlap_or_speed']+=1;continue
                if not(a['mixin_time_mix']<b['mixin_time_mix']<a['mixout_time_mix']<b['mixout_time_mix']):
                    reasons['non_simple_transition']+=1;continue
                bars=overlap*sa*ba/240
                nearest=int(BAR_COUNTS[np.argmin(np.abs(np.log2(BAR_COUNTS/bars)))])
                if abs(bars-nearest)>max(.5,.125*nearest):reasons['non_supported_length']+=1;continue
                ca=a['mixout_time_track']-overlap*sa;cb=b['mixin_time_track']
                ka,kb=recording_key(tracks[ia]),recording_key(tracks[ib]);pa,pb=partition(ka),partition(kb)
                if pa!=pb:reasons['cross_partition_pair']+=1;continue
                def valid(aa,bb0,length):
                    duration=length*240/ba
                    return 0<=aa and 0<=bb0 and aa+duration<=da and bb0+duration*ba/bb<=db
                if not valid(ca,cb,nearest):reasons['source_coverage']+=1;continue
                candidates=[(ca,cb,nearest)];attempts=0
                while len(candidates)<9 and attempts<120:
                    attempts+=1
                    da_beats=float(rng.choice([-32,-16,-8,-4,0,4,8,16,32]))
                    db_beats=float(rng.choice([-32,-16,-8,-4,0,4,8,16,32]))
                    length=int(rng.choice(BAR_COUNTS)) if rng.random()<.5 else nearest
                    aa=ca+da_beats*60/ba;bb0=cb+db_beats*60/bb
                    proposal=(aa,bb0,length)
                    if proposal in candidates or not valid(*proposal):continue
                    candidates.append(proposal)
                if len(candidates)!=9:reasons['insufficient_alternatives']+=1;continue
                first=len(rows)
                rows.extend([style_features(aa,bb0,length,da,db,ba,bb) for aa,bb0,length in candidates])
                targets.extend([1]+[0]*8);split_rows.extend([pa]*9)
                examples.append({'mix_id':mid,'outgoing_id':ia,'incoming_id':ib,'outgoing_key':ka,'incoming_key':kb,
                    'partition':pa,'row':first,'count':9,'a':ca,'b':cb,'bars':nearest,'observed_bars':bars,
                    'outgoing_duration':da,'incoming_duration':db,'bpm_a':ba,'bpm_b':bb,
                    'alignment_cost_a':a['cost'],'alignment_cost_b':b['cost']})
            if (number+1)%1000==0:print('DJ annotations:',number+1,'mixes;',len(examples),'usable transitions',flush=True)
    np.savez_compressed(root/'style-examples.npz',x=np.array(rows,dtype=np.float32),y=np.array(targets),splits=np.array(split_rows))
    write_json(root/'style-queries.json',examples)
    write_json(root/'style-dataset-summary.json',{'dataset_version':DATASET_VERSION,'source_mix_files':len(files),'source_tracks':len(tracks),
        'retained_transitions':len(examples),'excluded':dict(reasons),'seconds':time.monotonic()-start})

def train(root,out):
    started=time.monotonic();out.mkdir(parents=True,exist_ok=True)
    summary=root/'style-dataset-summary.json'
    if not (root/'style-examples.npz').exists() or not summary.exists() or json.loads(summary.read_text()).get('dataset_version')!=DATASET_VERSION:
        prepare(root)
    d=np.load(root/'style-examples.npz');x=d['x'];y=d['y'];splits=d['splits'];mask=splits=='train'
    queries=json.loads((root/'style-queries.json').read_text())
    model=HistGradientBoostingClassifier(max_iter=160,max_leaf_nodes=15,max_depth=5,learning_rate=.07,
        l2_regularization=2,early_stopping=False,max_bins=128,random_state=SEED)
    model.fit(x[mask],y[mask],sample_weight=np.where(y[mask],8.,1.))
    exported=export_booster(model,STYLE_FEATURES,'dj-transition-population-prior-v1')
    write_json(out/'dj-style-model.json',exported)
    step=max(1,len(x)//100);np.testing.assert_allclose(predict(exported,x[::step],True),model.predict_proba(x[::step])[:,1],atol=1e-10)
    scores=model.decision_function(x);reports={};keys={};ids={};predictions=[]
    # Position and length terms of the old rule, without unavailable acoustic features.
    length_preference={2:-.4,4:.42,8:.65,16:.15,32:-.25}
    old=1.1*np.clip((x[:,0]-.70)/.20,-1,1)+.25*np.clip((.45-x[:,1])/.4,-1,1)
    old+=np.array([length_preference[int(round(2**v))] for v in x[:,4]])
    for split in ['train','validation','test']:
        selected=[q for q in queries if q['partition']==split];rank=[];old_rank=[]
        keys[split]={q[k] for q in selected for k in ['outgoing_key','incoming_key']}
        ids[split]={q[k] for q in selected for k in ['outgoing_id','incoming_id']}
        for q in selected:
            a=q['row'];b=a+q['count'];s=scores[a:b];o=old[a:b]
            rank.append(int(np.sum(s[1:]>=s[0]))+1);old_rank.append(int(np.sum(o[1:]>=o[0]))+1)
            predictions.append({'mix_id':q['mix_id'],'outgoing_id':q['outgoing_id'],'incoming_id':q['incoming_id'],
                'partition':split,'recorded_choice_rank':rank[-1],'position_baseline_rank':old_rank[-1]})
        rr=np.array(rank);oo=np.array(old_rank)
        reports[split]={'transitions':len(selected),'distinct_recordings':len(ids[split]),'top1':float(np.mean(rr==1)),
            'top3':float(np.mean(rr<=3)),'mean_rank':float(np.mean(rr)),
            'position_baseline_top1':float(np.mean(oo==1)),'position_baseline_mean_rank':float(np.mean(oo))}
    for a,b in [('train','validation'),('train','test'),('validation','test')]:
        assert not(keys[a]&keys[b] or ids[a]&ids[b]),'Recording leakage'
    report={'seed':SEED,'features':len(STYLE_FEATURES),'candidate_examples':len(x),'seconds':time.monotonic()-started,
        'splits':reports,'export_parity':True,'recording_leakage':False,
        'task':'Recover the observed DJ timing choice among eight generated alternatives.',
        'labels':'Raveform mix-to-track alignment estimates from real DJ mixes, not manually rated transition quality.',
        'limitations':['No audio features or chorus/vocal understanding in this population prior.',
            'Alternatives may also be musically valid; ranking metrics are not listening-quality scores.',
            'Mixes/DJs may appear in multiple folds, while recording IDs and normalized titles are disjoint.'],
        'dataset':json.loads((root/'style-dataset-summary.json').read_text())}
    write_json(out/'dj-style-training-results.json',report);write_json(out/'dj-style-predictions.json',predictions)
    print(json.dumps(report),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('out',type=Path);a=p.parse_args();train(a.root,a.out)
