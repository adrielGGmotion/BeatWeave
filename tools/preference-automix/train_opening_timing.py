#!/usr/bin/env python3
"""Fit only the cue scorer's timing terms using Raveform alignment estimates.

This is partial-feature supervision, not audio/chorus training. Missing acoustic
features are held at their standardized mean, never fabricated as labels.
The full acoustic scorer is used only for the single eligible personal replay.
"""
import argparse
import collections
import copy
import hashlib
import json
import os
os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
from pathlib import Path
import re
import zipfile

import numpy as np
from scipy.optimize import minimize
from scipy.special import logsumexp
from run_auto import save, sha
from train_cues_round6 import expand, positive

SEED = 20261006
SOURCE_SHA = '10c97fa9213fe4ca032195e73b6a9d068c0d5ca8a8f603615bb1bdbabffb34de'
BARS = np.array([2, 4, 8, 16, 32])
TERMS = list(range(4)) + [13+k for k,(i,j) in enumerate(
    (i,j) for i in range(13) for j in range(i,13)) if i < 4 and j < 4]
DEVELOPMENT_TITLES = ['yesbaby', 'sandstorm', 'mantra', 'subwaysurfers',
    'lovemelikeyoudo', 'closer', 'homicidelove', 'thelastpage', 'yeahno', 'somebodytoldme']


def key(title):
    return re.sub(r'\W+', '', re.sub(r'\[[^\]]*\]', '', title).casefold())


def partition(identity):
    n = int(hashlib.sha256(('beatweave-opening-timing-v1:'+identity).encode()).hexdigest()[:8],16) / 2**32
    return 'train' if n < .5 else 'validation' if n < .75 else 'test'


