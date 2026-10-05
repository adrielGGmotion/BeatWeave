"""Train a low-capacity cue ranker from exact accepted acoustic windows.

Unreviewed alternatives are contrastive background, not human-rated negatives.
No unreviewed 16-bar choice is relabeled as preferred.
"""
import argparse,json,os,sys
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'handoff-automix'))
import numpy as np
from scipy.optimize import minimize
from scipy.special import logsumexp
from train_handoff import save

REGULARIZATION=.5

def positive(rows,label):
    return (abs(rows[:,0]-label['a'])<.031)&(abs(rows[:,1]-label['b'])<.031)&(rows[:,4]==label['bars'])

def fit(candidates,labels,references,indices):
    raw=np.concatenate([np.vstack([candidates[i][:,7:],references[i]]) for i in indices]);mean=raw.mean(axis=0);scale=np.maximum(raw.std(axis=0),.05)
    xs=[(np.vstack([candidates[i][:,7:],references[i]])-mean)/scale for i in indices]
    ps=[np.r_[positive(candidates[i],labels[i]),True] for i in indices]
    weights=np.array([labels[i]['weight'] for i in indices]);weights/=weights.sum()
    def objective(w):
        loss=REGULARIZATION*np.dot(w,w)/2;grad=REGULARIZATION*w.copy()
        for x,p,weight in zip(xs,ps,weights):
            s=x@w;total=logsumexp(s);pos=logsumexp(s[p])
            loss+=weight*(total-pos)
            grad+=weight*(x.T@np.exp(s-total)-x[p].T@np.exp(s[p]-pos))
        return loss,grad
    opt=minimize(objective,np.zeros(13),jac=True,method='L-BFGS-B',options={'maxiter':200,'ftol':1e-10,'gtol':1e-7})
    # The existing Kotlin scorer supports a quadratic expansion; zero those
    # terms deliberately to reduce capacity for only five independent pairs.
    model={'model_id':'beatweave-cue-round5-linear','mean':mean.tolist(),'scale':scale.tolist(),
        'weights':np.r_[opt.x,np.zeros(91)].tolist(),'feature_expansion':'standardized linear terms; quadratic weights explicitly zero',
        'regularization':REGULARIZATION,'training_indices':indices,'production_enabled':False}
    return model,{'success':bool(opt.success),'iterations':int(opt.nit),'loss':float(opt.fun),'message':str(opt.message)}

def scores(model,rows):
    z=(rows[:,7:]-model['mean'])/model['scale'];return z@np.array(model['weights'][:13])

def reference_score(model,ref):
    z=(ref-np.array(model['mean']))/np.array(model['scale'])
    expanded=np.r_[z,[z[i]*z[j] for i in range(len(z)) for j in range(i,len(z))]]
    return float(expanded@model['weights'])

def assess(rows,label,score,ref_score):
    p=positive(rows,label);best=int(np.argmax(score));row=rows[best]
    same=rows[rows[:,4]==label['bars']]
    floor=float(np.min(abs(same[:,0]-label['a'])+abs(same[:,1]-label['b']))) if len(same) else None
    return {'reference_feature_rank':int(np.sum(score>ref_score)+1),'candidate_count':len(rows),
        'reference_rank_fraction':float(np.sum(score>ref_score)/len(rows)),
        'approved_cue_in_supported_candidates':bool(p.any()),'same_length_combined_cue_error_floor_seconds':floor,
        'top1_matches_approved':bool(p[best]),'outgoing_error_seconds':float(abs(row[0]-label['a'])),
        'incoming_error_seconds':float(abs(row[1]-label['b'])),'bars_match':bool(row[4]==label['bars']),
        'top_candidate':dict(zip(['a','b','duration','speed','bars'],map(float,row[:5])))}

def train(root):
    labels=json.loads((root/'pair-labels.json').read_text());pairs=labels['pairs']
    old=json.loads((root/'previous/frozen/cue-model.json').read_text())
    candidates=[];scopes=[]
    for p in pairs:
        path=root/'pairs'/p['id']/'before/candidates.csv'
        if len(path.read_text().splitlines())>1:
            candidates.append(np.loadtxt(path,delimiter=',',skiprows=1));scopes.append('supported planner shortlist, before final clock fitting')
        else:
            # A declined grid supplies no automatic candidates. Its exact
            # positive acoustic window can still supervise musical preference
            # against other detected-grid windows, without permitting playback.
            raw=np.loadtxt(root/'pairs'/p['id']/'acoustic-background/pair-1-candidates.csv',delimiter=',',skiprows=1)
            z=(raw[:,6:]-old['mean'])/old['scale']
            expanded=np.column_stack([z]+[(z[:,i]*z[:,j])[:,None] for i in range(13) for j in range(i,13)])
            candidates.append(np.column_stack([raw[:,:6],expanded@old['weights'],raw[:,6:]]))
            scopes.append('acoustic background only; automatic planner declined this grid')
    references=[np.loadtxt(root/'pairs'/p['id']/'before/approved-features.csv',delimiter=',') for p in pairs]
    targets=[p['label'] for p in pairs];indices=list(range(len(pairs)))
    model,optimization=fit(candidates,targets,references,indices);save(root/'cue-model.json',model)
    (root/'cue-runtime.tsv').write_text('\n'.join(','.join(map(str,model[k])) for k in ['mean','scale','weights'])+'\n')
    report={'independent_positive_pairs':len(pairs),'optimization':optimization,'feature_weights_nonzero':int(np.sum(abs(np.array(model['weights']))>1e-9)),
        'labels':'Exact approved cues; unreviewed candidates are contrastive background, not listener rejections.',
        'grid_limitations':labels['grid_limitations'],'pairs':[],
        'limitations':['Five personal preferences only; the Homicide pair has reduced weight because its feedback was lukewarm.',
            'Exact labeled acoustic windows can teach the scorer even when the bar-support grid excludes them. They are never inserted into playback candidates.',
            'Leave-one-pair-out is exploratory. Reference-feature rank includes off-grid references and is not an automatic selection success rate. Candidate export precedes final clock fitting.',
            'No chorus/drop classifier labels or preferred 16-bar demonstration were added.']}
    for i,p in enumerate(pairs):
        fold,run=fit(candidates,targets,references,[j for j in indices if j!=i]);save(root/f'cue-fold-without-{p["id"]}.json',fold)
        report['pairs'].append({'id':p['id'],'candidate_scope':scopes[i],'before':assess(candidates[i],targets[i],candidates[i][:,6],reference_score(old,references[i])),
            'training_fit':assess(candidates[i],targets[i],scores(model,candidates[i]),reference_score(model,references[i])),
            'leave_one_pair_out':assess(candidates[i],targets[i],scores(fold,candidates[i]),reference_score(fold,references[i])),'fold_optimization':run})
    save(root/'cue-training-results.json',report);print(json.dumps(report),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);a=p.parse_args();train(a.out)
