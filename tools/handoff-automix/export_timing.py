#!/usr/bin/env python3
"""Compile source-clock diagnostics with the repository's pinned host toolchain."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[2]

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('data');p.add_argument('model');p.add_argument('output');a=p.parse_args()
    weights=Path(a.model)
    if hashlib.sha256(weights.read_bytes()).hexdigest()!='b6d54bca156b039593b6d9d48fd3ab3e5d09be06bbbe2706f0376c9f103d5191':
        raise ValueError('Only the verified small0 checkpoint is accepted')
    deps=ROOT/'.cache/verify/deps';lock=json.loads((ROOT/'tools/verification-dependencies.json').read_text())['dependencies']
    for d in lock:
        path=deps/d['file']
        if not path.exists():subprocess.run(['curl','-fLsS',d['url'],'-o',str(path)],check=True)
        if hashlib.sha256(path.read_bytes()).hexdigest()!=d['sha256']:raise ValueError('Dependency hash mismatch')
    cp=os.pathsep.join(str(deps/d['file']) for d in lock);sources=[]
    for folder in ['analysis/src/commonMain','learned-beats/src/commonMain','learned-beats/src/ortMain']:
        sources.extend((ROOT/folder).rglob('*.kt'))
    sources.append(Path(__file__).with_name('ExportTiming.kt'))
    jar=ROOT/'build/handoff-training/timing.jar';jar.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run(['java','-Xmx2g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
        '-jvm-target','1.8','-classpath',cp,'-d',str(jar),*map(str,sources)],check=True)
    subprocess.run(['java','-Xmx2g','-cp',str(jar)+os.pathsep+cp,'org.metrolist.beatweave.learned.ExportTimingKt',a.data,a.model,a.output],check=True)
