#!/usr/bin/env python3
"""Prepare human chorus/section labels with artist-disjoint song-level splits."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
import numpy as np
from sklearn.model_selection import GroupShuffleSplit
from features import decode,extract,sha,RATE,NAMES
from acquire_rwc import ANNOTATIONS,MD5,SIZE

SEED=20261007


def save(path,value):path.write_text(json.dumps(value,indent=2,allow_nan=False)+'\n')


def partition(rows):
    # A shared artist or normalized title joins recordings before splitting.
    parent=list(range(len(rows)))
    def find(i):
        while parent[i]!=i:parent[i]=parent[parent[i]];i=parent[i]
        return i
    seen={}
    for i,r in enumerate(rows):
        for field in ['Artist','Title']:
            key=field+':'+re.sub(r'\W+','',r[field].casefold())
            if key in seen:parent[find(i)]=find(seen[key])
            else:seen[key]=i
    groups=np.array([find(i) for i in range(len(rows))])
    train,rest=next(GroupShuffleSplit(n_splits=1,train_size=.6,random_state=SEED).split(rows,groups=groups))
    vi,ti=next(GroupShuffleSplit(n_splits=1,train_size=.5,random_state=SEED+1).split(rest,groups=groups[rest]))
    labels={int(i):s for s,indices in [('train',train),('validation',rest[vi]),('test',rest[ti])] for i in indices}
    assert all(not set(groups[[i for i in labels if labels[i]==a]])&set(groups[[i for i in labels if labels[i]==b]])
        for a,b in [('train','validation'),('train','test'),('validation','test')])
    return labels


def annotations(path):
    result=[]
    for line in path.read_text().splitlines():
        if not line.strip():continue
        a,b,label=line.split('\t',2)
        result.append(dict(start=float(a)/100,end=float(b)/100,label=label.strip('"')))
    assert all(s['end']>s['start']>=0 for s in result)
    assert all(a['end']<=b['start']+.011 for a,b in zip(result,result[1:]))
    return result


def prepare(source,out):
    out.mkdir(parents=True,exist_ok=True);(out/'features').mkdir(exist_ok=True)
    archive=source/'RWC-P.zip'
    acquisition=json.loads((source/'rwc-acquisition.json').read_text())
    assert archive.stat().st_size==SIZE and acquisition['published_md5']==MD5
    for name,commit in ANNOTATIONS.items():
        assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=source/name,text=True).strip()==commit
    rows=[r for r in csv.DictReader((source/'rwc-annotations/metadata.csv').open(),delimiter=';') if r['CollID']=='P']
    assert len(rows)==100
    splits=partition(rows)
    protocol=dict(seed=SEED,dataset='RWC-P 2026 release + AIST human structure annotations',
        acquisition=acquisition,metadata_sha256=sha(source/'rwc-annotations/metadata.csv'),
        split='GroupShuffleSplit 60/20/20 groups; shared artist OR normalized title kept together; seed fixed before training',
        input='11025 Hz mono; 152 audio/context features at 0.5-second source-clock bins; offline full-song normalization',
        feature_names=NAMES,chorus_target='label starts with chorus; pre/post-chorus are negative',
        boundary_target='within 0.75 seconds of any internal annotated section start',
        boundary_evaluation='one-to-one event matching within 3 seconds; peaks separated by >=4 seconds',
        models=[dict(leaves=l,iterations=n,learning_rate=.05,l2=1.) for l,n in [(7,120),(15,120),(15,240),(31,240)]],
        baseline='trained position/duration-only model using same train/validation/test; untrained energy/novelty boundary baselines',
        selection='Select chorus model by validation macro frame F1 and boundary model by validation macro event F1; each threshold selected on validation only.',
        test='Open test metrics once after model and thresholds are frozen; never use personal songs for selection.',
        scope='Structure classification, not DJ preference or end-to-end transition quality.',
        runtime='2 CPU threads; no GPU; no pretrained model.',license='CC BY-NC 4.0 research checkpoint; no production promotion')
    if (out/'protocol.json').exists():assert json.loads((out/'protocol.json').read_text())==protocol
    else:save(out/'protocol.json',protocol)
    manifest=[];byte_folds={}
    with zipfile.ZipFile(archive) as z:
        mapping={Path(n).stem:n for n in z.namelist() if n.lower().endswith('.wav')}
        for i,row in enumerate(rows):
            tid=row['RWCID'];npz=out/'features'/f'{tid}.npz'
            label_path=source/'rwc-annotations-archive/AIST_RWC-MDB-P-2001_CHORUS'/f'RM-P{int(row["PieceNo"]):03d}.CHORUS.TXT'
            sections=annotations(label_path);split=splits[i]
            if npz.exists():
                item=json.loads((out/'features'/f'{tid}.json').read_text())
                assert item['annotation_sha256']==sha(label_path) and item['partition']==split
            else:
                member=mapping[tid];content=z.read(member);wave_sha=hashlib.sha256(content).hexdigest()
                y=decode(content)
                duration=len(y)/RATE
                assert abs(duration-float(row['duration']))<.03,'Recording duration does not match annotation metadata'
                assert abs(sections[-1]['end']-duration)<1.,'Structure clock/recording mismatch'
                times,x=extract(y)
                mask=(times>=max(2.,float(row['audio_start'])))&(times<=min(duration-2,float(row['audio_end'])))
                times,x=times[mask],x[mask]
                chorus=np.zeros(len(times),dtype=np.int8)
                for s in sections:
                    if s['label'].casefold().startswith('chorus'):
                        chorus[(times>=s['start'])&(times<s['end'])]=1
                boundaries=np.array([s['start'] for s in sections[1:] if times[0]<=s['start']<=times[-1]])
                boundary=(np.min(abs(times[:,None]-boundaries),axis=1)<=.75).astype(np.int8)
                assert len(set(chorus))==2 and len(set(boundary))==2
                np.savez_compressed(npz,x=x,times=times,chorus=chorus,boundary=boundary,boundaries=boundaries)
                item=dict(id=tid,title=row['Title'],artist=row['Artist'],language=row['SingingLanguage'],partition=split,
                    duration=duration,wav_member=member,wav_sha256=wave_sha,annotation_sha256=sha(label_path),
                    sections=sections,frames=len(times),feature_sha256=sha(npz),features=len(NAMES))
                save(out/'features'/f'{tid}.json',item)
            digest=item['wav_sha256']
            assert digest not in byte_folds or byte_folds[digest]==split,'Byte-identical recording leakage'
            byte_folds[digest]=split
            manifest.append(item);save(out/'manifest.json',manifest)
            print(tid,split,'frames',item['frames'],flush=True)
    save(out/'dataset-summary.json',dict(songs=len(manifest),frames=sum(m['frames'] for m in manifest),
        splits={s:dict(songs=sum(m['partition']==s for m in manifest),artists=len({m['artist'] for m in manifest if m['partition']==s}),
            frames=sum(m['frames'] for m in manifest if m['partition']==s)) for s in ['train','validation','test']},
        source_recording_and_artist_leakage=False,personal_songs_used=0))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();prepare(a.source,a.out)