def prepare(archive, out):
    assert sha(archive) == SOURCE_SHA, 'Dataset checksum mismatch'
    rng = np.random.default_rng(SEED)
    queries, features = [], []
    rejected, bpm_cache = collections.Counter(), {}
    with zipfile.ZipFile(archive) as z:
        names = set(z.namelist())
        tracks = {r['id']:r for r in map(json.loads,z.read('raveform/tracks.jsonl').decode().splitlines())}
        mixes = {r['id']:r for r in map(json.loads,z.read('raveform/mixes.jsonl').decode().splitlines())}

        def bpm(tid):
            if tid not in bpm_cache:
                name = f'raveform/beats/tracks/{tid}.beat.json'
                intervals = np.diff(json.loads(z.read(name))['beats']) if name in names else np.array([])
                intervals = intervals[(intervals > .2) & (intervals < 1.5)]
                bpm_cache[tid] = 60 / np.median(intervals) if len(intervals) > 20 else np.nan
            return bpm_cache[tid]

        for name in sorted(n for n in names if '/alignments/' in n and n.endswith('.jsonl')):
            alignments = [json.loads(line) for line in z.read(name).decode().splitlines() if line.strip()]
            if not alignments: continue
            mid = alignments[0]['mix_id']
            mix = mixes.get(mid)
            if not mix: continue
            by_id = collections.defaultdict(list)
            for a in alignments: by_id[a['track_id']].append(a)
            for ea,eb in zip(mix['tracklist'],mix['tracklist'][1:]):
                ia,ib = ea.get('id'),eb.get('id')
                if ia not in tracks or ib not in tracks or ia == ib:
                    rejected['missing_track'] += 1; continue
                ka,kb = key(tracks[ia]['title']) or ia,key(tracks[ib]['title']) or ib
                if any(t in k for t in DEVELOPMENT_TITLES for k in (ka,kb)):
                    rejected['personal_development_work'] += 1; continue
                if len(by_id[ia]) != 1 or len(by_id[ib]) != 1:
                    rejected['missing_or_ambiguous_alignment'] += 1; continue
                a,b = by_id[ia][0],by_id[ib][0]
                da,db = tracks[ia]['duration'],tracks[ib]['duration']
                ba,bb = bpm(ia),bpm(ib)
                if not (90 <= da <= 1200 and 90 <= db <= 1200 and 60 <= ba <= 200 and 60 <= bb <= 200):
                    rejected['duration_or_tempo'] += 1; continue
                if a['cost'] > .5 or b['cost'] > .5:
                    rejected['alignment_cost'] += 1; continue
                ma = a['mixout_time_mix']-a['mixin_time_mix']
                mb = b['mixout_time_mix']-b['mixin_time_mix']
                if min(ma,mb) < 30:
                    rejected['short_matched_span'] += 1; continue
                sa = (a['mixout_time_track']-a['mixin_time_track'])/ma
                sb = (b['mixout_time_track']-b['mixin_time_track'])/mb
                overlap = a['mixout_time_mix']-b['mixin_time_mix']
                if not (.8 <= sa <= 1.25 and .8 <= sb <= 1.25 and .8 <= ba/bb <= 1.25 and 3 <= overlap <= 90):
                    rejected['overlap_or_speed'] += 1; continue
                if not (a['mixin_time_mix'] < b['mixin_time_mix'] < a['mixout_time_mix'] < b['mixout_time_mix']):
                    rejected['non_simple_transition'] += 1; continue
                observed_bars = overlap*sa*ba/240
                bars = int(BARS[np.argmin(abs(np.log2(BARS/observed_bars)))])
                if abs(observed_bars-bars) > max(.5,.125*bars):
                    rejected['non_supported_length'] += 1; continue
                ca,cb = a['mixout_time_track']-overlap*sa,b['mixin_time_track']
                limit = min(30.,db*.1)
                if not (0 <= cb <= limit):
                    rejected['incoming_outside_opening'] += 1; continue
                split = partition('recording:'+ka)
                if not (split == partition('recording:'+kb) == partition('mix:'+mid)):
                    rejected['cross_partition_pair_or_mix'] += 1; continue

                def valid(aa,bb0,length):
                    duration = length*240/ba
                    return 0 <= aa and 0 <= bb0 <= limit and aa+duration <= da and bb0+duration*ba/bb <= db

                if not valid(ca,cb,bars):
                    rejected['source_coverage'] += 1; continue
                candidates = [(ca,cb,bars)]
                for _ in range(240):
                    if len(candidates) == 9: break
                    aa = ca+float(rng.choice([-32,-16,-8,-4,0,4,8,16,32]))*60/ba
                    bi = cb+float(rng.choice([-32,-16,-8,-4,0,4,8,16,32]))*60/bb
                    length = int(rng.choice(BARS)) if rng.random() < .5 else bars
                    p = (aa,bi,length)
                    if p not in candidates and valid(*p): candidates.append(p)
                if len(candidates) != 9:
                    rejected['insufficient_alternatives'] += 1; continue
                features.append([[aa/da,bi/db,np.log2(n)/5,n*240/ba/60] for aa,bi,n in candidates])
                queries.append(dict(mix_id=mid,outgoing_id=ia,incoming_id=ib,outgoing_key=ka,incoming_key=kb,
                    partition=split,a=ca,b=cb,bars=bars,observed_bars=observed_bars,candidates=candidates,
                    outgoing_duration=da,incoming_duration=db,bpm_a=ba,bpm_b=bb,
                    alignment_cost_a=a['cost'],alignment_cost_b=b['cost']))
    assert queries
    for field in ['mix_id','outgoing_id','incoming_id','outgoing_key','incoming_key']:
        # Recording sets combine outgoing and incoming roles below.
        fields = ['outgoing_id','incoming_id'] if field.endswith('_id') and field != 'mix_id' else (
            ['outgoing_key','incoming_key'] if field.endswith('_key') else [field])
        sets = [{q[k] for q in queries if q['partition']==s for k in fields} for s in ['train','validation','test']]
        assert not (sets[0]&sets[1] or sets[0]&sets[2] or sets[1]&sets[2]), 'Split leakage'
    np.savez_compressed(out/'timing-examples.npz',x=np.asarray(features),splits=np.array([q['partition'] for q in queries]))
    save(out/'timing-queries.json',queries)
    save(out/'dataset-summary.json',dict(source_sha256=SOURCE_SHA,retained_transitions=len(queries),
        partitions=dict(collections.Counter(q['partition'] for q in queries)),exclusions=dict(rejected),
        recording_id_and_normalized_title_disjoint=True,mix_disjoint=True,dj_disjoint='not established'))


