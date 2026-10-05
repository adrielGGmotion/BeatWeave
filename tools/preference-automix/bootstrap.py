#!/usr/bin/env python3
"""Pinned build of the automatic preference runner and offline renderer."""
import hashlib,json,os,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def build():
    deps=ROOT/'.cache/verify/deps';deps.mkdir(parents=True,exist_ok=True)
    lock=json.loads((ROOT/'tools/verification-dependencies.json').read_text())['dependencies']
    for d in lock:
        p=deps/d['file']
        if not p.exists():subprocess.run(['curl','-fLsS',d['url'],'-o',str(p)],check=True)
        if hashlib.sha256(p.read_bytes()).hexdigest()!=d['sha256']:raise ValueError('Dependency hash mismatch: '+p.name)
    cp=os.pathsep.join(str(deps/d['file']) for d in lock);sources=[]
    for folder in ['analysis/src/commonMain','learned-beats/src/commonMain','learned-beats/src/ortMain']:sources.extend((ROOT/folder).rglob('*.kt'))
    sources.append(Path(__file__).with_name('PreferenceEngine.kt'));work=ROOT/'build/preference-training';work.mkdir(parents=True,exist_ok=True)
    jar=work/'preference-engine.jar'
    subprocess.run(['java','-Xmx2g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','1.8','-classpath',cp,'-d',str(jar),*map(str,sources)],check=True)
    (work/'classpath.txt').write_text(str(jar)+os.pathsep+cp)
    binary=ROOT/'build/handoff-training/warp-r3';binary.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run(['g++','-std=c++17','-O3','-DNDEBUG','-pthread','-I'+str(ROOT),str(ROOT/'tools/handoff-automix/warp.cpp'),str(ROOT/'rubberband/native/vendor/rubberband/single/RubberBandSingle.cpp'),'-o',str(binary)],check=True)
    print('Built preference engine and R3 renderer',flush=True)
if __name__=='__main__':build()
