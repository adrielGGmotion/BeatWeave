"""Source-only acoustic features for a small offline gain-envelope learner."""
from pathlib import Path
import subprocess
import numpy as np
from scipy.signal import stft
from scipy.ndimage import gaussian_filter1d

RATE=11025
FPS=4
BINS=32

def decode(path,rate=RATE):
    return np.frombuffer(subprocess.check_output(['ffmpeg','-nostdin','-v','error','-i',str(path),
        '-ac','1','-ar',str(rate),'-f','f32le','-']),dtype='<f4').copy()

def spectrum(audio):
    f,t,z=stft(audio,fs=RATE,nperseg=2048,noverlap=2048-256,boundary='zeros')
    power=np.abs(z)**2
    edges=np.geomspace(35,5000,BINS+1)
    bands=np.array([power[(f>=lo)&(f<hi)].mean(axis=0) for lo,hi in zip(edges[:-1],edges[1:])])
    clock=np.arange(max(2,int(len(audio)/RATE*FPS)))/FPS
    bands=gaussian_filter1d(bands,1.5,axis=1)
    return clock,np.array([np.interp(clock,t,b) for b in bands]).T

def source_features(a,b,clock):
    """No reference mixture, target gains, song identity or Spotify data enters X."""
    q=np.linspace(0,1,len(clock))
    rows=[q,q*q,np.sin(np.pi*q),np.full(len(q),min(clock[-1]/120,2))]
    for spec in [a,b]:
        level=np.maximum(spec.mean(axis=1),1e-12)
        scale=max(float(np.median(level)),1e-10)
        bands=np.log1p(spec/(scale+.000000001))
        rows.extend(bands.T)
        loud=np.log(np.maximum(level/scale,1e-5))
        for width in [1,4,16]:
            smooth=gaussian_filter1d(loud,width)
            rows.extend([smooth,np.gradient(smooth)])
        # Local before/after contrast catches entrances and releases.
        for delta in [4,16,32]:
            rows.append(np.interp(np.arange(len(q))+delta,np.arange(len(q)),loud)-
                        np.interp(np.arange(len(q))-delta,np.arange(len(q)),loud))
    rows.append(np.log(np.maximum(a.mean(axis=1),1e-10)/np.maximum(b.mean(axis=1),1e-10)))
    return np.stack(rows,axis=1).astype(np.float32)

def isotonic(values,increasing=True):
    """Pooled adjacent violators, equal frame weights; no future labels required."""
    sign=1 if increasing else -1
    means=[];weights=[]
    for v in np.asarray(values)*sign:
        means.append(float(v));weights.append(1)
        while len(means)>1 and means[-2]>means[-1]:
            w=weights[-2]+weights[-1]
            m=(means[-2]*weights[-2]+means[-1]*weights[-1])/w
            means[-2:]=[m];weights[-2:]=[w]
    return np.repeat(means,weights)*sign

def predict(model,x):
    h=np.clip((x-np.array(model['mean']))/np.array(model['scale']),-8,8)
    for i,layer in enumerate(model['layers']):
        h=h@np.array(layer['weight']).T+np.array(layer['bias'])
        if i<len(model['layers'])-1:h=np.maximum(h,0)
    return 1.3/(1+np.exp(-np.clip(h,-50,50)))

def constrained_gains(raw):
    return np.stack([isotonic(np.clip(raw[:,0],0,1.3),False),
                     isotonic(np.clip(raw[:,1],0,1.3),True)],axis=1)
