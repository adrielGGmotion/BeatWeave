#!/usr/bin/env python3
"""Compare unchanged round-6 weights before/after enforcing incoming opening cues."""
import argparse,base64,html,json,time
from pathlib import Path
from run_auto import save,sha,verify_release
from run_ensemble import plan,prepare,encode
from round6_report import NAMES

def compare(root,previous):
    release=root/'frozen';verify_release(release)
    faders=json.loads((release/'full-span-faders.json').read_text())
    summary={'models_retrained':False,'manual_overrides':False,'policy':{'seconds':30,'fraction':.1},'pairs':[]}
    for pair in json.loads((root/'pair-labels.json').read_text())['pairs']:
        folder=root/'pairs'/pair['id'];target=folder/'opening'
        if target.exists():raise ValueError('Do not overwrite an automatic attempt')
        start=time.monotonic();plan(folder/'data',target,release,experimental=True)
        chosen=json.loads((target/'plan.json').read_text());incoming_duration=(folder/'data/track-1.mono.f32').stat().st_size/88200
        limit=min(30.,incoming_duration*.1)
        if chosen['status']=='accepted':
            assert 0<=chosen['b']<=limit
            assert abs(chosen['incoming_start_limit_seconds']-limit)<1e-6
        item={'id':pair['id'],'incoming_duration_seconds':incoming_duration,'entry_limit_seconds':limit,
            'elapsed_seconds':time.monotonic()-start,'previous_label_allowed':pair['label']['b']<=limit,'variants':[]};rendered=[]
        for name,analysis in [('before',previous/'pairs'/pair['id']/'trained'),('opening',target)]:
            result=prepare(folder/'data',analysis,faders,release/'warp-map',folder/'renders'/name)
            if result is None:item['variants'].append({'kind':name,'status':'declined','plan':json.loads((analysis/'plan.json').read_text())})
            else:rendered.append((name,*result))
        gain=min(1.,.78/max([float(abs(raw).max()) for _,raw,_ in rendered]+[1e-12]))
        for name,raw,details in rendered:
            audio=folder/f'{name}.ogg';check=encode(raw,audio,gain);save(folder/f'{name}-gains.json',details)
            item['variants'].append({'kind':name,'status':'rendered','audio':str(audio.relative_to(root)),
                'plan':details['plan'],'audio_check':check,'gain_checks':details['gain_checks']})
        summary['pairs'].append(item);save(root/'results.json',summary)
        print(pair['id'],chosen,flush=True)
    report(root)

def report(root):
    doc=json.loads((root/'results.json').read_text());sections=[]
    for p in doc['pairs']:
        rows=[]
        for v in sorted(p['variants'],key=lambda v:v['kind']):
            title='Previous unrestricted cues' if v['kind']=='before' else 'Opening-only incoming cues'
            if v['status']=='declined':rows.append(f'<article><h3>{title}</h3><p>No supported opening transition. No later substitute.</p></article>');continue
            c=v['plan'];audio=base64.b64encode((root/v['audio']).read_bytes()).decode()
            rows.append(f'<article><h3>{title}</h3><p>Outgoing {c["a"]:.2f}s → incoming <strong>{c["b"]:.2f}s</strong><br>{c["bars"]} bars · {c["duration"]:.2f}s overlap</p><audio controls preload="none" src="data:audio/ogg;base64,{audio}"></audio></article>')
        sections.append(f'<section><h2>{html.escape(NAMES[p["id"]])}</h2><p>Incoming entry must be within 0–{p["entry_limit_seconds"]:.2f}s of the {p["incoming_duration_seconds"]:.2f}s recording.</p><div>'+''.join(rows)+'</div></section>')
    content='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BeatWeave — incoming opening fix</title><style>body{background:#111920;color:#e8eef3;font:16px/1.55 system-ui;max-width:1050px;margin:auto;padding:28px}h1{font-size:32px}section{border-top:1px solid #405363;margin-top:28px;padding-top:24px}section>div{display:grid;grid-template-columns:1fr 1fr;gap:20px}article{background:#1b2935;border-radius:12px;padding:18px}audio{width:100%}h3{font-size:18px}a{color:#9bd2ff}@media(max-width:680px){section>div{grid-template-columns:1fr}}</style><h1>Incoming songs enter near the beginning</h1><p>The automatic planner now limits incoming starts to the first 30 seconds or first 10% of the track, whichever is shorter. Timing-safe candidates compete only inside that window, including every fallback and detector alternative. A transition may continue past the window; its start must be inside it.</p><p><strong>This is a policy fix, not a new training run.</strong> Both sides use identical round-6 cue weights, beat detectors, full-span faders and R2 renderer, with one shared export gain per pair and no EQ. All cue times and bar counts are chosen automatically. Old mid-song approvals do not override this rule.</p><p>The outgoing song is still free to select a later musical phrase. Preview clips show up to six seconds before and six seconds after the transition. These familiar recordings are development checks; better entry placement does not by itself prove good musical transitions.</p>'''+''.join(sections)+'''<p>Source: <a href="https://github.com/adrielGGmotion/BeatWeave/pull/8">BeatWeave draft PR #8</a>.</p><script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})))</script></html>'''
    destination=root.parent/'BeatWeave-opening-comparison.html';destination.write_text(content);print(destination,flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('previous',type=Path)
    a=p.parse_args();compare(a.root,a.previous)
