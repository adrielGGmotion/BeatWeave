#!/usr/bin/env python3
"""Acquire published data, verify bytes and retain provenance. No account/login required."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from common import write_json,partition,is_development_recording

SETS=[44,123,222,275]
RAVEFORM_SHA256='10c97fa9213fe4ca032195e73b6a9d068c0d5ca8a8f603615bb1bdbabffb34de'

def download(url,path):
    if not path.exists():
        temporary=path.with_suffix(path.suffix+'.download')
        subprocess.run(['curl','--fail','--silent','--show-error','--location','--retry','2',
                        '--max-time','300',url,'--output',str(temporary)],check=True)
        temporary.replace(path)

def acquire(root):
    root.mkdir(parents=True,exist_ok=True)
    download('https://zenodo.org/api/records/1422385',root/'unmixdb-record.json')
    meta=json.loads((root/'unmixdb-record.json').read_text())
    files={f['key']:f for f in meta['files']};manifest=[];tracks=[]
    for number in SETS:
        filename=f'mixotic-set{number:03d}-excerpts.zip';path=root/filename
        download('https://zenodo.org/records/1422385/files/'+filename,path)
        expected=files[filename]['checksum'];actual='md5:'+hashlib.md5(path.read_bytes()).hexdigest()
        if actual!=expected:raise ValueError('Dataset checksum mismatch: '+filename)
        manifest.append({'dataset':'UnmixDB','source_url':'https://zenodo.org/records/1422385/files/'+filename,
            'filename':filename,'published_checksum':expected,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'purpose':'phase calibration from audio and published automatic beat annotations',
            'record_license':meta['metadata'].get('license'),'source_audio_redistributed':False})
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if '/refsongs/' not in name or not name.endswith('.excerpt40.mp3') or name.startswith('__MACOSX'):continue
                if is_development_recording(name):continue
                data=z.read(name);digest=hashlib.sha256(data).hexdigest();target=root/'audio'/f'{digest}.mp3'
                target.parent.mkdir(exist_ok=True)
                if not target.exists():target.write_bytes(data)
                txt=z.read(name[:-3]+'txt').decode().splitlines();fields=txt[0].split('\t');values=txt[1].split('\t')
                label={k:float(v) for k,v in zip(fields[1:],values[1:])}
                xmlname=name.replace('.excerpt40.mp3','.beat.xml')
                tree=ET.fromstring(z.read(xmlname));beats=[]
                for node in tree.iter():
                    if node.tag.endswith('}segment'):
                        t=float(node.attrib['time'])
                        if t<=label['cutpoint']:beats.append(t)
                        elif t>=label['joinpoint']:beats.append(t-label['joinpoint']+label['cutpoint'])
                key=Path(name).name.lower()
                tracks.append({'id':digest,'recording_key':key,'partition':partition(key),'set':number,
                    'filename':str(target.resolve()),'original_name':name,'labels':label,'beats':beats,
                    'beat_annotation_source':'UnmixDB supplied IRCAM beat tracker; not manual human ground truth'})
            for name in z.namelist():
                if name.startswith('__MACOSX'):continue
                if name.endswith(('license_link.txt','reftrack_attributions.txt')):
                    target=root/'provenance'/f'{number:03d}-{Path(name).name}';target.parent.mkdir(exist_ok=True)
                    target.write_bytes(z.read(name))
    unique={t['id']:t for t in tracks};tracks=list(unique.values())
    # Hash-identical audio has one partition regardless of names; filenames group repeated source names.
    write_json(root/'audio-corpus.json',{'tracks':tracks,'sources':manifest})
    url='https://huggingface.co/datasets/taejunkim/raveform/resolve/main/raveform.zip'
    download(url,root/'raveform.zip')
    raveform_hash=hashlib.sha256((root/'raveform.zip').read_bytes()).hexdigest()
    if raveform_hash!=RAVEFORM_SHA256:raise ValueError('Raveform changed; review the new dataset before retraining')
    manifest.append({'dataset':'Raveform','source_url':url,'filename':'raveform.zip',
        'sha256':raveform_hash,
        'purpose':'observed DJ entry/exit and overlap choices; no Raveform audio provided',
        'citation':'https://mir-aidj.github.io/raveform/'})
    write_json(root/'corpus-provenance.json',manifest)
    print('Acquired',len(tracks),'unique audio excerpts; partitions',
          {p:sum(t['partition']==p for t in tracks) for p in ['train','validation','test']},flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();acquire(a.root)