def replay_data(previous, old_candidates, prior):
    pairs = json.loads((previous/'pair-labels.json').read_text())['pairs']
    examples,audit = [],[]
    for p in pairs:
        limit = p['incoming_start_limit_seconds']
        if p['label']['b'] > limit:
            audit.append(dict(id=p['id'],used=False,reason='approval outside current opening policy'));continue
        arrays = [np.atleast_2d(np.loadtxt(f,delimiter=',',skiprows=1)) for f in
            sorted((old_candidates/'pairs'/p['id']/'expanded').glob('pool-*/candidates.csv')) if len(f.read_text().splitlines())>1]
        rows = np.concatenate(arrays)
        rows = rows[(rows[:,1]>=0)&(rows[:,1]<=limit)]
        _,ix = np.unique(np.round(rows[:,np.r_[0:6,7:20]],7),axis=0,return_index=True)
        rows = rows[np.sort(ix)]
        pos = positive(rows,p['label'])
        audit.append(dict(id=p['id'],used=bool(pos.any()),candidates=len(rows),
            reason='supported approved opening candidate' if pos.any() else 'exact approval unavailable to planner'))
        if pos.any():
            z = expand((rows[:,7:20]-prior['mean'])/prior['scale'])
            examples.append((z[:,TERMS],z@prior['weights'],pos))
    assert len(examples)==1, 'Audit changed: revisit the preregistered replay protocol'
    return examples,audit


def metrics(score):
    rank = 1+np.sum(score[:,1:]>=score[:,:1],axis=1)
    return dict(queries=len(rank),top1=float(np.mean(rank==1)),top3=float(np.mean(rank<=3)),
        mean_reciprocal_rank=float(np.mean(1/rank)),mean_rank=float(rank.mean()),
        cross_entropy=float(np.mean(logsumexp(score,axis=1)-score[:,0])))


