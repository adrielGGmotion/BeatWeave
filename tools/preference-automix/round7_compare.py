#!/usr/bin/env python3
"""First automatic outputs of an immutable timing-trained experimental checkpoint."""
import argparse
import base64
import html
import json
from pathlib import Path
import shutil
import time
from run_auto import save,sha,verify_release
from run_ensemble import plan,prepare,encode
from round6_report import NAMES


def compare(root,previous):
    release=root/'frozen';verify_release(release);verify_release(previous/'frozen')
    pairs=json.loads((previous/'pair-labels.json').read_text())['pairs']
    shutil.copy2(previous/'pair-labels.json',root/'pair-labels.json')
    faders=json.loads((release/'full-span-faders.json').read_text())
    summary=dict(models_retrained=True,manual_overrides=False,freeze_sha256=sha(release/'freeze.json'),pairs=[])
    for pair in pairs:
        folder=root/'pairs'/pair['id'];folder.mkdir(parents=True,exist_ok=False)
        data=previous/'pairs'/pair['id']/'data';target=folder/'trained'
        save(folder/'attempt.json',dict(model_sha256=sha(release/'experimental-cue-model.json'),
            source_sha256=pair['source_sha256'],decoded_sha256={p.name:sha(p) for p in data.glob('*.f32')},manual_overrides=False))
        start=time.monotonic();plan(data,target,release,experimental=True)
        chosen=json.loads((target/'plan.json').read_text());duration=(data/'track-1.mono.f32').stat().st_size/88200
        limit=min(30.,duration*.1)
        if chosen['status']=='accepted':assert 0<=chosen['b']<=limit
        item=dict(id=pair['id'],entry_limit_seconds=limit,elapsed_seconds=time.monotonic()-start,variants=[])
        rendered=[]
        shutil.copytree(previous/'pairs'/pair['id']/'opening',folder/'before-analysis')
        for name,analysis in [('before',folder/'before-analysis'),('trained',target)]:
            result=prepare(data,analysis,faders,release/'warp-map',folder/'renders'/name)
            if result is None:item['variants'].append(dict(kind=name,status='declined',plan=json.loads((analysis/'plan.json').read_text())))
            else:rendered.append((name,*result))
        gain=min(1.,.78/max([float(abs(raw).max()) for _,raw,_ in rendered]+[1e-12]))
        for name,raw,details in rendered:
            audio=folder/f'{name}.ogg';check=encode(raw,audio,gain);save(folder/f'{name}-gains.json',details)
            item['variants'].append(dict(kind=name,status='rendered',audio=str(audio.relative_to(root)),
                plan=details['plan'],audio_check=check,gain_checks=details['gain_checks']))
        summary['pairs'].append(item);save(root/'results.json',summary)
        print(pair['id'],json.dumps(chosen),flush=True)
    report(root)


def report(root):
    doc=json.loads((root/'results.json').read_text());training=json.loads((root/'training-results.json').read_text())
    data=json.loads((root/'dataset-summary.json').read_text());sections=[]
    for p in doc['pairs']:
        rows=[]
        for v in sorted(p['variants'],key=lambda v:v['kind']):
            title='Previous opening model' if v['kind']=='before' else 'Round 7 trained model'
            if v['status']=='declined':
                rows.append(f'<article><h3>{title}</h3><p>No supported opening transition. Decline preserved.</p></article>');continue
            c=v['plan'];audio=base64.b64encode((root/v['audio']).read_bytes()).decode()
            rows.append(f'<article><h3>{title}</h3><p>Outgoing {c["a"]:.2f}s → incoming <strong>{c["b"]:.2f}s</strong><br>{c["bars"]} bars · {c["duration"]:.2f}s overlap</p><audio controls preload="none" src="data:audio/ogg;base64,{audio}"></audio></article>')
        sections.append(f'<section><h2>{html.escape(NAMES[p["id"]])}</h2><p>Incoming start limit: {p["entry_limit_seconds"]:.2f}s.</p><div>'+''.join(rows)+'</div></section>')
    metrics=''.join(f'<tr><td>{s}</td><td>{v["before"]["queries"]}</td><td>{v["before"]["top1"]:.1%}</td><td>{v["after"]["top1"]:.1%}</td><td>{v["before"]["mean_rank"]:.2f} → {v["after"]["mean_rank"]:.2f}</td></tr>' for s,v in training['splits'].items())
    content='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BeatWeave — round 7 training</title><style>body{background:#111920;color:#e8eef3;font:16px/1.55 system-ui;max-width:1050px;margin:auto;padding:28px}h1{font-size:32px}section{border-top:1px solid #405363;margin-top:28px;padding-top:24px}section>div{display:grid;grid-template-columns:1fr 1fr;gap:20px}article{background:#1b2935;border-radius:12px;padding:18px}audio{width:100%}h3{font-size:18px}a{color:#9bd2ff}table{border-collapse:collapse;width:100%}td,th{text-align:left;padding:8px;border-bottom:1px solid #405363}@media(max-width:680px){section>div{grid-template-columns:1fr}}</style><h1>Round 7: opening-aware timing training</h1>'''
    content+=f'<p>Actual optimization updated {training["training"]["changed_parameters"]} timing coefficients; the other 90 cue coefficients, beat detectors, full-span faders and renderer are unchanged. The corpus contains {data["retained_transitions"]:,} usable alignment-derived transitions, plus one supported personal approval for retention.</p>'
    content+='<p>The corpus teaches outgoing/incoming position and overlap length. It contains no audio for this fit, so this is not chorus, similarity or beat-detector training. The corpus objective holds acoustic features at their mean; the audio comparisons below test the full scorer. Both sides keep incoming starts within the first 30 seconds or 10% of a track, whichever is shorter. Outgoing cues may be later in their songs.</p>'
    content+='<h2>Timing imitation check</h2><p>Recover the estimated recorded choice among eight generated alternatives. Alternatives can also be good. These numbers measure timing imitation, not musical quality. Recordings (IDs and normalized titles) and mixes are disjoint across these splits; DJ identity and alternate masters are not fully controlled. Raveform was used in earlier experiments, so this is not a never-seen benchmark. No hyperparameters were selected from these results.</p><table><thead><tr><th>Split</th><th>Transitions</th><th>Before top 1</th><th>After top 1</th><th>Mean rank</th></tr></thead><tbody>'+metrics+'</tbody></table>'
    content+='<h2>Every automatic first output</h2><p>These five familiar song pairs are development checks. No timestamp, bar count, EQ, gain curve or clock was manually adjusted. Declines are shown. Both versions use the same export gain per pair, with no EQ. Preview clips contain up to six seconds before and after the transition. The outgoing fader decreases across the entire transition and reaches zero at its end.</p>'+''.join(sections)
    content+='<p>Sources: <a href="https://mir-aidj.github.io/raveform/">Raveform</a> · <a href="https://huggingface.co/datasets/taejunkim/raveform">dataset</a> · <a href="https://github.com/adrielGGmotion/BeatWeave/pull/8">draft PR #8</a>. Models remain experimental.</p><script>document.querySelectorAll("audio").forEach(a=>a.addEventListener("play",()=>document.querySelectorAll("audio").forEach(b=>{if(a!==b)b.pause()})))</script></html>'
    destination=root.parent/'BeatWeave-round7-comparison.html';destination.write_text(content);print(destination,flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('previous',type=Path)
    a=p.parse_args();compare(a.root,a.previous)
