#!/usr/bin/env python3
"""Frozen automatic inference: two audio paths, release directory, fresh output.

No cue, bar count, offset, meter, template or gain override is accepted. An
analysis/clock rejection is preserved; it never triggers a manually rescued mix.
"""
import argparse,datetime,hashlib,json,os,subprocess,sys
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
from pathlib import Path
import numpy as np
from scipy.signal import resample_poly
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
sys.path.insert(0,str(HERE.parent/'handoff-automix'))
from audio_features import spectrum,source_features,predict,constrained_gains
from train_handoff import save
sys.path.insert(0,str(HERE.parent/'manual-automix'))
from render import excerpt
RATE=48000

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def verify_release(release):
    manifest=json.loads((release/'freeze.json').read_text())
    for relative,expected in manifest['artifact_sha256'].items():
        p=(release/relative).resolve()
        if not p.is_relative_to(release.resolve()) or sha(p)!=expected:raise ValueError('Frozen artifact changed: '+relative)
    for relative,expected in manifest['inference_code_sha256'].items():
        p=(ROOT/relative).resolve()
        if not p.is_relative_to(ROOT) or sha(p)!=expected:raise ValueError('Frozen inference code changed: '+relative)
    return manifest

def dependencies(config):
    dest=ROOT/'.cache/verify/deps';dest.mkdir(parents=True,exist_ok=True);paths=[]
    for d in config['jvm_dependencies']:
        p=dest/d['file']
        if not p.exists():subprocess.run(['curl','-fLsS',d['url'],'-o',str(p)],check=True)
        if sha(p)!=d['sha256']:raise ValueError('Dependency checksum mismatch: '+p.name)
        paths.append(str(p))
    return paths

def envelope(model,a,b,t,duration,release_seconds):
    active=(t>=0)&(t<duration);spectra=[]
    for stem in [a,b]:
        clock,spec=spectrum(resample_poly(stem[active].mean(axis=1),147,640));spectra.append(spec)
    raw=constrained_gains(predict(model,source_features(*spectra,clock)))
    # Same frozen playback constraints for every pair. Release width is the
    # robust median measured from the three approved control traces.
    g=np.clip(np.stack([np.interp(t,clock,raw[:,i]) for i in range(2)],axis=1),0,1)
    edge=release_seconds
    entrance=.5-.5*np.cos(np.pi*np.clip(t/edge,0,1))
    exit=.5-.5*np.cos(np.pi*np.clip((duration-t)/edge,0,1))
    g=g*entrance[:,None]+np.array([1.,0.])*(1-entrance[:,None])
    g=g*exit[:,None]+np.array([0.,1.])*(1-exit[:,None])
    g[t<0]=[1,0];g[t>=duration]=[0,1]
    return g,{'time':clock.tolist(),'predicted_before_playback_bounds':raw.tolist()}

def render_map(data,plan,clock_map,binary,work):
    a=np.memmap(data/'track-0.stereo.f32',dtype='<f4',mode='r').reshape(-1,2)
    b=np.memmap(data/'track-1.stereo.f32',dtype='<f4',mode='r').reshape(-1,2)
    start=max(0.,plan['a']-6);end=plan['a']+plan['duration']+6;n=round((end-start)*RATE)
    t=np.arange(n)/RATE+start-plan['a'];aa=excerpt(a,start,n)
    source_start=float(np.interp(start,clock_map[:,0],clock_map[:,1]));source_end=float(np.interp(start+(n-1)/RATE,clock_map[:,0],clock_map[:,1]))
    source=excerpt(b,source_start,round((source_end-source_start)*RATE)+1)
    anchors=[(0,0)]+[(round((s-source_start)*RATE),round((o-start)*RATE)) for o,s in clock_map if start<o<start+(n-1)/RATE]+[(len(source)-1,n-1)]
    inp=work/'incoming-input.f32';output=work/'incoming-warped.f32';mapping=work/'render-map.txt'
    source.tofile(inp);mapping.write_text(''.join(f'{s} {d}\n' for s,d in anchors))
    subprocess.run([str(binary),str(inp),str(output),str(n),str(mapping)],check=True)
    warped=np.fromfile(output,dtype='<f4').reshape(-1,2)
    if abs(len(warped)-n)>4:raise ValueError('Renderer output duration changed')
    bb=np.zeros_like(aa);bb[:min(n,len(warped))]=warped[:n]
    return t,aa,bb

