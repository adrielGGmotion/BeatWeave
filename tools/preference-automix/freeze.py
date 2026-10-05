#!/usr/bin/env python3
"""Seal inference weights, policies, compiled engine and code before unseen input."""
import argparse,datetime,hashlib,json,shutil,sys
from pathlib import Path
import numpy as np
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
sys.path.insert(0,str(HERE.parent/'handoff-automix'))
from train_handoff import save

def freeze(inputs,out):
    release=out/'frozen';release.mkdir(exist_ok=True)
    feedback=json.loads((out/'feedback.json').read_text());cue=json.loads((inputs/'pilot/personal-transition-model.json').read_text())
    slim={k:cue[k] for k in ['model_id','feature_names','mean','scale','weights','feature_expansion']}
    save(release/'cue-model.json',slim);(release/'cue-runtime.tsv').write_text('\n'.join(','.join(map(str,cue[k])) for k in ['mean','scale','weights'])+'\n')
    for src,dest in [(out/'personal-faders.json','personal-faders.json'),(ROOT/'build/preference-training/preference-engine.jar','preference-engine.jar'),(ROOT/'build/handoff-training/warp-r3','warp-r3'),(ROOT/'learned-beats/models/beat-this-small0.onnx','beat-this-small0.onnx')]:shutil.copy2(src,release/dest)
    config={'schema':1,'release_id':'beatweave-preference-round4-v1','cue_model_updated_this_round':False,
        'cue_policy':'existing LocalMixPlanner.bestTransition with prior personal cue scorer; original bar/pulse/clock acceptance limits',
        'fader_policy':'preference-finetuned 5450-parameter MLP; source-only features; independent monotone projection; amplitude clipped to [0,1]',
        'release_seconds':float(np.median([p['release_width_seconds'] for p in feedback['pairs']])),
        'release_source':'median 95%-to-5% outgoing release measured from the three approved volume traces',
        'eq_enabled':False,'pitch_scale':1.,'pre_roll_seconds':6,'post_roll_seconds':6,
        'manual_cue_or_gain_overrides':False,'production_enabled':False,
        'jvm_dependencies':json.loads((ROOT/'tools/verification-dependencies.json').read_text())['dependencies']}
    save(release/'config.json',config)
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    files=['tools/preference-automix/run_auto.py','tools/handoff-automix/audio_features.py','tools/handoff-automix/train_handoff.py','tools/manual-automix/render.py']
    manifest={'schema':1,'release_id':config['release_id'],'frozen_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'artifact_sha256':{p.name:sha(p) for p in sorted(release.iterdir()) if p.is_file() and p.name!='freeze.json'},
        'inference_code_sha256':{name:sha(ROOT/name) for name in files},
        'first_unseen_test_protocol':'Use the frozen runner once on the supplied pair. Preserve the first output or decline. Do not change cues, bars, offsets, EQ, faders or model weights after hearing it.',
        'source_repository':'https://github.com/adrielGGmotion/BeatWeave','license_note':'Compiled runtime corresponds to the source commit recorded in release evidence. Rubber Band native component is GPL-2.0-or-later; preserve its notices and corresponding source.'}
    save(release/'freeze.json',manifest);print('Frozen release hash:',sha(release/'freeze.json'),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('inputs',type=Path);p.add_argument('out',type=Path);a=p.parse_args();freeze(a.inputs,a.out)
