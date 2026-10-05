#!/usr/bin/env python3
"""Retry an accepted frozen plan with the repaired renderer, without selecting new cues.

The original attempt and release remain intact. Input hashes, release hashes,
original plan/map hashes, and the replacement renderer hash are recorded.
"""
import argparse
import datetime
import json
import os
import shutil
import subprocess
from pathlib import Path

os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
import numpy as np
from run_auto import ROOT, RATE, sha, verify_release, render_map, envelope, encode, save

def retry(outgoing, incoming, attempt, release, out):
    verify_release(release)
    parent = json.loads((attempt / 'attempt.json').read_text())
    if parent['freeze_sha256'] != sha(release / 'freeze.json'):
        raise ValueError('Attempt belongs to a different frozen release')
    if [sha(outgoing), sha(incoming)] != parent['input_sha256']:
        raise ValueError('Original input files are required')
    plan = json.loads((attempt / 'analysis/plan.json').read_text())
    if plan['status'] != 'accepted' or not plan['production_clock_gates']:
        raise ValueError('Only an accepted plan can be retried; no planning rescue')
    if out.exists() and any(out.iterdir()):
        raise ValueError('Use a fresh output directory')
    binary = ROOT / 'build/preference-training/warp-map'
    if not binary.is_file():
        raise ValueError('Run build_renderer.py first')
    out.mkdir(parents=True, exist_ok=True)
    data = out / 'work'
    data.mkdir()
    record = {
        'started_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'kind': 'renderer-repair-retry', 'freeze_sha256': parent['freeze_sha256'],
        'input_sha256': parent['input_sha256'],
        'original_attempt_sha256': sha(attempt / 'attempt.json'),
        'original_plan_sha256': sha(attempt / 'analysis/plan.json'),
        'original_clock_map_sha256': sha(attempt / 'analysis/accepted-map.csv'),
        'renderer': 'Rubber Band R2 offline, linked stereo, pitch scale 1',
        'renderer_sha256': sha(binary), 'manual_overrides': False,
        'planner_rerun': False, 'models_retrained': False,
    }
    save(out / 'attempt.json', record)
    try:
        for i, path in enumerate([outgoing, incoming]):
            expected = float(subprocess.check_output([
                'ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', str(path)
            ], text=True))
            dest = data / f'track-{i}.stereo.f32'
            subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(path),
                            '-ac', '2', '-ar', str(RATE), '-f', 'f32le', str(dest)],
                           stdin=subprocess.DEVNULL, check=True)
            if abs(dest.stat().st_size / (RATE * 8) - expected) > .05:
                raise ValueError('Incomplete decoded source')
        shutil.copytree(attempt / 'analysis', out / 'analysis')
        clock = np.loadtxt(out / 'analysis/accepted-map.csv', delimiter=',', skiprows=1)
        if not np.isfinite(clock).all() or not (np.diff(clock, axis=0) > 0).all():
            raise ValueError('Invalid accepted clock map')
        t, a, b = render_map(data, plan, clock, binary, data)
        model = json.loads((release / 'personal-faders.json').read_text())
        config = json.loads((release / 'config.json').read_text())
        gains, trace = envelope(model, a, b, t, plan['duration'], config['release_seconds'])
        audio = encode((a * gains[:, 0, None] + b * gains[:, 1, None]) / np.sqrt(2),
                       out / 'automatic-transition.ogg')
        save(out / 'gain-trace.json', {'time': t[::480].tolist(), 'gain': gains[::480].tolist(), 'model': trace})
        result = {'status': 'rendered', 'plan': plan, 'audio': audio,
                  'renderer': record['renderer'], 'manual_overrides': False,
                  'eq_and_filter_processing': False, 'planner_rerun': False,
                  'limits': 'Original automatic plan and frozen trained models; repaired experimental renderer. Not an unchanged frozen-runner result or a new training round.'}
        save(out / 'result.json', result)
        print(json.dumps(result), flush=True)
    except Exception as error:
        save(out / 'result.json', {'status': 'failed', 'plan': plan,
                                 'error': f'{type(error).__name__}: {error}'})
        raise

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('outgoing', type=Path)
    parser.add_argument('incoming', type=Path)
    parser.add_argument('--attempt', required=True, type=Path)
    parser.add_argument('--release', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    retry(args.outgoing, args.incoming, args.attempt, args.release, args.out)
