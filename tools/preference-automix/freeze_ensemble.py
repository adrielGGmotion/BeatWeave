#!/usr/bin/env python3
"""Pin the experimental ensemble release, including unchanged prior faders."""
import argparse,datetime,json,shutil,subprocess
from pathlib import Path
from run_auto import ROOT,save,sha

def freeze(previous,out):
    release=out/'frozen';release.mkdir()
    config=json.loads((previous/'config.json').read_text());config.update(release_id='beatweave-ensemble-round6-v1',
        cue_model_updated_this_round=True,default_cue_model_updated=False,detector_ensemble=True,production_enabled=False)
    save(release/'config.json',config)
    for name in ['beat-this-small0.onnx','cue-model.json','cue-runtime.tsv','full-span-faders.json','warp-map']:
        shutil.copy2(previous/name,release/name)
    for source,name in [(ROOT/'learned-beats/models/beat-this-final0.onnx','beat-this-final0.onnx'),
        (ROOT/'build/round5/training-engine.jar','preference-engine.jar'),
        (out/'cue-model.json','experimental-cue-model.json'),(out/'cue-runtime.tsv','experimental-cue-runtime.tsv')]:
        shutil.copy2(source,release/name)
    (release/'warp-map').chmod(0o755)
    code=['tools/preference-automix/'+n for n in ['run_auto.py','run_ensemble.py','full_span_faders.py','train_faders.py']]
    code+=['tools/handoff-automix/'+n for n in ['audio_features.py','train_handoff.py']]+['tools/manual-automix/render.py']
    save(release/'freeze.json',{'schema':1,'release_id':config['release_id'],
        'frozen_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'artifact_sha256':{p.name:sha(p) for p in release.iterdir() if p.is_file()},
        'inference_code_sha256':{n:sha(ROOT/n) for n in code},
        'source_repository':'https://github.com/adrielGGmotion/BeatWeave',
        'source_tree':subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip(),
        'cue_policy':'Prior cue scorer is default. --experimental-cues selects the fitted 104-parameter model; not promoted.',
        'detector_policy':'Two existing released models, each passed through normal gates; at most four candidate searches. Neither detector was trained here.',
        'manual_overrides':False,'production_enabled':False,
        'first_unseen_test_protocol':'Retain the first output or decline. No per-song cue, bar, offset, EQ or gain edits.'})
    print(sha(release/'freeze.json'))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('previous',type=Path);parser.add_argument('out',type=Path)
    args=parser.parse_args();freeze(args.previous,args.out)