def encode(raw,dest,gain=None):
    if not np.isfinite(raw).all():raise ValueError('Nonfinite render')
    if gain is None:gain=min(1.,.78/max(float(np.abs(raw).max()),1e-12))
    subprocess.run(['ffmpeg','-nostdin','-v','error','-y','-f','f32le','-ar',str(RATE),'-ac','2','-i','pipe:0','-af',f'volume={gain}','-c:a','libvorbis','-q:a','6',str(dest)],input=raw.astype('<f4').tobytes(),check=True)
    decoded=np.frombuffer(subprocess.check_output(['ffmpeg','-nostdin','-v','error','-i',str(dest),'-f','f32le','-']),dtype='<f4')
    peak=float(np.max(abs(decoded)));clipped=int(np.sum(abs(decoded)>1))
    if clipped:raise ValueError('Decoded sample clipping')
    return {'peak':peak,'clipped_samples':clipped,'export_gain':gain,'sha256':sha(dest)}

def run(outgoing,incoming,release,out):
    manifest=verify_release(release)
    if out.exists() and any(out.iterdir()):raise ValueError('Use a fresh output directory; an existing attempt cannot be overwritten')
    out.mkdir(parents=True,exist_ok=True);config=json.loads((release/'config.json').read_text());data=out/'work';data.mkdir()
    save(out/'attempt.json',{'started_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'release_id':manifest['release_id'],
        'freeze_sha256':sha(release/'freeze.json'),'input_sha256':[sha(outgoing),sha(incoming)],'manual_overrides':False})
    for i,path in enumerate([outgoing,incoming]):
        expected=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',str(path)],text=True))
        for label,rate,channels in [('mono',22050,1),('stereo',RATE,2)]:
            decoded=data/f'track-{i}.{label}.f32'
            subprocess.run(['ffmpeg','-nostdin','-v','error','-y','-i',str(path),'-ac',str(channels),'-ar',str(rate),'-f','f32le',str(decoded)],stdin=subprocess.DEVNULL,check=True)
            if abs(decoded.stat().st_size/(rate*channels*4)-expected)>.05:raise ValueError('Incomplete decoded input')
        if abs((data/f'track-{i}.mono.f32').stat().st_size/88200-(data/f'track-{i}.stereo.f32').stat().st_size/384000)>.002:raise ValueError('Decode clock mismatch')
    cp=os.pathsep.join([str(release/'preference-engine.jar'),*dependencies(config)])
    subprocess.run(['java','-Xmx2g','-cp',cp,'org.metrolist.beatweave.learned.PreferenceEngineKt','plan',str(release/'beat-this-small0.onnx'),str(out/'analysis'),str(release/'cue-runtime.tsv'),str(data/'track-0.mono.f32'),str(data/'track-1.mono.f32')],check=True)
    plan=json.loads((out/'analysis/plan.json').read_text())
    if plan['status']!='accepted':
        save(out/'result.json',{'status':'declined','plan':plan,'manual_rescue':False});return
    clock=np.loadtxt(out/'analysis/accepted-map.csv',delimiter=',',skiprows=1)
    t,a,b=render_map(data,plan,clock,release/'warp-r3',data)
    model=json.loads((release/'personal-faders.json').read_text());g,trace=envelope(model,a,b,t,plan['duration'],config['release_seconds'])
    raw=(a*g[:,0,None]+b*g[:,1,None])/np.sqrt(2)
    info=encode(raw,out/'automatic-transition.ogg')
    save(out/'gain-trace.json',{'time':t[::480].tolist(),'gain':g[::480].tolist(),'model':trace})
    save(out/'result.json',{'status':'rendered','plan':plan,'audio':info,'eq_and_filter_processing':False,'manual_overrides':False,
        'limits':'Existing automatic cue model + preference-trained faders. Accepted clock geometry is not a human quality verdict. R3 renders a sampled accepted clock in this experimental harness.'})
    print(json.dumps({'status':'rendered','plan':plan,'peak':info['peak']}),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('outgoing',type=Path);p.add_argument('incoming',type=Path)
    p.add_argument('--release',required=True,type=Path);p.add_argument('--out',required=True,type=Path)
    a=p.parse_args();run(a.outgoing,a.incoming,a.release,a.out)
