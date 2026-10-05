"""Learn positive fade speeds; integrate them over the complete transition.

Endpoints, monotonicity, bounded speeds and smoothing are explicit playback
constraints. Audio-conditioned speed predictions are learned parameters.
"""
import copy,json,os,time
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
from pathlib import Path
import numpy as np
from scipy.ndimage import gaussian_filter1d
from scipy.optimize import minimize
from scipy.special import expit
from scipy.signal import resample_poly
from train_faders import pack,unpack
from audio_features import predict,constrained_gains,spectrum,source_features
from train_handoff import save,error

MIN_RATE=.5
MAX_RATE=1.5
SMOOTH_FRACTION=.04
ITERATIONS=160
ANCHOR=.01

def rates_to_gains(rates):
    rates=np.asarray(rates,dtype=float)
    if rates.ndim!=2 or rates.shape[1]!=2 or len(rates)<2 or not np.isfinite(rates).all():
        raise ValueError('Expected finite two-deck rate sequence')
    rates=gaussian_filter1d(np.clip(rates,MIN_RATE,MAX_RATE),max(1.,len(rates)*SMOOTH_FRACTION),axis=0)
    phase=np.vstack([np.zeros(2),np.cumsum((rates[:-1]+rates[1:])/2,axis=0)])
    phase/=phase[-1]
    gains=np.column_stack([np.cos(np.pi*phase[:,0]/2),np.sin(np.pi*phase[:,1]/2)])
    gains[0]=[1.,0.];gains[-1]=[0.,1.]
    return gains

def predict_rates(model,x):
    h=np.clip((x-np.array(model['mean']))/np.array(model['scale']),-8,8)
    for i,l in enumerate(model['layers']):
        h=h@np.array(l['weight']).T+np.array(l['bias'])
        if i<len(model['layers'])-1:h=np.maximum(h,0)
    return MIN_RATE+(MAX_RATE-MIN_RATE)*expit(h)

def full_span_envelope(model,a,b,t,duration):
    """Apply the learned curve across the exact accepted overlap endpoints."""
    active=(t>=0)&(t<duration);spectra=[]
    for stem in [a,b]:
        clock,spec=spectrum(resample_poly(stem[active].mean(axis=1),147,640));spectra.append(spec)
    x=source_features(*spectra,clock);rates=predict_rates(model,x);g=rates_to_gains(rates)
    control=np.linspace(0,duration,len(g))
    gains=np.column_stack([np.interp(t,control,g[:,k]) for k in range(2)])
    gains[t<0]=[1,0];gains[t>=duration]=[0,1]
    return gains,{'clock':control.tolist(),'gain':g.tolist(),'rate':rates.tolist()}

def teacher_rates(d):
    """Project earlier estimated DJ gains into the requested full-span family.

    These are policy-projected acoustic estimates, not recorded knob positions
    or newly supplied human volume curves. Unobservable gains are excluded.
    """
    n=len(d['gains']);q=np.linspace(0,1,n);target=np.ones((n,2));mask=d['observable'].copy()
    for k in range(2):
        valid=np.flatnonzero(mask[:,k])
        if len(valid)<4:mask[:,k]=False;continue
        gain=np.interp(np.arange(n),valid,np.clip(d['gains'][valid,k],0,1))
        phase=2/np.pi*(np.arccos(gain) if k==0 else np.arcsin(gain))
        phase=np.maximum.accumulate(phase)
        span=phase[-1]-phase[0]
        if span<.05:mask[:,k]=False;continue
        phase=(phase-phase[0])/span
        smooth=gaussian_filter1d(phase,max(1.,n*SMOOTH_FRACTION))
        smooth=(smooth-smooth[0])/(smooth[-1]-smooth[0])
        # Blend uniform progress with the estimated DJ progress, then limit
        # local rates. This explicitly removes holds and instantaneous cuts.
        target[:,k]=np.clip(.5+.5*np.gradient(smooth,q),MIN_RATE,MAX_RATE)
    return target,mask

def objective(theta,base,x,y,weights):
    layers=unpack(theta,base);h=x;inputs=[];pre=[]
    for i,(w,b) in enumerate(layers):
        inputs.append(h);z=h@w.T+b;pre.append(z);h=np.maximum(z,0) if i<len(layers)-1 else z
    sigmoid=expit(h);rate=MIN_RATE+(MAX_RATE-MIN_RATE)*sigmoid;diff=rate-y;beta=.1
    loss=float(np.sum(weights*np.where(abs(diff)<beta,.5*diff**2/beta,abs(diff)-beta/2)))
    d=weights*np.clip(diff/beta,-1,1)*(MAX_RATE-MIN_RATE)*sigmoid*(1-sigmoid);grads=[]
    for i in range(len(layers)-1,-1,-1):
        w,b=layers[i];grads.append((d.T@inputs[i],d.sum(axis=0)))
        if i:d=(d@w)*(pre[i-1]>0)
    grad=np.concatenate([a.ravel() for pair in reversed(grads) for a in pair])
    delta=theta-pack(base);loss+=ANCHOR*np.mean(delta**2)/2;grad+=ANCHOR*delta/len(theta)
    return loss,grad

