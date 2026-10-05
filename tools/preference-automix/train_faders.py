#!/usr/bin/env python3
"""Warm-start the complete fader MLP on accepted examples plus independent replay.

Fixed objective/iterations; no test-based hyperparameter or epoch selection.
SciPy L-BFGS uses explicit NumPy gradients, so a GPU/PyTorch is unnecessary.
"""
import argparse,copy,json,os,sys,time
os.environ.setdefault('OPENBLAS_NUM_THREADS','2');os.environ.setdefault('OMP_NUM_THREADS','2')
from pathlib import Path
import numpy as np
from scipy.optimize import minimize
from scipy.special import expit
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent/'handoff-automix'))
from audio_features import predict,constrained_gains
from train_handoff import save,error

ITERATIONS=160
ANCHOR=.01

def pack(model):return np.concatenate([np.asarray(l[k]).ravel() for l in model['layers'] for k in ['weight','bias']])
def unpack(theta,model):
    layers=[];offset=0
    for l in model['layers']:
        arrays=[]
        for k in ['weight','bias']:
            shape=np.shape(l[k]);n=int(np.prod(shape));arrays.append(theta[offset:offset+n].reshape(shape));offset+=n
        layers.append(arrays)
    return layers

def objective(theta,base,x,y,weights):
    layers=unpack(theta,base);h=x;inputs=[];pre=[]
    for i,(w,b) in enumerate(layers):
        inputs.append(h);z=h@w.T+b;pre.append(z);h=np.maximum(z,0) if i<len(layers)-1 else z
    sigmoid=expit(h);g=1.3*sigmoid;diff=g-y;absdiff=np.abs(diff);beta=.1
    loss=float(np.sum(weights*np.where(absdiff<beta,.5*diff**2/beta,absdiff-.5*beta)))
    d=np.clip(diff/beta,-1,1)*weights*1.3*sigmoid*(1-sigmoid)
    gradients=[]
    for i in range(len(layers)-1,-1,-1):
        w,b=layers[i];gradients.append((d.T@inputs[i],d.sum(axis=0)))
        if i:d=(d@w)*(pre[i-1]>0)
    gradient=np.concatenate([a.ravel() for pair in reversed(gradients) for a in pair])
    delta=theta-pack(base);loss+=ANCHOR*np.mean(delta**2)/2;gradient+=ANCHOR*delta/len(theta)
    return loss,gradient

def fit(base,replay,personal):
    rows=[];targets=[];weights=[]
    for group,total in [(replay,1/3),(personal,2/3)]:
        for d in group:
            rows.append(d['x']);targets.append(d['gains']);m=d['observable'].astype(float)
            weights.append(m/max(m.sum(),1)*total/len(group))
    x=np.clip((np.concatenate(rows)-base['mean'])/base['scale'],-8,8);y=np.concatenate(targets);w=np.concatenate(weights)
    started=time.monotonic();initial=pack(base)
    result=minimize(objective,initial,args=(base,x,y,w),jac=True,method='L-BFGS-B',options={'maxiter':ITERATIONS,'maxls':30,'ftol':1e-10,'gtol':1e-7})
    model=copy.deepcopy(base);model['layers']=[{'weight':w.tolist(),'bias':b.tolist()} for w,b in unpack(result.x,base)]
    model.update(kind='personalized-audio-conditioned-independent-faders-v2',production_enabled=False,
        training_source='Three explicitly accepted user demonstrations plus seven independent DJ transitions; no unseen test inputs',
        preference_examples=len(personal),optimizer='L-BFGS-B',loss='masked Huber beta=.1; personal/replay weights 2/3 and 1/3; parameter anchor .01')
    return model,{'success':bool(result.success),'message':str(result.message),'iterations':int(result.nit),'seconds':time.monotonic()-started,
        'initial_objective':objective(initial,base,x,y,w)[0],'final_objective':float(result.fun),
        'parameter_delta_l2':float(np.linalg.norm(result.x-initial)),'changed_parameters':int(np.sum(abs(result.x-initial)>1e-9))}

def metrics(model,d):
    g=constrained_gains(predict(model,d['x']));mask=d['observable'].astype(bool)
    return {'gain_mae':float(np.abs(g-d['gains'])[mask].mean()),
        'spectral_mae_db':error(d['a'],d['b'],d['y'],g) if 'y' in d else None}

def train(inputs,work,out):
    base=json.loads((inputs/'round3/handoff-model.json').read_text());manifest=json.loads((out/'replay-manifest.json').read_text())
    replay={e['id']:dict(np.load(work/f"replay-{e['id']}.npz")) for e in manifest['examples']}
    training=[replay[e['id']] for e in manifest['examples'] if e['split']=='train']
    user=[dict(np.load(work/f'user-{i}.npz')) for i in range(1,4)]
    model,optimization=fit(base,training,user);save(out/'personal-faders.json',model)
    report={'independent_user_preferences':3,'training_replay_transitions':len(training),'optimization':optimization,'personal_training':[],'leave_one_pair_out':[],'external_regression':[]}
    for i,d in enumerate(user):report['personal_training'].append({'pair':i+1,'before':metrics(base,d),'after':metrics(model,d)})
    for i,d in enumerate(user):
        fold,run=fit(base,training,[d for j,d in enumerate(user) if j!=i]);save(out/f'fader-fold-excluding-pair-{i+1}.json',fold)
        report['leave_one_pair_out'].append({'pair':i+1,'before':metrics(base,d),'after':metrics(fold,d),'optimization':run})
    for e in manifest['examples']:
        if e['split']=='train':continue
        report['external_regression'].append({'id':e['id'],'split':e['split'],'before':metrics(base,replay[e['id']]),'after':metrics(model,replay[e['id']])})
    report['limitations']=['Three pairs, not thousands of independent frame labels. Leave-one-pair-out is exploratory.',
        'The original external test mixes were already inspected last round; this is a retention regression, not a new untouched benchmark.',
        'Personal targets imitate the selected volume traces. This is not new independent expert-DJ supervision.',
        'No optimizer choices or manual per-song inference edits are based on the future audio.']
    save(out/'fader-training-results.json',report);print(json.dumps(report),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ['inputs','work','out']:p.add_argument(name,type=Path)
    a=p.parse_args();train(a.inputs,a.work,a.out)
