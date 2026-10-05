#!/usr/bin/env python3
"""Recover the exact audition picks and build source-only supervised examples."""
import argparse,hashlib,json,subprocess,sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import numpy as np
from scipy.signal import resample_poly
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
sys.path.insert(0,str(HERE.parent/'handoff-automix'))
from audio_features import decode,spectrum,source_features
from render_review import warped_stems
from train_handoff import save,gain_observability

PICKS=[(1,3,'bar-timing'),(2,2,'no-eq'),(3,3,'bar-timing')]

def prepare(inputs,work,out):
    work.mkdir(parents=True,exist_ok=True);out.mkdir(parents=True,exist_ok=True)
    source_dir=inputs/'sources'
    prefixes=['Madison Beer','Darude','JENNIE','Subway Surfers','Ellie Goulding','The Chainsmokers']
    sources=[next(source_dir.glob(p+'*.opus')) for p in prefixes]
    data=work/'data';data.mkdir(exist_ok=True)
    def convert(item):
        i,p=item
        expected=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',str(p)],text=True))
        for label,rate,channels in [('mono',22050,1),('stereo',48000,2)]:
            dest=data/f'track-{i}.{label}.f32'
            if not dest.exists() or abs(dest.stat().st_size/(rate*channels*4)-expected)>.05:
                temporary=dest.with_suffix('.tmp')
                subprocess.run(['ffmpeg','-nostdin','-v','error','-y','-i',str(p),'-ac',str(channels),'-ar',str(rate),'-f','f32le',str(temporary)],stdin=subprocess.DEVNULL,check=True)
                if abs(temporary.stat().st_size/(rate*channels*4)-expected)>.05:raise ValueError('Incomplete decoded source')
                temporary.replace(dest)
        if abs((data/f'track-{i}.mono.f32').stat().st_size/88200-(data/f'track-{i}.stereo.f32').stat().st_size/384000)>.002:raise ValueError('Decode clock mismatch')
    with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(convert,enumerate(sources)))
    previous=json.loads((inputs/'round3/listening-results.json').read_text())
    old=json.loads((inputs/'pilot/training-results.json').read_text())['pairs']
    feedback=[]
    for index,ordinal,kind in PICKS:
        pair=previous['pairs'][index-1];picked=pair['variants'][ordinal-1]
        if picked['kind']!=kind:raise ValueError('Audition ordering changed')
        clip=inputs/'round3'/picked['audio'];plan=picked['plan']
        t,a,b,anchors=warped_stems(data,inputs/'round3/source-clocks',work,index,plan,kind=='bar-timing')
        active=(t>=0)&(t<plan['duration']);spectra=[]
        for stem in [a,b]:
            clock,s=spectrum(resample_poly(stem[active].mean(axis=1),147,640));spectra.append(s)
        x=source_features(*spectra,clock)
        traces=json.loads((inputs/f'pilot/pair-{index}-learned-automation.json').read_text());old_d=old[index-1]['learned']['duration']
        target_time=clock/plan['duration']*old_d
        target=np.stack([np.interp(target_time,traces['time'],traces[k]) for k in ['outgoing_volume','incoming_volume']],axis=1)
        np.savez_compressed(work/f'user-{index}.npz',x=x,a=spectra[0],b=spectra[1],gains=target,clock=clock,observable=gain_observability(*spectra))
        # Infer the short end-release width from the approved rendered control trace.
        tt=np.array(traces['time']);gg=np.array(traces['outgoing_volume']);tail=tt>old_d*.8
        levels=gg[tail][::-1];times=tt[tail][::-1]
        release=float(np.interp(.05,levels,times)-np.interp(.95,levels,times))
        feedback.append({'pair':index,'ordinal':ordinal,'variant':kind,'plan':plan,'title':pair['title'],
            'selected_audio_sha256':hashlib.sha256(clip.read_bytes()).hexdigest(),
            'source_sha256':[hashlib.sha256(p.read_bytes()).hexdigest() for p in sources[(index-1)*2:index*2]],
            'source_paths':[str(p.resolve()) for p in sources[(index-1)*2:index*2]],'release_width_seconds':release,
            'label':'accepted demonstration; no rating invented for unauditioned candidates',
            'frames':len(clock),'gain_target':'previous pilot amplitude traces, time-normalized to the selected timing; no EQ/filter labels'})
        print('Prepared approved pair',index,'frames',len(clock),'end release',round(release,4),flush=True)
    save(out/'feedback.json',{'schema':1,'user_feedback':'Pair 1 third nearly perfect; pair 2 second worked well; pair 3 third best.',
        'pairs':feedback,'new_independent_preferences':3,'spotify_controls_read_for_training':False,
        'note':'The preferred legacy volume traces originally derive from the user-designed transitions. This is explicitly personal imitation training.'})
    # Replay original estimated supervision without fitting teachers again or using test labels in fitting.
    manifest=json.loads((inputs/'round3/training-manifest.json').read_text())
    public=ROOT/'build/transition-analysis/demo/public'
    for e in manifest['examples']:
        arrays=[]
        for side in ['prev','next','mix']:
            path=public/'audio'/f"{e['id']}-{side}-dj.mp3"
            rel=str(path.relative_to(ROOT/'build/transition-analysis'))
            expected=next(z['sha256'] for z in manifest['source_files'] if z['path']==rel)
            if hashlib.sha256(path.read_bytes()).hexdigest()!=expected:raise ValueError('Replay recording changed')
            clock,s=spectrum(decode(path));arrays.append(s)
        gains=np.array(json.loads((inputs/f"round3/example-{e['id']}-curves.json").read_text())['estimated_teacher'])
        n=len(gains);a,b,y=[s[:n] for s in arrays];clock=clock[:n]
        np.savez_compressed(work/f"replay-{e['id']}.npz",x=source_features(a,b,clock),a=a,b=b,y=y,gains=gains,clock=clock,observable=gain_observability(a,b))
    save(out/'replay-manifest.json',manifest)
    save(work/'source-files.json',[str(p.resolve()) for p in sources])

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ['inputs','work','out']:p.add_argument(name,type=Path)
    a=p.parse_args();prepare(a.inputs,a.work,a.out)
