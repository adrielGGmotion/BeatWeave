#!/usr/bin/env python3
"""Build the R2 mapped renderer without modifying an archived frozen release."""
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def build():
    target = ROOT / 'build/preference-training/warp-map'
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        'g++', '-std=c++17', '-O3', '-DNDEBUG', '-DBEATWEAVE_MAP_ENGINE_R2',
        '-pthread', '-I' + str(ROOT), str(ROOT / 'tools/handoff-automix/warp.cpp'),
        str(ROOT / 'rubberband/native/vendor/rubberband/single/RubberBandSingle.cpp'),
        '-o', str(target),
    ], check=True)
    print(target)

if __name__ == '__main__':
    build()