def train(archive, previous, old_candidates, out):
    out.mkdir(parents=True,exist_ok=False)
    protocol = dict(seed=SEED,source='https://huggingface.co/datasets/taejunkim/raveform',
        archive_sha256=SOURCE_SHA,prior_sha256=sha(previous/'frozen/experimental-cue-model.json'),
        trainable_weight_indices=TERMS,frozen_weights=90,anchor=.5,corpus_loss_weight=.9,replay_loss_weight=.1,
        candidate_count=9,split='SHA256, 50/25/25; both normalized recording keys and mix must agree',
        optimizer='L-BFGS-B, maxiter=250, gtol=1e-7, ftol=1e-11',hyperparameter_search=False,
        corpus_feature_scope='four timing features only; all acoustic standardized features held at zero',
        evaluation='same generated options before/after; held-out ranking is timing imitation, not listening quality',
        promotion='experimental only regardless of metrics; audition first output for all five pairs without adjustment',
        reuse='Raveform has appeared in earlier experiments; new split is disjoint within this run, not a never-seen corpus')
    save(out/'protocol.json',protocol)  # Written before data preparation or any metrics.
    save(out/'replay-inputs.json',dict(labels_sha256=sha(previous/'pair-labels.json'),
        candidate_sha256={str(f.relative_to(old_candidates)):sha(f) for f in
            sorted(old_candidates.glob('pairs/*/expanded/pool-*/candidates.csv'))}))
    prepare(archive,out)
    prior = json.loads((previous/'frozen/experimental-cue-model.json').read_text())
    d = np.load(out/'timing-examples.npz');x = d['x'];splits = d['splits']
    raw = np.tile(prior['mean'],(x.shape[0],x.shape[1],1));raw[:,:,:4]=x
    z = expand(((raw-prior['mean'])/prior['scale']).reshape(-1,13)).reshape(len(x),9,104)
    terms = z[:,:,TERMS];base = z@prior['weights'];mask=splits=='train'
    assert mask.sum()>50 and all(np.sum(splits==s)>=10 for s in ['validation','test'])
    replay,audit = replay_data(previous,old_candidates,prior)
    tx,tb = terms[mask],base[mask]

    def objective(delta):
        score = tb+tx@delta;den = logsumexp(score,axis=1)
        loss = .25*np.dot(delta,delta)+.9*np.mean(den-score[:,0])
        probabilities=np.exp(score-den[:,None]);probabilities[:,0]-=1
        grad=.5*delta+.9*np.einsum('nc,ncf->f',probabilities,tx)/len(tx)
        for rx,rb,pos in replay:
            s=rb+rx@delta;all_lse=logsumexp(s);pos_lse=logsumexp(s[pos])
            loss+=.1*(all_lse-pos_lse)/len(replay)
            grad+=.1*(rx.T@np.exp(s-all_lse)-rx[pos].T@np.exp(s[pos]-pos_lse))/len(replay)
        return float(loss),grad

    zero=np.zeros(len(TERMS));eps=1e-5;analytic=objective(zero)[1]
    numerical=np.array([(objective(zero+np.eye(len(TERMS))[i]*eps)[0]-objective(zero-np.eye(len(TERMS))[i]*eps)[0])/(2*eps) for i in range(len(TERMS))])
    assert np.max(abs(analytic-numerical))<1e-6, 'Objective gradient mismatch'
    opt=minimize(objective,zero,jac=True,method='L-BFGS-B',options={'maxiter':250,'gtol':1e-7,'ftol':1e-11})
    assert opt.success and np.isfinite(opt.x).all()
    model=copy.deepcopy(prior);w=np.array(prior['weights']);w[TERMS]+=opt.x;model['weights']=w.tolist()
    model.update(model_id='beatweave-cue-round7-opening-timing-v1',production_enabled=False,
        trainable_parameters=14,trainable_weight_indices=TERMS,training_indices=None,
        frozen_quadratic_weights='all except timing-only terms',training_scope=protocol['corpus_feature_scope'])
    untouched=sorted(set(range(104))-set(TERMS));assert np.array_equal(w[untouched],np.array(prior['weights'])[untouched])
    after=base+terms@opt.x
    report=dict(training=dict(success=bool(opt.success),iterations=int(opt.nit),message=str(opt.message),
        initial_objective=objective(zero)[0],final_objective=float(opt.fun),
        changed_parameters=int(np.sum(abs(opt.x)>1e-9)),delta_l2=float(np.linalg.norm(opt.x)),
        gradient_max_error=float(np.max(abs(analytic-numerical))),frozen_weights_exact=True),
        splits={s:dict(before=metrics(base[splits==s]),after=metrics(after[splits==s])) for s in ['train','validation','test']},
        replay=audit,
        limitations=['Alignment-derived, not human-rated transition quality. Generated alternatives may be valid.',
            'No source audio in corpus fit; acoustic/chorus and beat-detector weights were not trained.',
            'Population timing fit at mean acoustic features may not transfer to full acoustic ranking.',
            'Mix and recording separation checked; DJ identity and alternate-master separation are incomplete.',
            'Familiar personal recordings are development checks, not unseen tests. Prior already trained on them.',
            'Raveform was used in earlier experiments. This is a new within-run split, not a sealed benchmark.'])
    for rx,rb,pos in replay:
        report['personal_replay_rank']={name:int(np.sum(s>max(s[pos]))+1) for name,s in [('before',rb),('after',rb+rx@opt.x)]}
    save(out/'cue-model.json',model)
    (out/'cue-runtime.tsv').write_text('\n'.join(','.join(map(str,model[k])) for k in ['mean','scale','weights'])+'\n')
    save(out/'training-results.json',report)
    np.savez_compressed(out/'corpus-predictions.npz',before=base,after=after,splits=splits)
    print(json.dumps(report),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for arg in ['archive','previous','old_candidates','out']:p.add_argument(arg,type=Path)
    a=p.parse_args();train(a.archive,a.previous,a.old_candidates,a.out)
