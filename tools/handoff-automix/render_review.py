#!/usr/bin/env python3
"""Factor timing, EQ removal and learned gain changes into separate auditions.

Review-song timing remains a diagnostic, not an independently validated plan.
No EQ/filter approximation is used in any newly rendered variant.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import numpy as np
from scipy.signal import resample_poly
from audio_features import spectrum,source_features,predict,constrained_gains
from train_handoff import save
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'manual-automix'))
from render import excerpt,controls

RATE=48000
ROOT=Path(__file__).resolve().parents[2]

def warped_stems(data,clocks,work,pair,plan,align):
    pre=6.;post=6.;i=(pair-1)*2
    a=np.memmap(data/f'track-{i}.stereo.f32',dtype='<f4',mode='r').reshape(-1,2)
    b=np.memmap(data/f'track-{i+1}.stereo.f32',dtype='<f4',mode='r').reshape(-1,2)
    n=round((pre+plan['duration']+post)*RATE);t=np.arange(n)/RATE-pre
    aa=excerpt(a,plan['a']-pre,n);anchors=[]
    if align:
        ba=np.loadtxt(clocks/f'track-{i}-canonical.csv',delimiter=',',skiprows=1)[:,0]
        bb=np.loadtxt(clocks/f'track-{i+1}-canonical.csv',delimiter=',',skiprows=1)[:,0]
        ia=int(np.argmin(abs(ba-plan['a'])));ib=int(np.argmin(abs(bb-plan['b'])))
        offsets=np.arange(max(-ia,-ib),min(len(ba)-ia,len(bb)-ib))
        output_t=ba[ia+offsets]-plan['a']+pre;source_t=bb[ib+offsets]
        # Linear extension beyond available anchors; interior follows detected beats.
        def source_at(x):
            if x<output_t[0]:return source_t[0]+(x-output_t[0])*(source_t[1]-source_t[0])/(output_t[1]-output_t[0])
            if x>output_t[-1]:return source_t[-1]+(x-output_t[-1])*(source_t[-1]-source_t[-2])/(output_t[-1]-output_t[-2])
            return float(np.interp(x,output_t,source_t))
        start=source_at(0);end=source_at((n-1)/RATE);source=excerpt(b,start,round((end-start)*RATE)+1)
        anchors=[(0,0)]+[(round((s-start)*RATE),round(o*RATE)) for s,o in zip(source_t,output_t) if 0<o<(n-1)/RATE]+[(len(source)-1,n-1)]
    else:
        source=excerpt(b,plan['b']-pre*plan['speed'],round(n*plan['speed']))
        anchors=[(0,0),(len(source)-1,n-1)]
    src=work/f'pair-{pair}-input.f32';dst=work/f'pair-{pair}-stretched.f32';anchorfile=work/f'pair-{pair}-anchors.txt'
    source.tofile(src);anchorfile.write_text(''.join(f'{s} {d}\n' for s,d in anchors))
    subprocess.run([str(ROOT/'build/handoff-training/warp-r3'),str(src),str(dst),str(n),str(anchorfile)],check=True)
    warped=np.fromfile(dst,dtype='<f4').reshape(-1,2)
    if abs(len(warped)-n)>4:raise ValueError(f'Unexpected output length {len(warped)} != {n}')
    out=np.zeros_like(aa);out[:min(n,len(warped))]=warped[:n]
    return t,aa,out,anchors

def bounded_envelope(clock,g,t,duration):
    """Explicit renderer constraints, not learned policy: endpoints and 0..1 cap."""
    result=np.stack([np.interp(t,clock,g[:,i]) for i in range(2)],axis=1)
    result=np.clip(result,0,1)
    edge=min(.5,duration/4)
    entrance=.5-.5*np.cos(np.pi*np.clip(t/edge,0,1))
    exitfade=.5-.5*np.cos(np.pi*np.clip((duration-t)/edge,0,1))
    result=result*entrance[:,None]+np.array([1.,0.])*(1-entrance[:,None])
    result=result*exitfade[:,None]+np.array([0.,1.])*(1-exitfade[:,None])
    result[t<0]=[1,0];result[t>=duration]=[0,1]
    return result

def render(data,clocks,pilot,out):
    refs=json.loads((data/'references.json').read_text())['pairs']
    old=json.loads((pilot/'training-results.json').read_text())['pairs']
    templates=json.loads((pilot/'personal-transition-model.json').read_text())['automation']['templates']
    audit=json.loads((out/'user-bar-audit.json').read_text())['tracks']
    model=json.loads((out/'handoff-model.json').read_text())
    work=ROOT/'build/handoff-training/listening';work.mkdir(parents=True,exist_ok=True)
    audiodir=out/'audio';audiodir.mkdir(exist_ok=True);results=[]
    for pair,previous in zip(refs,old):
        index=pair['index'];plan=previous['learned'];variants=[]
        # Original raw render retains guessed EQ solely as an explicit old control.
        oldraw=np.fromfile(data/f'pair-{index}-learned.mix.f32',dtype='<f4').reshape(-1,2)
        variants.append({'kind':'previous-control','label':'Previous pilot, including its EQ effects',
            'plan':plan,'raw':oldraw,'timing':'previous constant speed','gain_policy':'previous template'})
        t,aa,bb,anchors=warped_stems(data,clocks,work,index,plan,False)
        def previous_gains(t,d):
            return np.stack([controls(templates,previous['automation_mixture'],t,d,side)['volume'] for side in ['outgoing','incoming']],axis=1)
        g=previous_gains(t,plan['duration']);raw=(aa*g[:,0,None]+bb*g[:,1,None])/np.sqrt(2)
        variants.append({'kind':'no-eq','label':'Same cues and volume; EQ/filter effects removed','plan':plan,'raw':raw,
            'timing':'previous constant speed','gain_policy':'previous template; EQ omitted'})
        newplan=dict(plan)
        if index!=2:
            # Agreed bar hypotheses only. The cues remain near the prior region.
            for side,key in enumerate(['a','b']):
                r=audit[(index-1)*2+side]
                if r['vote_agreement']<.75 or r['distance_to_neural_bar_seconds']>.08:raise ValueError('Conflicting bar hypotheses')
                newplan[key]=r['suggested_bar_time']
            ba=np.loadtxt(clocks/f'track-{2*(index-1)}-canonical.csv',delimiter=',',skiprows=1)[:,0]
            ia=int(np.argmin(abs(ba-newplan['a'])));end=ia+round(newplan['bars']*4)
            if end<len(ba):
                endpoint=float(ba[end]);newplan['extrapolated_end_beats']=0
            else:
                # Detector may stop in the ending tail. Limit this diagnostic
                # extension to one bar and record that no observed beat supports it.
                missing=end-(len(ba)-1)
                if missing>4:raise ValueError('Not enough detected beats for overlap')
                slope,intercept=np.polyfit(np.arange(len(ba)-16,len(ba)),ba[-16:],1)
                endpoint=float(slope*end+intercept);newplan['extrapolated_end_beats']=missing
            newplan['duration']=endpoint-float(ba[ia])
            t,aa,bb,anchors=warped_stems(data,clocks,work,index,newplan,True)
            g=previous_gains(t,newplan['duration']);raw=(aa*g[:,0,None]+bb*g[:,1,None])/np.sqrt(2)
            variants.append({'kind':'bar-timing','label':'Bar timing diagnostic; previous volume; no EQ','plan':newplan,'raw':raw,
                'timing':'detected beat anchors; phase model agrees with neural bars','gain_policy':'previous template; EQ omitted',
                'anchors':anchors})
        # Same stems as the timing comparison, or unchanged accepted timing for pair 2.
        active=(t>=0)&(t<newplan['duration'])
        reduced=[resample_poly(s[active].mean(axis=1),147,640) for s in [aa,bb]]
        clock,sa=spectrum(reduced[0]);_,sb=spectrum(reduced[1])
        x=source_features(sa,sb,clock);learned=constrained_gains(predict(model,x))
        g=bounded_envelope(clock,learned,t,newplan['duration'])
        raw=(aa*g[:,0,None]+bb*g[:,1,None])/np.sqrt(2)
        trace={'time':t[::480].tolist(),'outgoing_gain':g[::480,0].tolist(),'incoming_gain':g[::480,1].tolist(),
            'model_control_time':clock.tolist(),'model_predicted_gains_before_boundary_rules':learned.tolist()}
        save(out/f'pair-{index}-gain-trace.json',trace)
        variants.append({'kind':'trained-faders','label':'Same timing; newly trained faders; no EQ','plan':newplan,'raw':raw,
            'timing':'approved prior timing retained' if index==2 else 'same bar-timing diagnostic',
            'gain_policy':'audio-conditioned MLP, isotonic projection, explicit endpoint joins and 0..1 amplitude cap',
            'out_of_training_duration_range':True,'training_window_seconds':[58.75,73.0],
            'actual_model_window_seconds':float(clock[-1]),'anchors':anchors})
        peak=max(float(np.max(np.abs(v['raw']))) for v in variants);gain=min(1.,.78/peak)
        for v in variants:
            raw=v.pop('raw');assert np.isfinite(raw).all()
            stem=f"pair-{index}-{v['kind']}";path=work/(stem+'.f32');raw.astype('<f4').tofile(path)
            audio=audiodir/(stem+'.ogg')
            subprocess.run(['ffmpeg','-v','error','-y','-f','f32le','-ar',str(RATE),'-ac','2','-i',str(path),
                '-af',f'volume={gain}','-c:a','libvorbis','-q:a','6',str(audio)],check=True)
            decoded=np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(audio),'-f','f32le','-']),dtype='<f4')
            v.update(audio='audio/'+audio.name,frames=len(raw),export_gain=gain,peak=float(np.max(np.abs(decoded))),
                clipped_samples=int(np.sum(np.abs(decoded)>1)))
            if v['clipped_samples']:raise ValueError('Decoded clipping')
            print('Rendered',stem,'peak',round(v['peak'],3),flush=True)
        results.append({'pair':index,'title':pair['title'],'variants':variants})
    save(out/'listening-results.json',{'sample_rate':RATE,'pre_roll_seconds':6,'post_roll_seconds':6,
        'production_enabled':False,'pairs':results,'limitations':[
            'The three review pairs are development material, not held-out evaluation.',
            'The cue region and requested overlap length are inherited; no new cue-selection model is claimed.',
            'Bar phase on these songs is unannotated. Beat maps are estimates, not proof of alignment.',
            'User overlaps are shorter than the fader training windows; this is an out-of-domain audition.',
            'Each pair shares one export gain. Older control audio is re-encoded from its existing raw render.']})

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ['data','clocks','pilot','out']:p.add_argument(name,type=Path)
    a=p.parse_args();render(a.data,a.clocks,a.pilot,a.out)
