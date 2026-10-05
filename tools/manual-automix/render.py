#!/usr/bin/env python3
"""Reference-guided listening experiment with explicit, editable DSP. Not a Spotify emulator."""
import argparse
import json
import math
from pathlib import Path
import subprocess
import numpy as np
from scipy.signal import butter, sosfilt, lfilter

ROOT=Path(__file__).resolve().parents[2]
RATE=48000
CHANNELS=['volume','low','mid','high','cutoff','resonance']

def bezier(points,t,axis):
    n=len(points)-1
    return sum(math.comb(n,i)*(1-t)**(n-i)*t**i*p[axis] for i,p in enumerate(points))

def curve(raw,q,default=0.5):
    """Inverse Bezier x(t), segment-local handles, ordered right-continuous jumps."""
    y=np.full(np.shape(q),default,dtype=float)
    for s in raw.get('curves',[]):
        points=s['points']; start=s['start'];end=s['end']
        if not points: continue
        y[q>=end]=points[-1]['y']
        if end<=start: continue
        mask=(q>=start)&(q<end)
        if not mask.any():continue
        x=(q[mask]-start)/(end-start)
        lo=np.zeros_like(x);hi=np.ones_like(x)
        for _ in range(32):
            mid=(lo+hi)/2
            lower=bezier(points,mid,'x')<x
            lo=np.where(lower,mid,lo);hi=np.where(lower,hi,mid)
        y[mask]=bezier(points,(lo+hi)/2,'y')
    return np.clip(y,0,1)

def smooth(y,milliseconds=5):
    n=max(1,round(milliseconds*RATE/1000))
    padded=np.pad(y,(n-1,0),mode='edge')
    c=np.r_[0,np.cumsum(padded,dtype=float)]
    return (c[n:]-c[:-n])/n

def controls(templates,weights,t,duration,side):
    # 2 kHz curve sampling, then sample-accurate interpolation and a 5 ms causal dezipper.
    control_t=np.linspace(t[0],t[-1],int(np.ceil((t[-1]-t[0])*2000))+1)
    q=np.clip(control_t/duration,0,1)
    out={}
    for channel in CHANNELS:
        default=1 if channel=='volume' else .5
        y=sum(w*curve(template[side][channel],q,default) for w,template in zip(weights,templates))
        before=control_t<0;after=control_t>=duration
        if channel=='volume':
            y[before]=1 if side=='outgoing' else 0
            y[after]=0 if side=='outgoing' else 1
        else:y[before|after]=.5
        out[channel]=smooth(np.interp(t,control_t,y))
    return out

def eq_gain(y):
    # 0 = kill, 0.5 = unity, 1 = +6 dB. This is our own declared mapping.
    return np.where(y<=.5,2*y,10**((y-.5)*12/20))

def process_stem(x,c):
    low=sosfilt(butter(2,200,fs=RATE,output='sos'),x,axis=0)
    lowmid=sosfilt(butter(2,4000,fs=RATE,output='sos'),x,axis=0)
    y=low*eq_gain(c['low'])[:,None]+(lowmid-low)*eq_gain(c['mid'])[:,None]+(x-lowmid)*eq_gain(c['high'])[:,None]
    # RBJ biquad at 64-frame control blocks. Center is effectively open LPF;
    # values above center stay open for these explicitly low-pass reference styles.
    result=np.empty_like(y);state=np.zeros((2,2))
    for i in range(0,len(y),64):
        knob=float(c['cutoff'][i]);q=.5+1.5*float(c['resonance'][i])
        cutoff=30*(20000/30)**np.clip(2*knob,0,1)
        omega=2*np.pi*cutoff/RATE;co=np.cos(omega);al=np.sin(omega)/(2*q)
        b=np.array([(1-co)/2,1-co,(1-co)/2])/(1+al)
        a=np.array([1,-2*co/(1+al),(1-al)/(1+al)])
        result[i:i+64],state=lfilter(b,a,y[i:i+64],axis=0,zi=state)
    return result*c['volume'][:,None]/np.sqrt(2)

def excerpt(pcm,start,frames):
    index=round(start*RATE);out=np.zeros((frames,2),dtype=np.float32)
    a=max(0,index);b=min(len(pcm),index+frames)
    if b>a:out[a-index:b-index]=pcm[a:b]
    return out

