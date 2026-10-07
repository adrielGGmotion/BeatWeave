#!/usr/bin/env python3
"""Acquire checksum-pinned RWC-P research audio using bounded range downloads."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

SIZE=4071840278
MD5='960a11a2d7fb603ad0dae8428f53d4f0'
URL='https://zenodo.org/records/18656623/files/RWC-P.zip?download=1'
CHUNK=128*1024*1024
ANNOTATIONS={'rwc-annotations':'0a1a6c31dbe73a7f5d44f7caef8cd0999402a4c2',
    'rwc-annotations-archive':'994499597853664890b517711f97df9f47c0358a'}


def acquire(root):
    root.mkdir(parents=True,exist_ok=True)
    for name,commit in ANNOTATIONS.items():
        target=root/name
        if not target.exists():
            subprocess.run(['git','clone','https://github.com/rwc-music/'+name+'.git',str(target)],check=True)
            subprocess.run(['git','checkout',commit],cwd=target,check=True)
        assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=target,text=True).strip()==commit
    archive=root/'RWC-P.zip';parts=root/'rwc-parts';parts.mkdir(exist_ok=True)
    partial=root/'RWC-P.zip.download'
    if partial.exists() and not archive.exists():
        # Reuse complete chunks from an interrupted ordinary transfer.
        with partial.open('rb') as f:
            for i in range(partial.stat().st_size//CHUNK):
                target=parts/f'{i:03d}.part'
                data=f.read(CHUNK)
                if not target.exists():target.write_bytes(data)

    def fetch(i):
        start=i*CHUNK;end=min(SIZE,start+CHUNK)-1
        target=parts/f'{i:03d}.part'
        if not target.exists() or target.stat().st_size != end-start+1:
            temporary=target.with_suffix('.download')
            subprocess.run(['curl','--fail','--silent','--show-error','--location','--retry','2',
                '--max-time','300','--range',f'{start}-{end}','--max-filesize',str(end-start+1),
                URL,'--output',str(temporary)],check=True)
            assert temporary.stat().st_size==end-start+1, 'Invalid byte-range response'
            temporary.replace(target)
        print('Audio archive part',i+1,'of',(SIZE+CHUNK-1)//CHUNK,flush=True)
        return target

    if not archive.exists():
        with ThreadPoolExecutor(max_workers=4) as workers:
            ordered=list(workers.map(fetch,range((SIZE+CHUNK-1)//CHUNK)))
        with archive.with_suffix('.assembled').open('wb') as output:
            for part in ordered:
                with part.open('rb') as source:
                    while block:=source.read(1024*1024):output.write(block)
        archive.with_suffix('.assembled').replace(archive)
    hashes=[hashlib.md5(),hashlib.sha256()]
    with archive.open('rb') as f:
        while block:=f.read(1024*1024):
            for h in hashes:h.update(block)
    assert archive.stat().st_size==SIZE and hashes[0].hexdigest()==MD5,'Published archive checksum mismatch'
    with zipfile.ZipFile(archive) as z:
        names=[n for n in z.namelist() if n.lower().endswith('.wav')]
        assert len(names)==100
    (root/'rwc-acquisition.json').write_text(json.dumps(dict(url=URL,bytes=SIZE,published_md5=MD5,
        sha256=hashes[1].hexdigest(),annotation_commits=ANNOTATIONS,license='CC BY-NC 4.0',
        use='Research-only model training; source audio not redistributed'),indent=2)+'\n')
    print('Verified 100-song archive',hashes[1].hexdigest(),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);acquire(p.parse_args().root)
