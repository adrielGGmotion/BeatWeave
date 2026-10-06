#!/usr/bin/env python3
"""Collect actual candidates with source checks and content-keyed model caching."""
import argparse,json,subprocess,time
from pathlib import Path
from run_auto import ROOT,sha,save

def collect(out,previous,model,scope,ids):
    cp=(ROOT/'build/round5/classpath.txt').read_text()
    labels=json.loads((out/'pair-labels.json').read_text())
    for p in labels['pairs']:
        if ids and p['id'] not in ids:continue
        pair=out/'pairs'/p['id'];target=pair/scope
        if target.exists() and any(target.iterdir()):raise ValueError('Use a fresh collection scope')
        for i,source in enumerate(p['sources']):
            assert sha(Path(source))==p['source_sha256'][i]
            duration=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',source],text=True))
            for kind,rate,channels in [('mono',22050,1),('stereo',48000,2)]:
                pcm=pair/'data'/f'track-{i}.{kind}.f32'
                if abs(pcm.stat().st_size/(4*channels*rate)-duration)>.05:raise ValueError('Incomplete decoded source: '+str(pcm))
        ref=pair/'approved-window.csv';label=p['label']
        ref.write_text(','.join(str(label[k]) for k in ['a','b','duration','speed','bars'])+'\n')
        target.mkdir(parents=True,exist_ok=True);start=time.monotonic()
        save(target/'input-check.json',{'source_sha256':p['source_sha256'],'pcm_sha256':[sha(pair/'data'/f'track-{i}.mono.f32') for i in [0,1]],
            'beat_model_sha256':sha(model),'engine_sha256':sha(ROOT/'build/round5/training-engine.jar'),'training_reference_only':True})
        print(scope,p['id'],flush=True)
        subprocess.run(['java','-Xmx2g',f'-Dbeatweave.candidateFile={target}/candidates.csv',f'-Dbeatweave.referenceFile={ref}',
            f'-Dbeatweave.logitCache={out}/logit-cache','-cp',cp,'org.metrolist.beatweave.learned.PreferenceEngineKt',
            'plan',str(model),str(target),str(previous/'frozen/cue-runtime.tsv'),
            str(pair/'data/track-0.mono.f32'),str(pair/'data/track-1.mono.f32')],check=True)
        save(target/'collection.json',{'wall_seconds':time.monotonic()-start,'scope':scope,'model_sha256':sha(model)})

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('previous',type=Path)
    p.add_argument('--model',type=Path,required=True);p.add_argument('--scope',required=True);p.add_argument('--ids',nargs='*')
    a=p.parse_args();collect(a.out,a.previous,a.model,a.scope,a.ids)
