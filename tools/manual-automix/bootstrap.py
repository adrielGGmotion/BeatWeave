#!/usr/bin/env python3
"""Compile the pilot using the repository's checksum-pinned host toolchain, without Android SDK."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
deps = ROOT / '.cache/verify/deps'
deps.mkdir(parents=True, exist_ok=True)
locked = json.loads((ROOT/'tools/verification-dependencies.json').read_text())['dependencies']
for d in locked:
    p = deps/d['file']
    if not p.exists():
        subprocess.run(['curl','--fail','--silent','--show-error','--location',d['url'],'-o',str(p)],check=True)
    if hashlib.sha256(p.read_bytes()).hexdigest() != d['sha256']:
        raise ValueError('Dependency checksum mismatch: '+p.name)
cp = os.pathsep.join(str(deps/d['file']) for d in locked)
build = ROOT/'build/manual-automix'
build.mkdir(parents=True,exist_ok=True)
sources = list((ROOT/'analysis/src/commonMain').rglob('*.kt'))
sources += list((ROOT/'learned-beats/src/commonMain').rglob('*.kt'))
sources += list((ROOT/'learned-beats/src/ortMain').rglob('*.kt'))
sources += [ROOT/'tools/manual-automix/PilotFeatures.kt']
subprocess.run(['java','-Xmx2g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib','-no-reflect','-jvm-target','1.8','-classpath',cp,
                '-d',str(build/'pilot.jar'),*map(str,sources)],check=True)
(build/'classpath.txt').write_text(str(build/'pilot.jar')+os.pathsep+cp)
print('Compiled pilot:',build/'pilot.jar',flush=True)
if len(sys.argv)>1:
    subprocess.run(['java','-Xmx2g','-cp',(build/'classpath.txt').read_text(),
        'org.metrolist.beatweave.learned.PilotFeaturesKt',*sys.argv[1:]],check=True)
