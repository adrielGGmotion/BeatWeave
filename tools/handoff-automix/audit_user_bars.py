#!/usr/bin/env python3
"""Frozen four-beat phase model on review songs; no labels or refitting."""
import argparse
import json
from pathlib import Path
import numpy as np
from train_bar_phase import audio_features,phase_features,predict,write_json

def audit(data,clocks,pilot,models,out):
    refs=json.loads((data/'references.json').read_text())['pairs']
    old=json.loads((pilot/'training-results.json').read_text())['pairs']
    model=json.loads((models/'bar-phase-model.json').read_text())
    results=[]
    for pair,previous in zip(refs,old):
        for side,source in enumerate(pair['sources']):
            i=(pair['index']-1)*2+side
            beats=np.loadtxt(clocks/f'track-{i}-canonical.csv',delimiter=',',skiprows=1)[:,0]
            neural=np.loadtxt(clocks/f'track-{i}-bars.csv',delimiter=',',skiprows=1)
            cue=previous['learned']['a' if side==0 else 'b'];near=int(np.argmin(abs(beats-cue)))
            t,c=audio_features(source['path']);votes=[];aligned=[]
            for start in sorted({max(0,min(near+d,len(beats)-21)) for d in range(-16,17,4)}):
                s=predict(model,phase_features(t,c,beats[start:start+21]),True)
                global_scores=np.roll(s,start%4);aligned.append(global_scores)
                votes.append({'start_beat_index':start,'global_phase':int(np.argmax(global_scores)),
                    'margin':float(np.sort(s)[-1]-np.sort(s)[-2]),'scores':s.tolist()})
            avg=np.mean(aligned,axis=0);phase=int(np.argmax(avg));candidate_indices=np.arange(phase,len(beats),4)
            selected=int(candidate_indices[np.argmin(abs(beats[candidate_indices]-cue))])
            closest_neural=float(neural[np.argmin(abs(neural[:,0]-beats[selected])),0])
            results.append({'track':i,'title':Path(source['path']).name,'old_cue':cue,'old_nearest_beat':float(beats[near]),
                'estimated_global_phase':phase,'suggested_beat_index':selected,'suggested_bar_time':float(beats[selected]),
                'averaged_scores':avg.tolist(),'vote_agreement':float(np.mean([v['global_phase']==phase for v in votes])),
                'individual_windows_above_validation_margin':sum(v['margin']>=.2 for v in votes),
                'nearest_neural_bar':closest_neural,'distance_to_neural_bar_seconds':abs(closest_neural-float(beats[selected])),
                'windows':votes,'automatic_correction_authorized_by_metric':False})
    write_json(out/'user-bar-audit.json',{'tracks':results,'limitations':[
        'Review-song bars have no independent human ground truth. Agreement is not accuracy.',
        'Four-beat hypothesis only; the underlying beat tracker may return another metrical level.',
        'The validation margin applies to individual Ballroom windows, not this out-of-domain averaged vote.',
        'These are diagnostics, not validated automatic cue decisions.']})
    for r in results:print(r['track'],r['old_cue'],'->',r['suggested_bar_time'],'agreement',round(r['vote_agreement'],2),
        'neural distance',round(r['distance_to_neural_bar_seconds'],3),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ['data','clocks','pilot','models','out']:p.add_argument(name,type=Path)
    a=p.parse_args();audit(a.data,a.clocks,a.pilot,a.models,a.out)