def fit(base,examples,metadata):
    groups={m['mixId'] for m in metadata};xs=[];ys=[];ws=[]
    for d,m in zip(examples,metadata):
        y,mask=teacher_rates(d);weight=1/len(groups)/sum(z['mixId']==m['mixId'] for z in metadata)
        xs.append(d['x']);ys.append(y);ws.append(mask*weight/max(mask.sum(),1))
    x=np.clip((np.concatenate(xs)-base['mean'])/base['scale'],-8,8);y=np.concatenate(ys);w=np.concatenate(ws)
    initial=pack(base);start=time.monotonic()
    opt=minimize(objective,initial,args=(base,x,y,w),jac=True,method='L-BFGS-B',
        options={'maxiter':ITERATIONS,'maxls':30,'ftol':1e-10,'gtol':1e-7})
    model=copy.deepcopy(base)
    model['layers']=[{'weight':w.tolist(),'bias':b.tolist()} for w,b in unpack(opt.x,base)]
    model.update(kind='full-span-phase-speed-faders-v3',production_enabled=False,
        min_rate=MIN_RATE,max_rate=MAX_RATE,smoothing_fraction=SMOOTH_FRACTION,
        training_source='Seven independent DJ transitions from six mixes; earlier acoustic gain estimates projected into the explicit full-span policy. No new human knob labels.',
        endpoint_policy='Integrate positive rates over normalized full duration; cosine outgoing, sine incoming; no short forced release')
    return model,{'success':bool(opt.success),'message':str(opt.message),'iterations':int(opt.nit),
        'initial_objective':objective(initial,base,x,y,w)[0],'final_objective':float(opt.fun),
        'changed_parameters':int(np.sum(abs(opt.x-initial)>1e-9)),'parameter_delta_l2':float(np.linalg.norm(opt.x-initial)),
        'seconds':time.monotonic()-start}

def metrics(model,old,d,mean_rates):
    targets,mask=teacher_rates(d);target=rates_to_gains(targets);q=np.linspace(0,1,len(target))
    baseline=np.column_stack([np.interp(q,np.linspace(0,1,len(mean_rates)),mean_rates[:,k]) for k in range(2)])
    variants={'old':np.clip(constrained_gains(predict(old,d['x'])),0,1),
        'new':rates_to_gains(predict_rates(model,d['x'])),
        'equal_power':rates_to_gains(np.ones_like(targets)),
        'learned_time_only':rates_to_gains(baseline)}
    return {name:{'projected_target_gain_mae':float(abs(g-target)[mask].mean()),
        'original_teacher_gain_mae':float(abs(g-d['gains'])[d['observable']].mean()),
        'original_mix_spectral_mae_db':error(d['a'],d['b'],d['y'],g)} for name,g in variants.items()}

def train(previous,out):
    manifest=json.loads((previous/'replay-manifest.json').read_text());meta=manifest['examples']
    data={m['id']:dict(np.load(previous/f"training-features/replay-{m['id']}.npz")) for m in meta}
    train_meta=[m for m in meta if m['split']=='train'];training=[data[m['id']] for m in train_meta]
    base=json.loads((previous/'personal-faders.json').read_text())
    model,optimization=fit(base,training,train_meta);save(out/'full-span-faders.json',model)
    q=np.linspace(0,1,128);profiles=[]
    for d in training:
        rates,_=teacher_rates(d);profiles.append(np.column_stack([np.interp(q,np.linspace(0,1,len(rates)),rates[:,k]) for k in range(2)]))
    mean=np.mean(profiles,axis=0);save(out/'time-only-rates.json',mean.tolist())
    report={'optimization':optimization,'independent_training_transitions':len(training),'training_mix_groups':len({m['mixId'] for m in train_meta}),
        'source_audio_newly_downloaded':False,'source':'Previously checkpointed aligned real DJ/source spectral features',
        'target_policy':'Projected estimated gains; full-span endpoints/rate bounds are architecture rules, not learned accomplishments',
        'external_regression':[{'id':m['id'],'split':m['split'],'metrics':metrics(model,base,data[m['id']],mean)} for m in meta if m['split']!='train'],
        'limits':['External examples were inspected in earlier rounds; retention checks, not untouched tests.',
            'Seven transitions from six mixes cannot establish broad musical generalization.',
            'Old accepted user volume traces are excluded because latest feedback supersedes their abrupt endings.']}
    save(out/'full-span-training-results.json',report)
    print(json.dumps(report),flush=True)

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('previous',type=Path);p.add_argument('out',type=Path);a=p.parse_args();train(a.previous,a.out)
