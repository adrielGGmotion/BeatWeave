#!/usr/bin/env python3
"""CPU audio features for offline musical structure, on the original source clock.

No annotations, beat predictions, track identities or chosen cues are inputs.
Whole-recording normalization and future context make this an offline analyzer.
"""
import os
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
os.environ.setdefault('OMP_NUM_THREADS','2')
import hashlib
import subprocess
from pathlib import Path
import numpy as np
from scipy.fft import rfft,dct

RATE=11025
STEP=.5
BASE=[f'mfcc_{i}' for i in range(13)]+[f'chroma_{i}' for i in range(12)]+['energy','centroid','flux']
NAMES=[f'{op}_{name}' for op in ['current','mean_2s','mean_8s','change_4s','change_8s'] for name in BASE]
NAMES += [f'novelty_{w}s_{k}' for w in [4,8] for k in ['timbre','chroma','energy']]
NAMES += ['timbre_repeat_max','timbre_repeat_top5','chroma_repeat_max','chroma_repeat_top5','position','duration_over_600']


def decode(path):
    source='pipe:0' if isinstance(path,bytes) else str(path)
    content=subprocess.check_output(['ffmpeg','-nostdin','-v','error','-i',source,'-ac','1','-ar',str(RATE),'-f','f32le','pipe:1'],
        input=path if isinstance(path,bytes) else None)
    y=np.frombuffer(content,dtype='<f4')
    assert len(y)>RATE and np.isfinite(y).all(), 'Invalid decoded audio'
    return y


def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        while block:=f.read(1024*1024):h.update(block)
    return h.hexdigest()


def mean_ranges(x,left,right):
    left=np.clip(left,0,len(x));right=np.clip(right,0,len(x))
    sums=np.vstack([np.zeros((1,x.shape[1])),np.cumsum(x,axis=0)])
    return (sums[right]-sums[left])/np.maximum(right-left,1)[:,None]


def unit(x):
    return x/np.maximum(np.linalg.norm(x,axis=1,keepdims=True),1e-8)


def extract(y):
    assert y.ndim==1 and np.isfinite(y).all()
    nfft=1024;hop=256
    if len(y)<nfft:y=np.pad(y,(0,nfft-len(y)))
    frequencies=np.fft.rfftfreq(nfft,1/RATE)
    hz_to_mel=lambda f:2595*np.log10(1+f/700)
    edges=700*(10**(np.linspace(hz_to_mel(40),hz_to_mel(RATE/2),42)/2595)-1)
    bank=np.maximum(0,np.minimum((frequencies[None,:]-edges[:-2,None])/(edges[1:-1,None]-edges[:-2,None]),
        (edges[2:,None]-frequencies[None,:])/(edges[2:,None]-edges[1:-1,None])))
    bank/=np.maximum(bank.sum(axis=1,keepdims=True),1e-9)
    chroma=np.zeros((12,len(frequencies)))
    use=(frequencies>=65)&(frequencies<=4000)
    notes=np.rint(69+12*np.log2(frequencies[use]/440)).astype(int)%12
    chroma[notes,np.flatnonzero(use)]=1
    frames=np.lib.stride_tricks.sliding_window_view(y,nfft)[::hop]
    count=int(np.ceil(len(y)/RATE/STEP));acc=np.zeros((count,28));counts=np.zeros(count)
    previous=None
    for start in range(0,len(frames),512):
        frame=frames[start:start+512]
        power=abs(rfft(frame*np.hanning(nfft),axis=1))**2
        mel=10*np.log10(np.maximum(power@bank.T,1e-10))
        cepstra=dct(mel,type=2,norm='ortho',axis=1)[:,:13]
        chrom=power@chroma.T;chrom/=np.maximum(chrom.sum(axis=1,keepdims=True),1e-9)
        energy=10*np.log10(np.maximum(np.mean(frame.astype(float)**2,axis=1),1e-12))
        centroid=(power@frequencies)/np.maximum(power.sum(axis=1),1e-9)/(RATE/2)
        earlier=np.vstack([mel[0] if previous is None else previous,mel[:-1]])
        flux=np.maximum(mel-earlier,0).mean(axis=1);previous=mel[-1]
        values=np.column_stack([cepstra,chrom,energy,centroid,flux])
        times=(np.arange(start,start+len(frame))*hop+nfft/2)/RATE
        bins=np.minimum((times/STEP).astype(int),count-1)
        np.add.at(acc,bins,values);np.add.at(counts,bins,1)
    valid=counts>0
    if not valid.all():
        # At most an incomplete tail bin; interpolate without changing the clock.
        for j in range(acc.shape[1]):acc[~valid,j]=np.interp(np.flatnonzero(~valid),np.flatnonzero(valid),acc[valid,j]/counts[valid])
        counts[~valid]=1
    base=acc/counts[:,None]
    # Per-recording normalization is unsupervised and identically applied at inference.
    base=(base-base.mean(axis=0))/np.maximum(base.std(axis=0),.1)
    base=np.clip(base,-8,8)
    index=np.arange(count);parts=[base]
    for seconds in [2,8]:
        h=int(seconds/STEP/2);parts.append(mean_ranges(base,index-h,index+h+1))
    novelty=[]
    for seconds in [4,8]:
        h=int(seconds/STEP)
        before=mean_ranges(base,index-h,index);after=mean_ranges(base,index,index+h)
        diff=after-before;parts.append(diff)
        novelty.extend([np.linalg.norm(diff[:,1:13],axis=1),np.linalg.norm(diff[:,13:25],axis=1),abs(diff[:,25])])
    parts.append(np.column_stack(novelty))
    smoothed=mean_ranges(base,index-2,index+3)
    repeat=[]
    outside=abs(index[:,None]-index[None,:])*STEP>=20
    for columns in [slice(1,13),slice(13,25)]:
        u=unit(smoothed[:,columns]);similarity=u@u.T
        similarity=np.where(outside,similarity,-1.)
        top=np.partition(similarity,-min(5,count),axis=1)[:,-min(5,count):]
        repeat.extend([top.max(axis=1),top.mean(axis=1)])
    times=(index+.5)*STEP;duration=len(y)/RATE
    parts.append(np.column_stack([*repeat,times/duration,np.full(count,duration/600)]))
    x=np.column_stack(parts).astype(np.float32)
    assert x.shape==(count,len(NAMES)) and np.isfinite(x).all()
    return times,x
