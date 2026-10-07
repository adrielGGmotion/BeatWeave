#!/usr/bin/env python3
"""Automatic structure predictions from one audio input; no cue/label overrides."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import numpy as np
from features import decode,extract,sha,RATE
from prepare_rwc import save
from train import peaks
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'corpus-automix'))
from common import predict


def verify(root):
    manifest=json.loads((root/'freeze.json').read_text())
    assert sha(root/'selection.json')==manifest['selection_sha256']
    assert sha(root/'protocol.json')==manifest['protocol_sha256']
    assert sha(Path(__file__).with_name('features.py'))==manifest['feature_code_sha256']
    assert sha(Path(__file__).resolve().parents[1]/'corpus-automix/common.py')==manifest['portable_inference_code_sha256']
    for relative,digest in manifest['models'].items():assert sha(root/relative)==digest


def intervals(times,mask):
    out=[];start=None
    for i,on in enumerate([*mask,False]):
        if on and start is None:start=i
        if not on and start is not None:
            out.append([max(0.,float(times[start]-.25)),float(times[i-1]+.25)]);start=None
    return out


def infer(root,audio,out):
    verify(root)
    expected=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',str(audio)],text=True))
    y=decode(audio);duration=len(y)/RATE
    assert abs(duration-expected)<.06,'Incomplete audio decode'
    times,x=extract(y);keep=(times>=2)&(times<=duration-2);times,x=times[keep],x[keep]
    scores={};thresholds={}
    for task in ['chorus','boundary']:
        model=json.loads((root/'models'/f'{task}-audio-selected.json').read_text())
        scores[task]=predict(model,x[:,model['input_columns']],True)
        thresholds[task]=model['threshold']
    result=dict(source_name=audio.name,source_sha256=sha(audio),duration=duration,freeze_sha256=sha(root/'freeze.json'),
        source_clock_seconds=times.tolist(),chorus_score=scores['chorus'].tolist(),boundary_score=scores['boundary'].tolist(),
        thresholds=thresholds,chorus_regions=intervals(times,scores['chorus']>=thresholds['chorus']),
        section_boundaries=peaks(times,scores['boundary'],thresholds['boundary']).tolist(),
        manual_overrides=False,role='Experimental structure diagnostic; no transition or beat-clock changes',
        limitation='0.5-second sampling; uncalibrated scores; Western/pop/EDM transfer is unvalidated')
    save(out,result);return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('audio',type=Path)
    p.add_argument('--release',required=True,type=Path);p.add_argument('--out',required=True,type=Path)
    a=p.parse_args();assert not a.out.exists(),'Preserve the first output';infer(a.release,a.audio,a.out)
