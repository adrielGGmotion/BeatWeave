#!/usr/bin/env python3
"""Compare automatic, 8-bar and 16-bar planning using a verified frozen engine."""
import argparse
import json
import os
import subprocess
from pathlib import Path
from run_auto import ROOT, dependencies, sha, verify_release

def audit(attempt, release, out):
    verify_release(release)
    parent = json.loads((attempt / 'attempt.json').read_text())
    if parent['freeze_sha256'] != sha(release / 'freeze.json'):
        raise ValueError('Attempt and release differ')
    if out.exists() and any(out.iterdir()):
        raise ValueError('Use a fresh diagnostic output directory')
    out.mkdir(parents=True, exist_ok=True)
    config = json.loads((release / 'config.json').read_text())
    cp = os.pathsep.join([str(release / 'preference-engine.jar'), *dependencies(config)])
    jar = out / 'bar-audit.jar'
    subprocess.run(['java', '-Xmx1g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '1.8', '-classpath', cp,
                    '-d', str(jar), str(Path(__file__).with_name('BarCountAudit.kt'))], check=True)
    subprocess.run(['java', '-Xmx2g', '-cp', str(jar) + os.pathsep + cp,
                    'org.metrolist.beatweave.learned.BarCountAuditKt',
                    str(release / 'beat-this-small0.onnx'), str(release / 'cue-runtime.tsv'),
                    str(attempt / 'work/track-0.mono.f32'), str(attempt / 'work/track-1.mono.f32'),
                    str(out)], check=True)
    print((out / 'bar-count-comparison.tsv').read_text())

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--attempt', required=True, type=Path)
    p.add_argument('--release', required=True, type=Path)
    p.add_argument('--out', required=True, type=Path)
    a = p.parse_args()
    audit(a.attempt, a.release, a.out)
