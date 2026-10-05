#!/usr/bin/env python3
"""Controlled volume-only comparisons on the three training demonstrations."""
import argparse,json,shutil,sys
from pathlib import Path
import numpy as np
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent/'handoff-automix'))
from render_review import warped_stems
from train_handoff import save
from run_auto import envelope,encode

def render(inputs,work,out):
    feedback=json.loads((out/'feedback.json').read_text())['pairs'];previous=json.loads((inputs/'round3/listening-results.json').read_text())['pairs']
    model=json.loads((out/'personal-faders.json').read_text());release=float(np.median([p['release_width_seconds'] for p in feedback]));audio=out/'audio';audio.mkdir(exist_ok=True)
    result=[]
    for entry,pair in zip(feedback,previous):
        i=entry['pair'];v=pair['variants'][entry['ordinal']-1];plan=entry['plan']
        picked=audio/f'pair-{i}-your-pick.ogg';shutil.copy2(inputs/'round3'/v['audio'],picked)
        t,a,b,_=warped_stems(work/'data',inputs/'round3/source-clocks',work,i,plan,entry['variant']=='bar-timing')
        g,trace=envelope(model,a,b,t,plan['duration'],release)
        raw=(a*g[:,0,None]+b*g[:,1,None])/np.sqrt(2);new=audio/f'pair-{i}-preference-trained.ogg'
        info=encode(raw,new,v['export_gain']);save(out/f'pair-{i}-trained-gains.json',{'time':t[::480].tolist(),'gain':g[::480].tolist(),'model':trace})
        result.append({'pair':i,'title':entry['title'],'plan':plan,'original_audio':'audio/'+picked.name,'trained_audio':'audio/'+new.name,'audio':info,
            'manual_edits_after_inference':False,'evaluation_role':'training-set controlled audition; uses approved fixed cues to isolate learned volume, not an autonomous planning result'})
        print('Rendered preference comparison',i,flush=True)
    save(out/'listening-results.json',{'pairs':result,'release_width_seconds':release,'new_training':True,'new_unseen_song_test':False})

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ['inputs','work','out']:p.add_argument(name,type=Path)
    a=p.parse_args();render(a.inputs,a.work,a.out)
