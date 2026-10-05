#!/usr/bin/env python3
"""Freeze the round-5 audition pipeline; never overwrite a prior release."""
import argparse,datetime,json,shutil
from pathlib import Path
from run_auto import ROOT,save,sha

def freeze(previous,out):
    release=out/'frozen';release.mkdir()  # A release is immutable after creation.
    config=json.loads((previous/'config.json').read_text())
    config.update(release_id='beatweave-full-span-round5-v1',release_seconds=None,
        cue_model_updated_this_round=True,default_cue_model_updated=False,
        fader_policy='Learned bounded positive phase rates integrated across the full overlap',
        release_source='No short endpoint release: full overlap determines the envelope endpoints',
        renderer='R2 offline mapped',production_enabled=False)
    save(release/'config.json',config)
    for name in ['beat-this-small0.onnx','cue-model.json','cue-runtime.tsv']:
        shutil.copy2(previous/name,release/name)
    for src,name in [(out/'full-span-faders.json','full-span-faders.json'),
        (out/'cue-model.json','experimental-cue-model.json'),(out/'cue-runtime.tsv','experimental-cue-runtime.tsv'),
        (ROOT/'build/round5/training-engine.jar','preference-engine.jar'),
        (ROOT/'build/preference-training/warp-map','warp-map')]:shutil.copy2(src,release/name)
    code=['tools/preference-automix/'+n for n in ['run_auto.py','run_full_span.py','full_span_faders.py','train_faders.py']]
    code+=['tools/handoff-automix/'+n for n in ['audio_features.py','train_handoff.py']]+['tools/manual-automix/render.py']
    save(release/'freeze.json',{'schema':1,'release_id':config['release_id'],
        'frozen_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'artifact_sha256':{p.name:sha(p) for p in release.iterdir() if p.is_file()},
        'inference_code_sha256':{n:sha(ROOT/n) for n in code},
        'cue_policy':'Old automatic cue model remains default; explicit experimental-cues option selects the new linear model.',
        'manual_overrides':False,'production_enabled':False,
        'first_unseen_test_protocol':'Preserve the first automatic result or decline. Do not adjust cues, bars, offset or gains after hearing it.',
        'source_repository':'https://github.com/adrielGGmotion/BeatWeave'})
    print('Frozen round-5 release:',sha(release/'freeze.json'),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('previous',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();freeze(a.previous,a.out)
