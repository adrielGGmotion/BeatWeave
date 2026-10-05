#!/usr/bin/env python3
"""Automatic round-5 inference, with an explicitly experimental cue option.

No cue, offset, bar-count or gain override. Declines remain declines. The default
retains the preceding cue model because the new ranker's validation regressed.
"""
import argparse,datetime,json,os,subprocess
from pathlib import Path
import numpy as np
from run_auto import verify_release,dependencies,sha,save,render_map,encode,RATE
from full_span_faders import full_span_envelope

def run(outgoing,incoming,release,out,experimental_cues=False):
    manifest=verify_release(release)
    if manifest['release_id']!='beatweave-full-span-round5-v1':raise ValueError('Wrong release type')
    if out.exists() and any(out.iterdir()):raise ValueError('Use a fresh output directory')
    out.mkdir(parents=True,exist_ok=True);work=out/'work';work.mkdir()
    config=json.loads((release/'config.json').read_text())
    model_id='beatweave-cue-round5-linear' if experimental_cues else 'beatweave-manual-pilot-v1'
    cue='experimental-cue-runtime.tsv' if experimental_cues else 'cue-runtime.tsv'
    save(out/'attempt.json',{'started_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'release_id':manifest['release_id'],'freeze_sha256':sha(release/'freeze.json'),
        'input_sha256':[sha(outgoing),sha(incoming)],'cue_model_id':model_id,'manual_overrides':False})
    for i,path in enumerate([outgoing,incoming]):
        duration=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',str(path)],text=True))
        for label,rate,channels in [('mono',22050,1),('stereo',RATE,2)]:
            decoded=work/f'track-{i}.{label}.f32'
            subprocess.run(['ffmpeg','-nostdin','-v','error','-y','-i',str(path),'-ac',str(channels),'-ar',str(rate),'-f','f32le',str(decoded)],stdin=subprocess.DEVNULL,check=True)
            if abs(decoded.stat().st_size/(rate*channels*4)-duration)>.05:raise ValueError('Incomplete decoded input')
        if abs((work/f'track-{i}.mono.f32').stat().st_size/88200-(work/f'track-{i}.stereo.f32').stat().st_size/384000)>.002:raise ValueError('Decode clock mismatch')
    cp=os.pathsep.join([str(release/'preference-engine.jar'),*dependencies(config)])
    subprocess.run(['java','-Xmx2g',f'-Dbeatweave.cueModelId={model_id}','-cp',cp,
        'org.metrolist.beatweave.learned.PreferenceEngineKt','plan',str(release/'beat-this-small0.onnx'),
        str(out/'analysis'),str(release/cue),str(work/'track-0.mono.f32'),str(work/'track-1.mono.f32')],check=True)
    plan=json.loads((out/'analysis/plan.json').read_text())
    if plan['status']!='accepted':
        save(out/'result.json',{'status':'declined','plan':plan,'manual_rescue':False});return
    clock=np.loadtxt(out/'analysis/accepted-map.csv',delimiter=',',skiprows=1)
    t,a,b=render_map(work,plan,clock,release/'warp-map',work)
    model=json.loads((release/'full-span-faders.json').read_text())
    g,trace=full_span_envelope(model,a,b,t,plan['duration'])
    raw=(a*g[:,0,None]+b*g[:,1,None])/np.sqrt(2)
    info=encode(raw,out/'automatic-transition.ogg')
    decoded=subprocess.check_output(['ffmpeg','-nostdin','-v','error','-i',str(out/'automatic-transition.ogg'),'-ac','2','-ar',str(RATE),'-f','f32le','pipe:1'])
    if len(decoded)!=len(raw)*2*4:raise ValueError('Encoded preview has wrong duration')
    save(out/'gain-trace.json',{'time':t[::480].tolist(),'gain':g[::480].tolist(),'model':trace})
    save(out/'result.json',{'status':'rendered','plan':plan,'audio':info,'cue_model_id':model_id,
        'fader_model':'full-span-phase-speed-faders-v3','renderer':'R2 offline mapped',
        'eq_and_filter_processing':False,'manual_overrides':False,
        'limits':'Experimental audition pipeline. Accepted timing geometry is not a human quality verdict; new cue model is opt-in.'})
    print(json.dumps({'status':'rendered','plan':plan,'cue_model_id':model_id,'peak':info['peak']}),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('outgoing',type=Path);p.add_argument('incoming',type=Path)
    p.add_argument('--release',required=True,type=Path);p.add_argument('--out',required=True,type=Path)
    p.add_argument('--experimental-cues',action='store_true');a=p.parse_args()
    run(a.outgoing,a.incoming,a.release,a.out,a.experimental_cues)