def render_pair(pair,plan,kind,templates,weights,work,binary):
    pre=6.;post=6.;n=round((pre+plan['duration']+post)*RATE)
    t=np.arange(n)/RATE-pre
    a=np.memmap(work/f"track-{2*(pair['index']-1)}.stereo.f32",dtype='<f4',mode='r').reshape(-1,2)
    b=np.memmap(work/f"track-{2*(pair['index']-1)+1}.stereo.f32",dtype='<f4',mode='r').reshape(-1,2)
    aa=excerpt(a,plan['a']-pre,n)
    speed=plan['speed'];source=excerpt(b,plan['b']-pre*speed,round(n*speed))
    scratch_in=work/'stretch-input.f32'; scratch_out=work/'stretch-output.f32'
    source.tofile(scratch_in)
    subprocess.run([str(binary),str(scratch_in),str(scratch_out),str(speed)],check=True)
    stretched=np.fromfile(scratch_out,dtype='<f4').reshape(-1,2)
    # At most rounding-sample differences are expected from offline exact duration.
    if abs(len(stretched)-n)>4:raise ValueError(f'Unexpected R3 frame count {len(stretched)} != {n}')
    bb=np.zeros((n,2),dtype=np.float32);bb[:min(n,len(stretched))]=stretched[:n]
    if kind=='old':
        q=np.clip(t/plan['duration'],0,1)
        ga=np.cos(q*np.pi/2)/np.sqrt(2);gb=np.sin(q*np.pi/2)/np.sqrt(2)
        result=aa*ga[:,None]+bb*gb[:,None]
        traces={'outgoing_volume':ga*np.sqrt(2),'incoming_volume':gb*np.sqrt(2)}
    else:
        ca=controls(templates,weights,t,plan['duration'],'outgoing')
        cb=controls(templates,weights,t,plan['duration'],'incoming')
        result=process_stem(aa,ca)+process_stem(bb,cb)
        traces={f'{side}_{k}':c[k] for side,c in [('outgoing',ca),('incoming',cb)] for k in CHANNELS}
    assert np.isfinite(result).all()
    raw=work/f"pair-{pair['index']}-{kind}.mix.f32";result.astype('<f4').tofile(raw)
    trace={'time':t[::480].tolist(),**{k:v[::480].tolist() for k,v in traces.items()}}
    return {'kind':kind,'path':str(raw),'plan':plan,'frames':n,'peak':float(np.abs(result).max()),
        'rms':float(np.sqrt(np.mean(result**2))),'traces':trace}

def render(work,output):
    refs=json.loads((work/'references.json').read_text())['pairs']
    report=json.loads((output/'training-results.json').read_text())
    model=json.loads((output/'personal-transition-model.json').read_text())
    binary=ROOT/'build/manual-automix/stretch-r3'
    subprocess.run(['g++','-std=c++17','-O3','-DNDEBUG','-pthread','-I'+str(ROOT),
        str(ROOT/'tools/manual-automix/stretch.cpp'),
        str(ROOT/'rubberband/native/vendor/rubberband/single/RubberBandSingle.cpp'),'-o',str(binary)],check=True)
    for pair in refs:
        for side,s in enumerate(pair['sources']):
            path=work/f"track-{(pair['index']-1)*2+side}.stereo.f32"
            if not path.exists():subprocess.run(['ffmpeg','-v','error','-y','-i',s['path'],'-ac','2','-ar',str(RATE),'-f','f32le',str(path)],check=True)
    audio_dir=output/'audio';audio_dir.mkdir(exist_ok=True)
    render_report=[]
    for pair,item in zip(refs,report['pairs']):
        fold=json.loads((output/f"fold-excluding-pair-{pair['index']}.json").read_text())
        variants=[]
        for kind in ['old','learned','reference','held_out']:
            plan=item[kind] if kind!='reference' else {k:pair[k] for k in ['a','b','duration','speed','bars']}
            templates=model['automation']['templates'];weights=item['automation_mixture']
            if kind=='reference':templates=[pair['automation']];weights=[1.]
            if kind=='held_out':templates=fold['automation']['templates'];weights=item['held_out_automation_mixture']
            variants.append(render_pair(pair,plan,kind,templates,weights,work,binary))
        # One gain for every version of a pair: don't normalize away an unwanted energy dip.
        gain=min(1.,0.80/max(v['peak'] for v in variants))
        for v in variants:
            stem=f"pair-{pair['index']}-{v['kind']}"
            dest=audio_dir/(stem+'.ogg')
            subprocess.run(['ffmpeg','-v','error','-y','-f','f32le','-ar',str(RATE),'-ac','2',
                '-i',v['path'],'-af',f'volume={gain}', '-c:a','libvorbis','-q:a','6',str(dest)],check=True)
            encoded=subprocess.check_output(['ffmpeg','-v','error','-i',str(dest),'-f','f32le','-'])
            decoded=np.frombuffer(encoded,dtype='<f4')
            v['encoded_decoded_peak']=float(np.max(np.abs(decoded)))
            v['encoded_clipped_samples']=int((np.abs(decoded)>1).sum())
            if v['encoded_clipped_samples']:raise ValueError('Encoded sample clipping')
            v['export_gain']=gain;v['audio']='audio/'+dest.name;v.pop('path')
            (output/(stem+'-automation.json')).write_text(json.dumps(v.pop('traces'))+'\n')
            print('Rendered',stem,'peak',round(v['encoded_decoded_peak'],4),flush=True)
        render_report.append({'pair':pair['index'],'title':pair['title'],'variants':variants})
    (output/'render-results.json').write_text(json.dumps({'sample_rate':RATE,'pre_roll_seconds':6,'post_roll_seconds':6,
        'dsp':'Offline Rubber Band R3, constant incoming tempo, pitch=1; own normalized volume/EQ/LPF mapping.',
        'pairs':render_report},indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('work',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();render(a.work,a.output)
