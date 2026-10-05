#!/usr/bin/env python3
"""Automatic before/fade-only/new-cue comparisons; no per-song cue overrides."""
import argparse,json,os,subprocess
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
from pathlib import Path
import numpy as np
from run_auto import ROOT,render_map,envelope,encode,sha,save
from full_span_faders import full_span_envelope

def prepared(pair,analysis,model,kind,binary,work,release):
    plan=json.loads((analysis/'plan.json').read_text())
    if plan['status']!='accepted':return None
    work.mkdir(parents=True,exist_ok=True)
    clock=np.loadtxt(analysis/'accepted-map.csv',delimiter=',',skiprows=1)
    t,a,b=render_map(pair/'data',plan,clock,binary,work)
    if kind=='old':
        config=json.loads((release/'config.json').read_text())
        g,trace=envelope(model,a,b,t,plan['duration'],config['release_seconds'])
    else:g,trace=full_span_envelope(model,a,b,t,plan['duration'])
    raw=(a*g[:,0,None]+b*g[:,1,None])/np.sqrt(2)
    endpoint={}
    for progress in [0,.1,.25,.5,.75,.9,.99,1]:
        at=int(np.argmin(abs(t-progress*plan['duration'])));endpoint[str(progress)]=g[at].tolist()
    return raw,{'plan':plan,'trace':trace,'time':t[::480].tolist(),'gain':g[::480].tolist(),'progress_gains':endpoint,
        'plan_sha256':sha(analysis/'plan.json'),'clock_map_sha256':sha(analysis/'accepted-map.csv')}

def compare(out,release):
    doc=json.loads((out/'pair-labels.json').read_text());old=json.loads((release/'personal-faders.json').read_text())
    new=json.loads((out/'full-span-faders.json').read_text());binary=ROOT/'build/preference-training/warp-map'
    cp=(ROOT/'build/round5/classpath.txt').read_text();report={'pairs':[],'new_cue_default_promoted':False,
        'selection_policy':'Both cue versions use the actual automatic planner and original acceptance gates.',
        'training_data_used':'These are development/review songs; not unseen evaluation.',
        'rendering_policy':'R2 for every condition; one common export gain per pair; no EQ or manual offsets.'}
    for p in doc['pairs']:
        pair=out/'pairs'/p['id'];after=pair/'after'
        print('Automatic candidate model:',p['id'],flush=True)
        subprocess.run(['java','-Xmx2g','-Dbeatweave.cueModelId=beatweave-cue-round5-linear','-cp',cp,
            'org.metrolist.beatweave.learned.PreferenceEngineKt','plan',str(release/'beat-this-small0.onnx'),str(after),
            str(out/'cue-runtime.tsv'),str(pair/'data/track-0.mono.f32'),str(pair/'data/track-1.mono.f32')],check=True)
        choices=[('before','before',old,'old'),('fade-only','before',new,'new'),('new-cues-and-fade','after',new,'new')]
        rendered=[];item={'id':p['id'],'variants':[]}
        for name,analysis,model,kind in choices:
            result=prepared(pair,pair/analysis,model,kind,binary,pair/'renders'/name,release)
            if result is None:
                item['variants'].append({'kind':name,'status':'declined','plan':json.loads((pair/analysis/'plan.json').read_text())})
            else:rendered.append((name,*result))
        gain=min(1.,.78/max([float(abs(raw).max()) for _,raw,_ in rendered]+[1e-12]))
        for name,raw,details in rendered:
            audio=pair/f'{name}.ogg';info=encode(raw,audio,gain)
            trace=pair/f'{name}-gains.json';save(trace,details)
            item['variants'].append({'kind':name,'status':'rendered','audio':str(audio.relative_to(out)),
                'gain_trace':str(trace.relative_to(out)),'plan':details['plan'],'audio_check':info,
                'progress_gains':details['progress_gains']})
        report['pairs'].append(item);save(out/'listening-results.json',report)
        print('Rendered',p['id'],[v['kind'] for v in item['variants'] if v['status']=='rendered'],flush=True)
    print(json.dumps(report),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('release',type=Path);a=p.parse_args();compare(a.out,a.release)
