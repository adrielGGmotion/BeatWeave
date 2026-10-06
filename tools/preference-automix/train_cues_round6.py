#!/usr/bin/env python3
"""Fine-tune a prior cue scorer on approved windows that the planner can propose.

The frozen prior already saw earlier personal examples. Leave-one-update-out
results measure update retention, not unseen generalization from a clean model.
"""
import argparse,copy,json,os
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
from pathlib import Path
import numpy as np
from scipy.optimize import minimize
from scipy.special import logsumexp
from run_auto import save

ANCHOR=.5

def expand(z):
    return np.column_stack([z]+[(z[:,i]*z[:,j])[:,None] for i in range(13) for j in range(i,13)])

def positive(rows,label):
    return (abs(rows[:,0]-label['a'])<.031)&(abs(rows[:,1]-label['b'])<.031)&(rows[:,4]==label['bars'])&(
        abs(rows[:,0]+rows[:,2]-label['a']-label['duration'])<.15)&(
        abs(rows[:,1]+rows[:,2]*rows[:,3]-label['b']-label['duration']*label['speed'])<.15)

def load_data(root):
    pairs=json.loads((root/'pair-labels.json').read_text())['pairs'];data=[]
    for p in pairs:
        arrays=[];refs=[];scopes=[]
        for folder in sorted((root/'pairs'/p['id']/'expanded').glob('pool-*')):
            file=folder/'candidates.csv'
            if len(file.read_text().splitlines())>1:
                array=np.atleast_2d(np.loadtxt(file,delimiter=',',skiprows=1));arrays.append(array)
                scopes.append({'pool':folder.name,'candidates':len(array)})
            refs.append(np.loadtxt(folder/'approved-features.csv',delimiter=','))
        rows=np.concatenate(arrays)
        # The same acoustic candidate can occur in multiple independent detector pools.
        # Deduplicate by the complete timing/features, without rounding the values used in fit.
        _,ix=np.unique(np.round(rows[:,np.r_[0:6,7:20]],7),axis=0,return_index=True)
        rows=rows[np.sort(ix)]
        references=np.unique(np.round(np.atleast_2d(refs),10),axis=0)
        data.append({'id':p['id'],'label':p['label'],'rows':rows,'references':references,'pools':scopes})
    return data

def fit(prior,data,indices):
    examples=[]
    weights=np.array([data[i]['label']['weight'] for i in indices]);weights/=weights.sum()
    for i in indices:
        d=data[i];features=d['rows'][:,7:20]
        z=expand((features-prior['mean'])/prior['scale']);base=z@prior['weights']
        targets=positive(d['rows'],d['label']);assert targets.any()
        examples.append((z,base,targets))
    def objective(delta):
        loss=ANCHOR*np.dot(delta,delta)/2;grad=ANCHOR*delta.copy()
        for weight,(z,base,pos) in zip(weights,examples):
            score=base+z@delta;all_lse=logsumexp(score);pos_lse=logsumexp(score[pos])
            loss+=weight*(all_lse-pos_lse)
            grad+=weight*(z.T@np.exp(score-all_lse)-z[pos].T@np.exp(score[pos]-pos_lse))
        return float(loss),grad
    opt=minimize(objective,np.zeros(104),jac=True,method='L-BFGS-B',options={'maxiter':200,'gtol':1e-7,'ftol':1e-10})
    model=copy.deepcopy(prior);model['weights']=(np.array(model['weights'])+opt.x).tolist()
    model.update(model_id='beatweave-cue-round6-feasible-v1',production_enabled=False,trainable_parameters=104,
        frozen_quadratic_weights=False,anchor=ANCHOR,training_indices=indices)
    return model,{'success':bool(opt.success),'iterations':int(opt.nit),'message':str(opt.message),
        'initial_objective':objective(np.zeros(104))[0],'final_objective':float(opt.fun),
        'changed_parameters':int(np.sum(abs(opt.x)>1e-9)),'parameter_delta_l2':float(np.linalg.norm(opt.x))}

def score(model,features):return expand((features-model['mean'])/model['scale'])@model['weights']

def assess(model,d):
    rows=d['rows'];scores=score(model,rows[:,7:20]);ref=float(score(model,d['references']).max());i=int(np.argmax(scores));r=rows[i]
    label=d['label'];same=rows[rows[:,4]==label['bars']]
    floor=float(np.min(abs(same[:,0]-label['a'])+abs(same[:,1]-label['b']))) if len(same) else None
    return {'reference_feature_rank':int(np.sum(scores>ref)+1),'candidates':len(rows),
        'approved_window_available':bool(positive(rows,label).any()),'same_length_start_error_floor_seconds':floor,
        'top_raw_candidate':dict(zip(['a','b','duration','speed','bars'],map(float,r[:5]))),
        'top_matches_approved':bool(positive(rows,label)[i]),'start_error_seconds':[abs(float(r[0])-label['a']),abs(float(r[1])-label['b'])]}

def train(root,previous):
    prior=json.loads((previous/'frozen/cue-model.json').read_text());data=load_data(root)
    eligible=[i for i,d in enumerate(data) if positive(d['rows'],d['label']).any()]
    model,run=fit(prior,data,eligible);save(root/'cue-model.json',model)
    (root/'cue-runtime.tsv').write_text('\n'.join(','.join(map(str,model[k])) for k in ['mean','scale','weights'])+'\n')
    report={'training':run,'review_pairs':len(data),'positive_pairs':len(eligible),'trainable_parameters':104,'fixed_anchor':ANCHOR,
        'reference_policy':'Only supported candidates matching approved start AND end windows supervise fitting. Unavailable references are kept for coverage evaluation, not inserted as synthetic positives. Unreviewed alternatives are contrastive background.',
        'limits':['Small personal update, not broad DJ training.','The prior already saw earlier personal examples; leave-one-update-out is retention, not independent generalization.',
            'Raw candidate ranks precede clock fitting. A highly ranked unavailable reference is not a successful automatic pick.',
            'The two existing beat detectors were not trained in this round. Full-span faders are unchanged from round 5.'],'pairs':[]}
    for i,d in enumerate(data):
        fold,foldrun=fit(prior,data,[j for j in eligible if j!=i]);save(root/f'cue-fold-without-{d["id"]}.json',fold)
        report['pairs'].append({'id':d['id'],'pools':d['pools'],'before':assess(prior,d),'after':assess(model,d),
            'included_in_fit':i in eligible,'leave_one_update_out':assess(fold,d) if i in eligible else None,'fold_optimization':foldrun})
    save(root/'cue-training-results.json',report)
    print(json.dumps(report),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('previous',type=Path);a=p.parse_args();train(a.root,a.previous)
