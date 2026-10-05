#!/usr/bin/env python3
"""Experimental proposals; learned scorers plus explicitly declared search rules.

No user cue labels enter automatic candidate scoring. Provider grids are retained
to isolate musical selection; these are not production-approved mix plans.
"""
import argparse
import json
from pathlib import Path
import numpy as np
from scipy.special import logit
from common import predict, style_features, write_json
from train_phase import load_audio, onset_features, candidate_features


def cue_support(cues, positions, tolerance):
    t = np.array([c['time'] for c in cues])
    s = np.array([c['score'] for c in cues])
    return np.max(s[None, :] * np.exp(-.5*((positions[:, None]-t)/tolerance)**2), axis=1)


def phase(model, features, cue, period, duration):
    shifts = np.arange(-32, 33)*period/64
    x = candidate_features(*features, cue, period, min(duration, 16*period), shifts)
    scores = predict(model, x, True)
    best = int(np.argmax(scores))
    far = np.abs(shifts-shifts[best]) > .1*period
    return {'offset': float(shifts[best]), 'score': float(scores[best]),
            'margin': float(scores[best]-scores[far].max())}


def correction(model, features, plan):
    pa = plan['duration']/(4*plan['bars'])
    pb = pa*plan['speed']
    a = phase(model, features[0], plan['a'], pa, plan['duration'])
    b = phase(model, features[1], plan['b'], pb, plan['duration']*plan['speed'])
    shift = b['offset']-plan['speed']*a['offset']
    # Listening experiment guard, not a calibrated production confidence gate.
    usable = min(a['margin'], b['margin']) >= .05 and abs(shift) <= .25*pb
    return {'a': a, 'b': b, 'proposed_incoming_shift': shift,
            'applied': usable, 'applied_incoming_shift': shift if usable else 0.,
            'reason': 'bounded phase adjustment' if usable else 'ambiguous or larger than quarter-beat; left unchanged'}


def run(data, previous, output):
    refs = json.loads((data/'references.json').read_text())['pairs']
    old = json.loads((previous/'training-results.json').read_text())['pairs']
    cues = json.loads((output/'acoustic-cues.json').read_text())['tracks']
    style = json.loads((output/'dj-style-model.json').read_text())
    phase_model = json.loads((output/'phase-model.json').read_text())
    result = []
    for p, (pair, prior) in enumerate(zip(refs, old)):
        candidates = np.genfromtxt(data/f'pair-{p+1}-candidates.csv', delimiter=',', names=True)
        da, db = cues[2*p]['duration'], cues[2*p+1]['duration']
        # Define the operational search region before examining its winners.
        mask = ((candidates['a'] >= .65*da) & (candidates['a']+candidates['duration'] <= da-1)
                & (candidates['b'] < .55*db) & np.isin(candidates['bars'], [4, 8, 16]))
        c = candidates[mask]
        ba = 240*c['bars']/c['duration']; bb = ba/c['speed']
        x = np.array([style_features(a,b,n,da,db,aa,bbb) for a,b,n,aa,bbb in zip(c['a'],c['b'],c['bars'],ba,bb)])
        prior_score = predict(style,x,True)
        start_a = cue_support(cues[2*p]['cues'], c['a'], .6)
        end_a = cue_support(cues[2*p]['cues'], c['a']+c['duration'], .6)
        start_b = cue_support(cues[2*p+1]['cues'], c['b'], .6)
        # Hand-set fusion, not a trained end-to-end DJ controller. Weak timing
        # prior is deliberately a small component after poor held-out results.
        acoustic = .25*start_a + .25*end_a + .5*start_b
        score = acoustic + .10*prior_score + .05*c['x9'] + .05*c['x10']
        chosen = int(np.argmax(score));row=c[chosen]
        plan = {k:float(row[k]) for k in ['a','b','duration','speed','bars']}
        feat = [onset_features(load_audio(s['path'])) for s in pair['sources']]
        adjustment = correction(phase_model,feat,plan)
        plan['b'] += adjustment['applied_incoming_shift']
        old_adjustment = correction(phase_model,feat,prior['learned'])
        repair = dict(prior['learned']); repair['b'] += old_adjustment['applied_incoming_shift']
        trial = dict(prior['learned']); trial['b'] += old_adjustment['proposed_incoming_shift']
        top = np.argsort(-score)[:10]
        result.append({'pair':p+1,'title':pair['title'],'candidate_count':len(c),
            'previous':prior['learned'],'automatic_proposal':plan,'phase_adjustment':adjustment,
            'alignment_only':repair,'alignment_only_adjustment':old_adjustment,
            'alignment_trial':trial,'alignment_trial_note':'Ungated diagnostic only; never production-accepted. Render only when explicitly reviewing timing.',
            'score':float(score[chosen]),'acoustic_support':float(acoustic[chosen]),'timing_prior':float(prior_score[chosen]),
            'top_candidates':[{**{k:float(c[i][k]) for k in ['a','b','duration','speed','bars']},'score':float(score[i])} for i in top]})
        print('Pair',p+1,plan,'phase shift',adjustment['applied_incoming_shift'],flush=True)
    write_json(output/'proposals.json',{'schema':1,'production_enabled':False,
        'method':'CUE-DETR acoustic support + 0.10 learned timing prior + 0.05 level balance + 0.05 long-blend affinity; provider grid candidates; quarter-beat bounded phase repair.',
        'limitations':['Fusion weights and search bounds are hand-set; not trained end-to-end.',
            'Phase adjustment guard is not confidence-calibrated.',
            'CUE-DETR is an independently pretrained model. Its pretraining overlap with these commercial tracks is unknown.',
            'No newly learned fader policy; user-approved template is retained for new proposals.',
            'All six user tracks are development listening material, not independent evaluation.'], 'pairs':result})


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('data',type=Path);p.add_argument('previous',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();run(a.data,a.previous,a.output)
