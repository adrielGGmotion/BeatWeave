#!/usr/bin/env python3
"""Frozen-checkpoint diagnostics. Does not select or refit either model."""
import argparse
import json
from pathlib import Path
import numpy as np
from train_handoff import save

def evaluate(out):
    rows=json.loads((out/'bar-phase-predictions.json').read_text())
    test=[x for x in rows if x['split']=='test'];groups=sorted({x['group'] for x in test})
    stats=np.array([[sum(x['correct'] for x in test if x['group']==g),
        sum(x['baseline_correct'] for x in test if x['group']==g),sum(x['group']==g for x in test)] for g in groups])
    rng=np.random.default_rng(923);boots=stats[rng.integers(0,len(stats),(2000,len(stats)))].sum(axis=1)
    acc=boots[:,0]/boots[:,2];delta=(boots[:,0]-boots[:,1])/boots[:,2]
    report={'bar_test_cluster_bootstrap':{'resampling_unit':'album/replica group','groups':len(groups),'replicates':2000,'seed':923,
        'accuracy_95_percent_interval':np.quantile(acc,[.025,.975]).tolist(),
        'paired_accuracy_improvement_95_percent_interval':np.quantile(delta,[.025,.975]).tolist()},'bar_margin_subsets':{}}
    threshold=json.loads((out/'bar-phase-results.json').read_text())['validation_threshold_for_90_percent']
    for split in ['validation','test']:
        z=[x for x in rows if x['split']==split]
        selected=[x for x in z if threshold is not None and x['margin']>=threshold]
        report['bar_margin_subsets'][split]={'threshold':threshold,'selected_windows':len(selected),'all_windows':len(z),
            'accuracy':float(np.mean([x['correct'] for x in selected])) if selected else None}
    audio=json.loads((out/'handoff-training-results.json').read_text())['splits']['test']['learned_spectral_mae_db']
    time=json.loads((out/'time-only/handoff-training-results.json').read_text())['splits']['test']['learned_spectral_mae_db']
    report['fader_ablation']={'test_audio_conditioned_spectral_mae_db':audio,'test_time_only_spectral_mae_db':time,
        'audio_features_beat_time_only_on_spectral_metric':audio<time,
        'decision':'Keep experimental. Two test mixes and one validation mix are insufficient for reliable model selection.'}
    save(out/'evaluation-audit.json',report)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);a=p.parse_args();evaluate(a.out)
