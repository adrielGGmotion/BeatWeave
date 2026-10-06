#!/usr/bin/env python3
"""Recollect development pools from a frozen release and hash-checked source PCM."""
import argparse,json,time
from pathlib import Path
from run_auto import save,sha,verify_release
from run_ensemble import plan,decode

def collect(root,release,scope):
    verify_release(release)
    for pair in json.loads((root/'pair-labels.json').read_text())['pairs']:
        folder=root/'pairs'/pair['id'];target=folder/scope
        if target.exists():raise ValueError('Use a new collection scope')
        data=folder/'data';data.mkdir(parents=True,exist_ok=True)
        for i,source in enumerate(pair['sources']):
            source=Path(source)
            if sha(source)!=pair['source_sha256'][i]:raise ValueError('Source recording changed')
            decode(source,data,i)
        label=pair['label'];reference=folder/'approved-window.csv'
        reference.write_text(','.join(str(label[k]) for k in ['a','b','duration','speed','bars'])+'\n')
        start=time.monotonic()
        plan(data,target,release,cache=root/'logit-cache',reference=reference,collect=True)
        save(target/'collection.json',{'wall_seconds':time.monotonic()-start,'source_sha256':pair['source_sha256'],
            'reference_policy':'Training feature extraction only. No labels are supplied to candidate selection or playback.',
            'freeze_sha256':sha(release/'freeze.json')})

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('root',type=Path);parser.add_argument('release',type=Path)
    parser.add_argument('--scope',default='expanded');args=parser.parse_args();collect(args.root,args.release,args.scope)
