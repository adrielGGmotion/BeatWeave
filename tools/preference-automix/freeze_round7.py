#!/usr/bin/env python3
"""Freeze a timing-only experimental cue update; retain all prior playback code."""
import argparse
import datetime
import json
from pathlib import Path
import shutil
import subprocess
from run_auto import ROOT, save, sha, verify_release


def freeze(previous, out):
    if subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip():
        raise ValueError('Commit source before freezing')
    original = verify_release(previous)
    release = out/'frozen';release.mkdir()
    for name in original['artifact_sha256']:
        shutil.copy2(previous/name,release/name)
    for source,target in [('cue-model.json','experimental-cue-model.json'),('cue-runtime.tsv','experimental-cue-runtime.tsv')]:
        shutil.copy2(out/source,release/target)
    config=json.loads((release/'config.json').read_text())
    config.update(release_id='beatweave-round7-opening-timing-v1',cue_model_updated_this_round=True,
        experimental_cue_training='14 timing coefficients; 90 acoustic and cross coefficients frozen',
        default_cue_model_updated=False,production_enabled=False)
    save(release/'config.json',config);(release/'warp-map').chmod(0o755)
    changed={'config.json','experimental-cue-model.json','experimental-cue-runtime.tsv'}
    unchanged={name:sha(release/name)==expected for name,expected in original['artifact_sha256'].items() if name not in changed}
    assert all(unchanged.values())
    save(out/'unchanged-artifacts.json',unchanged)
    save(release/'freeze.json',dict(schema=1,release_id=config['release_id'],
        frozen_at_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        artifact_sha256={p.name:sha(p) for p in release.iterdir() if p.is_file()},
        inference_code_sha256=original['inference_code_sha256'],
        source_repository='https://github.com/adrielGGmotion/BeatWeave',
        source_tree=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip(),
        prior_freeze_sha256=sha(previous/'freeze.json'),models_retrained=True,manual_overrides=False,
        production_enabled=False,cue_policy=original['cue_policy'],
        training_protocol_sha256=sha(out/'protocol.json'),
        first_unseen_test_protocol=original['first_unseen_test_protocol']))
    verify_release(release)
    print(sha(release/'freeze.json'),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('previous',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();freeze(a.previous,a.out)
