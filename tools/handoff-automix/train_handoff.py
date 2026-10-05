#!/usr/bin/env python3
"""Learn independent gain envelopes from aligned real DJ mix/source audio.

Teacher gains are constrained acoustic reconstructions, not physical knob logs.
Sources are the authors' published ten-transition demonstration. No Spotify
capture, user-song labels or pretrained cue scores enter this training.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import numpy as np
from audio_features import decode,spectrum,source_features,predict,constrained_gains

SEED=20261006

def save(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,indent=2,allow_nan=False)+'\n')

def infer_teacher(a,b,y):
    import cvxpy as cp
    n=len(y);power=cp.Variable((n,2),nonneg=True)
    # Independently moving deck levels; no constant-sum crossfade assumption.
    scale=np.maximum(y.mean(axis=0),y.mean()*.05)
    reconstructed=cp.multiply(power[:,0,None],a)+cp.multiply(power[:,1,None],b)
    loss=cp.sum(cp.abs(cp.multiply(1/scale,reconstructed-y)))/(n*y.shape[1])
    smooth=cp.sum_squares(cp.diff(power,axis=0))/n
    constraints=[power<=1.3**2,cp.diff(power[:,0])<=0,cp.diff(power[:,1])>=0]
    prob=cp.Problem(cp.Minimize(loss+.01*smooth),constraints)
    prob.solve(solver='CLARABEL',max_iter=100)
    if prob.status not in ['optimal','optimal_inaccurate']:raise ValueError(prob.status)
    gains=np.sqrt(np.maximum(power.value,0))
    return gains,{'status':prob.status,'objective':float(prob.value)}

def error(a,b,y,gains):
    predicted=gains[:,0,None]**2*a+gains[:,1,None]**2*b
    floor=max(float(y.mean())*1e-3,1e-12)
    active=y>floor
    db=np.abs(10*np.log10(np.maximum(predicted,floor))-10*np.log10(np.maximum(y,floor)))
    return float(np.mean(db[active]))

def gain_observability(a,b):
    # The published excerpts sometimes zero-pad a source outside its available
    # region. Its fader is unidentifiable there and must not become a label.
    return np.stack([s.mean(axis=1)>max(float(np.quantile(s.mean(axis=1),.95))*1e-4,1e-12)
                     for s in [a,b]],axis=1)

def prepare(repo,work):
    public=repo/'demo/public';meta=json.loads((public/'meta.json').read_text())
    # Freeze whole-mix folds before inspecting gains or evaluation results.
    mixids=sorted({m['mixId'] for m in meta},key=lambda s:hashlib.sha256(('handoff-v1:'+s).encode()).hexdigest())
    splits={m:('test' if i<2 else 'validation' if i<3 else 'train') for i,m in enumerate(mixids)}
    examples=[];provenance=[]
    for item in meta:
        i=item['id'];arrays=[]
        for side in ['prev','next','mix']:
            p=public/'audio'/f'{i}-{side}-dj.mp3';audio=decode(p);clock,spec=spectrum(audio);arrays.append(spec)
            provenance.append({'path':str(p.relative_to(repo)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
        n=min(map(len,arrays));a,b,y=[x[:n] for x in arrays];clock=clock[:n]
        gains,diag=infer_teacher(a,b,y)
        x=source_features(a,b,clock)
        path=work/f'example-{i}.npz';np.savez_compressed(path,x=x,a=a,b=b,y=y,gains=gains,clock=clock)
        q=np.linspace(0,1,n)
        examples.append({**item,'split':splits[item['mixId']],'frames':n,'duration':float(clock[-1]),
            'teacher':diag,'teacher_spectral_mae_db':error(a,b,y,gains),
            'linear_spectral_mae_db':error(a,b,y,np.stack([1-q,q],axis=1)),
            'equal_power_spectral_mae_db':error(a,b,y,np.stack([np.cos(q*np.pi/2),np.sin(q*np.pi/2)],axis=1))})
        print('Prepared real mix example',i,splits[item['mixId']], 'teacher dB error',round(examples[-1]['teacher_spectral_mae_db'],2),flush=True)
    for s1,s2 in [('train','validation'),('train','test'),('validation','test')]:
        one={e[k] for e in examples if e['split']==s1 for k in ['prevTrackId','nextTrackId']}
        two={e[k] for e in examples if e['split']==s2 for k in ['prevTrackId','nextTrackId']}
        if one&two:raise ValueError('Recording overlap; regroup before training')
    manifest={'schema':1,'seed':SEED,'source':'https://github.com/mir-aidj/transition-analysis',
        'source_commit':'bc2ae4f1d345ab2edc0332b23afe67ad68aa07c1',
        'source_audio_redistributed':False,'examples':examples,'source_files':provenance,
        'label_definition':'Independent monotone deck amplitude gains estimated from real DJ mix power spectra. Actual fader settings and EQ cannot be uniquely recovered.',
        'folds':'Whole mixes and both source track IDs disjoint. DJ identities unavailable; distinct-DJ separation not established.'}
    save(work/'manifest.json',manifest)
    return manifest

def train(repo,work,out,time_only=False):
    import torch
    torch.set_num_threads(2);torch.set_num_interop_threads(2);torch.manual_seed(SEED)
    np.random.seed(SEED);started=time.monotonic();work.mkdir(parents=True,exist_ok=True);out.mkdir(parents=True,exist_ok=True)
    manifest=prepare(repo,work) if not (work/'manifest.json').exists() else json.loads((work/'manifest.json').read_text())
    examples=manifest['examples'];data={e['id']:dict(np.load(work/f"example-{e['id']}.npz")) for e in examples}
    if time_only:
        for d in data.values():d['x']=d['x'][:,:4].copy()
    training=[data[e['id']]['x'] for e in examples if e['split']=='train'];xx=np.concatenate(training)
    mean=xx.mean(axis=0);scale=np.maximum(xx.std(axis=0),.05)
    model=torch.nn.Sequential(torch.nn.Linear(xx.shape[1],48),torch.nn.ReLU(),torch.nn.Linear(48,24),torch.nn.ReLU(),torch.nn.Linear(24,2))
    optimizer=torch.optim.AdamW(model.parameters(),lr=.002,weight_decay=.02)
    tensors={}
    for e in examples:
        d=data[e['id']];x=np.clip((d['x']-mean)/scale,-8,8)
        tensors[e['id']]=(torch.tensor(x),torch.tensor(d['gains'],dtype=torch.float32),
                         torch.tensor(gain_observability(d['a'],d['b']),dtype=torch.float32))
    best=None;best_val=float('inf');best_epoch=0;history=[]
    # Only validation picks the epoch. Test remains unobserved until final export.
    for epoch in range(1,401):
        model.train();losses=[]
        for e in examples:
            if e['split']!='train':continue
            x,y,observable=tensors[e['id']];g=torch.sigmoid(model(x))*1.3
            loss=(torch.nn.functional.smooth_l1_loss(g,y,beta=.1,reduction='none')*observable).sum()/observable.sum().clamp(min=1)
            # Encourage smooth independent fader trajectories; never force gA+gB=1.
            delta=g[1:]-g[:-1]
            loss=loss+.1*(torch.relu(delta[:,0]).mean()+torch.relu(-delta[:,1]).mean())+.02*(delta**2).mean()
            optimizer.zero_grad();loss.backward();optimizer.step();losses.append(float(loss.detach()))
        model.eval()
        with torch.no_grad():
            values=[]
            for e in examples:
                if e['split']!='validation':continue
                x,y,observable=tensors[e['id']]
                values.append(float((torch.abs(torch.sigmoid(model(x))*1.3-y)*observable).sum()/observable.sum().clamp(min=1)))
            val=float(np.mean(values))
        if val<best_val:
            best_val=val;best_epoch=epoch;best={k:v.detach().clone() for k,v in model.state_dict().items()}
        if epoch%50==0:
            history.append({'epoch':epoch,'train_loss':float(np.mean(losses)),'validation_gain_mae':val})
            print('Handoff epoch',epoch,'train',round(np.mean(losses),4),'validation',round(val,4),flush=True)
    model.load_state_dict(best);model.eval()
    exported={'schema':1,'kind':'time-only-fader-baseline-v1' if time_only else 'audio-conditioned-independent-faders-v1','sample_rate':11025,'control_fps':4,
        'features':int(xx.shape[1]),'mean':mean.tolist(),'scale':scale.tolist(),'layers':[],'amplitude_limit':1.3,
        'postprocessing':'independent isotonic projection; gain is amplitude, spectral teacher coefficients are power',
        'training_source':'real DJ mix/source demonstration; no Spotify controls or user songs',
        'production_enabled':False}
    for layer in model:
        if isinstance(layer,torch.nn.Linear):exported['layers'].append({'weight':layer.weight.detach().numpy().tolist(),'bias':layer.bias.detach().numpy().tolist()})
    save(out/'handoff-model.json',exported)
    results=[]
    for e in examples:
        d=data[e['id']];raw=predict(exported,d['x']);g=constrained_gains(raw)
        with torch.no_grad():actual=(torch.sigmoid(model(tensors[e['id']][0]))*1.3).numpy()
        np.testing.assert_allclose(raw,actual,atol=2e-6)
        q=np.linspace(0,1,len(g));base=np.stack([1-q,q],axis=1)
        observable=gain_observability(d['a'],d['b'])
        results.append({**e,'observable_gain_labels':int(observable.sum()),
            'learned_gain_mae':float(np.abs(g-d['gains'])[observable].mean()),
            'linear_gain_mae':float(np.abs(base-d['gains'])[observable].mean()),'learned_spectral_mae_db':error(d['a'],d['b'],d['y'],g)})
        save(out/f"example-{e['id']}-curves.json",{'time':d['clock'].tolist(),'estimated_teacher':d['gains'].tolist(),
            'observable':observable.tolist(),'predicted':g.tolist(),'split':e['split']})
    summary={}
    for split in ['train','validation','test']:
        group=[e for e in results if e['split']==split]
        summary[split]={'transitions':len(group),'mixes':len({e['mixId'] for e in group}),'frames':sum(e['frames'] for e in group),
            **{k:float(np.mean([e[k] for e in group])) for k in ['learned_gain_mae','linear_gain_mae','learned_spectral_mae_db','teacher_spectral_mae_db','linear_spectral_mae_db','equal_power_spectral_mae_db']}}
    report={'seed':SEED,'input_mode':'time-only' if time_only else 'source-audio-and-time','best_epoch_by_validation':best_epoch,'training_history':history,'splits':summary,'examples':results,
        'parameters':sum(p.numel() for p in model.parameters()),'seconds':time.monotonic()-started,'export_parity':True,
        'limitations':['Ten curated examples from nine mixes are a small selection, not a broad corpus.',
            'Gain supervision is an acoustic estimate and absorbs some EQ/limiting/alignment error. Silent or zero-padded source frames are excluded from gain losses.',
            'This model predicts levels for an already aligned candidate; it does not select source cue points or establish downbeats.',
            'Spectral reconstruction and teacher imitation are not human listening-quality scores.',
            'The research audio is not redistributed and no unrestricted product-data license is asserted.']}
    save(out/'handoff-training-results.json',report);save(out/'training-manifest.json',manifest)
    print(json.dumps(summary),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('repo',type=Path);p.add_argument('work',type=Path);p.add_argument('out',type=Path)
    p.add_argument('--time-only',action='store_true',help='Matched learned baseline using only progress and duration')
    a=p.parse_args();train(a.repo,a.work,a.out,a.time_only)
