#!/usr/bin/env python3
"""Render all recorded automatic choices with one shared export gain per pair."""
import argparse,json
from pathlib import Path
from run_auto import save
from run_ensemble import prepare,encode

def compare(out,previous):
    faders=json.loads((previous/'frozen/full-span-faders.json').read_text())
    binary=previous/'frozen/warp-map';binary.chmod(binary.stat().st_mode|0o111)
    report={'production_enabled':False,'manual_overrides':False,
        'policy':'Same round-5 full-span faders and R2 renderer in all conditions; shared export gain per pair; no EQ.',
        'evaluation':'Previously reviewed development pairs; not unseen validation.','pairs':[]}
    for p in json.loads((out/'pair-labels.json').read_text())['pairs']:
        pair=out/'pairs'/p['id'];item={'id':p['id'],'variants':[]};rendered=[]
        choices=[('previous',previous/'pairs'/p['id']/'before'),('expanded',pair/'expanded'),('trained',pair/'trained')]
        for name,analysis in choices:
            result=prepare(pair/'data',analysis,faders,binary,pair/'renders'/name)
            if result is None:item['variants'].append({'kind':name,'status':'declined','plan':json.loads((analysis/'plan.json').read_text())})
            else:rendered.append((name,*result))
        gain=min(1.,.78/max([float(abs(raw).max()) for _,raw,_ in rendered]+[1e-12]))
        for name,raw,details in rendered:
            audio=pair/f'{name}.ogg';info=encode(raw,audio,gain);trace=pair/f'{name}-gains.json';save(trace,details)
            item['variants'].append({'kind':name,'status':'rendered','audio':str(audio.relative_to(out)),
                'gain_trace':str(trace.relative_to(out)),'plan':details['plan'],'audio_check':info,
                'gain_checks':details['gain_checks'],'progress_gains':details['progress_gains']})
        report['pairs'].append(item);save(out/'listening-results.json',report)
        print('Rendered',p['id'],flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('out',type=Path);parser.add_argument('previous',type=Path)
    args=parser.parse_args();compare(args.out,args.previous)
