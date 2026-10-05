#!/usr/bin/env python3
"""Normalize user annotations without changing source clocks. No recordings are committed."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

def prepare(source, work):
    work.mkdir(parents=True,exist_ok=True)
    records=[json.loads(line) for line in (source/'transitions.jsonl').read_text().splitlines() if line.strip()]
    patterns=['Madison Beer*','Darude*','JENNIE*','Subway Surfers*','Ellie Goulding*','The Chainsmokers*']
    tracks=[]; pairs=[]
    for p,r in enumerate(records):
        raw=r['transition']['raw_saved_transition']['value']['overlap']
        pair={'index':p+1,'title':r['outgoing']['title']['value']+' → '+r['incoming']['title']['value'],
              'a':raw['startAMs']/1000,'b':raw['startBMs']/1000,'duration':raw['durationMs']/1000,
              'bars':raw['durationBars'],'speed':raw['speedB'],'automation':{},'sources':[]}
        grids=r['transition']['bar_interpretation']['raw_beat_grids']['value']
        for side,name in enumerate(['outgoing','incoming']):
            index=p*2+side
            matches=list(source.glob(patterns[index]+'.opus'))
            if len(matches)!=1: raise ValueError('Recording ambiguous or missing: '+patterns[index])
            path=matches[0].resolve(); mono=work/f'track-{index}.mono.f32'; beats=work/f'track-{index}.beats.csv'
            subprocess.run(['ffmpeg','-v','error','-y','-i',str(path),'-ac','1','-ar','22050','-f','f32le',str(mono)],check=True)
            duration=mono.stat().st_size/4/22050
            beat_rows=[b for b in grids[side]['beats'] if 0<=b['time']<duration]
            beats.write_text(''.join(f"{b['time']},{b['value']}\n" for b in beat_rows))
            tracks.append(f'{index}\t{mono.resolve()}\t{beats.resolve()}')
            pair['sources'].append({'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                'decoded_duration_seconds':duration,'metadata_duration_seconds':r[name]['duration']['value']/1000})
            t=r['transition'][name]; c={}
            c['volume']=t['volume']['curve']['raw']
            for band in ['low','mid','high']:
                c[band]=t['eq']['bands'][band].get('raw',{'curves':[]})
            for kind in ['Cutoff','Resonance']:
                c[kind.lower()]=t['filter']['parameters']['filter'+kind+('Out' if side==0 else 'In')+'Curves']['raw']
            pair['automation'][name]=c
        pairs.append(pair)
    (work/'tracks.tsv').write_text('\n'.join(tracks)+'\n')
    (work/'references.json').write_text(json.dumps({'schema':1,'source_sha256':hashlib.sha256((source/'transitions.jsonl').read_bytes()).hexdigest(),'pairs':pairs},indent=2))
    print('Prepared',len(pairs),'pairs with untrimmed PCM.',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('work',type=Path)
    a=p.parse_args();prepare(a.source,a.work)
