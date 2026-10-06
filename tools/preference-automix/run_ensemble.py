#!/usr/bin/env python3
"""Frozen two-detector automatic audition; no musical overrides.

The prior cue model is the default. --experimental-cues explicitly selects the
round-6 fitted model. Both use unchanged round-5 full-span volume envelopes.
"""
import argparse,datetime,json,os,subprocess
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
from pathlib import Path
import numpy as np
from run_auto import RATE,dependencies,render_map,save,sha,verify_release
from full_span_faders import full_span_envelope

def atomic_bytes(path,content):
    path=Path(path);temporary=path.with_name(path.name+'.tmp')
    temporary.write_bytes(content);temporary.replace(path)

def decode(source,data,index):
    expected=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',str(source)],text=True))
    durations=[]
    for kind,rate,channels in [('mono',22050,1),('stereo',RATE,2)]:
        content=subprocess.check_output(['ffmpeg','-nostdin','-v','error','-i',str(source),'-ac',str(channels),'-ar',str(rate),'-f','f32le','pipe:1'])
        duration=len(content)/(4*rate*channels)
        if abs(duration-expected)>.05:raise ValueError('Incomplete decoded source')
        if not np.isfinite(np.frombuffer(content,dtype='<f4')).all():raise ValueError('Nonfinite source')
        atomic_bytes(data/f'track-{index}.{kind}.f32',content);durations.append(duration)
    if abs(durations[0]-durations[1])>.002:raise ValueError('Decode clock mismatch')

def plan(data,target,release,experimental=False,cache=None,reference=None,collect=False):
    config=json.loads((release/'config.json').read_text())
    cp=os.pathsep.join([str(release/'preference-engine.jar'),*dependencies(config)])
    prefix='experimental-' if experimental else ''
    model=json.loads((release/f'{prefix}cue-model.json').read_text())
    properties=[]
    if cache:properties.append(f'-Dbeatweave.logitCache={cache}')
    if reference:properties.append(f'-Dbeatweave.referenceFile={reference}')
    if collect:properties.append('-Dbeatweave.collect=true')
    subprocess.run(['java','-Xmx2g',*properties,'-cp',cp,'org.metrolist.beatweave.learned.EnsembleEngineKt',
        str(target),str(release/'beat-this-small0.onnx'),str(release/'beat-this-final0.onnx'),
        str(release/f'{prefix}cue-runtime.tsv'),model['model_id'],str(data/'track-0.mono.f32'),str(data/'track-1.mono.f32')],check=True)

def prepare(data,analysis,model,binary,work):
    chosen=json.loads((analysis/'plan.json').read_text())
    if chosen['status']!='accepted':return None
    work.mkdir(parents=True,exist_ok=True)
    clock=np.loadtxt(analysis/'accepted-map.csv',delimiter=',',skiprows=1)
    t,a,b=render_map(data,chosen,clock,binary,work)
    g,trace=full_span_envelope(model,a,b,t,chosen['duration'])
    active=g[(t>=0)&(t<=chosen['duration'])];differences=np.diff(active,axis=0)
    checks={'outgoing_strictly_decreases':bool(np.all(differences[:,0]<0)),
        'incoming_strictly_increases':bool(np.all(differences[:,1]>0)),
        'control_endpoints_exact':trace['gain'][0]==[1.,0.] and trace['gain'][-1]==[0.,1.],
        'finite_and_bounded':bool(np.isfinite(g).all() and np.all((g>=0)&(g<=1)))}
    if not all(checks.values()):raise ValueError('Full-span envelope invariant failed')
    progress={str(q):[float(np.interp(q*chosen['duration'],trace['clock'],np.array(trace['gain'])[:,k])) for k in range(2)]
        for q in [0,.1,.25,.5,.75,.9,.99,1]}
    raw=(a*g[:,0,None]+b*g[:,1,None])/np.sqrt(2)
    return raw,{'plan':chosen,'trace':trace,'time':t[::480].tolist(),'gain':g[::480].tolist(),
        'gain_checks':checks,'progress_gains':progress,'plan_sha256':sha(analysis/'plan.json'),
        'clock_map_sha256':sha(analysis/'accepted-map.csv')}

def encode(raw,path,gain=None):
    if not np.isfinite(raw).all():raise ValueError('Nonfinite render')
    if gain is None:gain=min(1.,.78/max(float(abs(raw).max()),1e-12))
    content=subprocess.check_output(['ffmpeg','-nostdin','-v','error','-f','f32le','-ar',str(RATE),'-ac','2','-i','pipe:0',
        '-af',f'volume={gain}','-c:a','libvorbis','-q:a','6','-f','ogg','pipe:1'],input=raw.astype('<f4').tobytes())
    atomic_bytes(path,content)
    decoded=np.frombuffer(subprocess.check_output(['ffmpeg','-nostdin','-v','error','-i',str(path),'-f','f32le','pipe:1']),dtype='<f4')
    if len(decoded)!=raw.size:raise ValueError('Encoded audio duration changed')
    clipped=int(np.sum(abs(decoded)>1))
    if clipped or not np.isfinite(decoded).all():raise ValueError('Invalid encoded samples')
    return {'sha256':sha(path),'export_gain':gain,'decoded_frames':len(decoded)//2,
        'expected_frames':len(raw),'peak':float(abs(decoded).max()),'clipped_samples':clipped}

def run(outgoing,incoming,release,out,experimental=False):
    manifest=verify_release(release)
    if out.exists() and any(out.iterdir()):raise ValueError('Use a fresh output directory')
    out.mkdir(parents=True,exist_ok=True);data=out/'work';data.mkdir()
    save(out/'attempt.json',{'started_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'release_id':manifest['release_id'],'freeze_sha256':sha(release/'freeze.json'),
        'source_sha256':[sha(outgoing),sha(incoming)],'experimental_cues':experimental,'manual_overrides':False})
    for i,source in enumerate([outgoing,incoming]):decode(source,data,i)
    plan(data,out/'analysis',release,experimental)
    chosen=json.loads((out/'analysis/plan.json').read_text())
    if chosen['status']!='accepted':
        save(out/'result.json',{'status':'declined','plan':chosen,'manual_rescue':False});return
    result=prepare(data,out/'analysis',json.loads((release/'full-span-faders.json').read_text()),release/'warp-map',data)
    raw,details=result;audio=encode(raw,out/'automatic-transition.ogg');save(out/'gain-trace.json',details)
    save(out/'result.json',{'status':'rendered','plan':chosen,'audio':audio,'gain_checks':details['gain_checks'],
        'manual_overrides':False,'eq_and_filter_processing':False,'production_enabled':False})
    print(json.dumps({'status':'rendered','plan':chosen,'audio':audio}),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('outgoing',type=Path);parser.add_argument('incoming',type=Path)
    parser.add_argument('--release',required=True,type=Path);parser.add_argument('--out',required=True,type=Path)
    parser.add_argument('--experimental-cues',action='store_true');args=parser.parse_args()
    run(args.outgoing,args.incoming,args.release,args.out,args.experimental_cues)
