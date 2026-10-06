#!/usr/bin/env python3
"""Freeze an opening-policy repair with every round-6 model byte unchanged."""
import argparse,datetime,json,shutil,subprocess
from pathlib import Path
from run_auto import ROOT,save,sha

def freeze(previous,out):
    if subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip():
        raise ValueError('Commit the source changes before freezing a release')
    release=out/'frozen';release.mkdir()
    original=json.loads((previous/'freeze.json').read_text())
    for name,expected in original['artifact_sha256'].items():
        if sha(previous/name)!=expected:raise ValueError('Prior release artifact changed: '+name)
        if name not in ['config.json','preference-engine.jar']:shutil.copy2(previous/name,release/name)
    config=json.loads((previous/'config.json').read_text());config.update(release_id='beatweave-opening-v1',
        cue_model_updated_this_round=False,default_cue_model_updated=False,
        incoming_entry_policy={'maximum_seconds':30.,'maximum_fraction':.1,'applies_to':'cue start','fallback':'decline'},
        production_enabled=False)
    save(release/'config.json',config)
    shutil.copy2(ROOT/'build/round5/training-engine.jar',release/'preference-engine.jar')
    (release/'warp-map').chmod(0o755)
    save(release/'freeze.json',{'schema':1,'release_id':config['release_id'],
        'frozen_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'artifact_sha256':{p.name:sha(p) for p in release.iterdir() if p.is_file()},
        'inference_code_sha256':{n:sha(ROOT/n) for n in original['inference_code_sha256']},
        'source_repository':'https://github.com/adrielGGmotion/BeatWeave',
        'source_tree':subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip(),
        'prior_freeze_sha256':sha(previous/'freeze.json'),
        'policy':config['incoming_entry_policy'],'models_retrained':False,'manual_overrides':False,
        'production_enabled':False,'cue_policy':original['cue_policy'],
        'first_unseen_test_protocol':original['first_unseen_test_protocol']})
    print(sha(release/'freeze.json'),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('previous',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();freeze(a.previous,a.out)
