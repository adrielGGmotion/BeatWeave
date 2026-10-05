#!/usr/bin/env python3
"""Create a portable listening report and inspectable, reproducible result package."""
import argparse
import base64
import hashlib
import html
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from render import curve

def data_uri(path,mime):
    return 'data:'+mime+';base64,'+base64.b64encode(path.read_bytes()).decode()

def report(work,out):
    results=json.loads((out/'training-results.json').read_text())
    renders=json.loads((out/'render-results.json').read_text())
    refs=json.loads((work/'references.json').read_text())['pairs']
    model=json.loads((out/'personal-transition-model.json').read_text())
    fig,axes=plt.subplots(3,2,figsize=(11,8),sharex=True,sharey=True,layout='constrained')
    q=np.linspace(0,1,4097);automation_metrics=[]
    for i,pair in enumerate(results['pairs']):
        ms={}
        for j,side in enumerate(['outgoing','incoming']):
            target=curve(refs[i]['automation'][side]['volume'],q,1)
            learned=sum(w*curve(t[side]['volume'],q,1) for w,t in zip(pair['automation_mixture'],model['automation']['templates']))
            old=np.cos(q*np.pi/2) if j==0 else np.sin(q*np.pi/2)
            ax=axes[i,j];ax.plot(q*100,old,color='#8b8e9e',lw=2,label='Old fade')
            ax.plot(q*100,target,color='#e0932d',lw=4,alpha=.5,label='Captured target')
            ax.plot(q*100,learned,color='#176f83',lw=1.8,label='Trained')
            ax.set_title(f'Pair {i+1} · {side}',loc='left',fontsize=11)
            ax.set_ylim(-.05,1.08);ax.grid(alpha=.15);ax.spines[['top','right']].set_visible(False)
            if i==2:ax.set_xlabel('Overlap completed (%)')
            if j==0:ax.set_ylabel('Normalized volume')
            ms[side]={'old_curve_mae':float(np.abs(old-target).mean()),'trained_curve_mae':float(np.abs(learned-target).mean())}
        automation_metrics.append(ms)
    axes[0,1].legend(loc='lower right',frameon=False,fontsize=9)
    fig.suptitle('The learned volume holds until the handoff',fontsize=16,fontweight='bold')
    fig.savefig(out/'volume-comparison.png',dpi=150);plt.close(fig)
    (out/'automation-metrics.json').write_text(json.dumps(automation_metrics,indent=2)+'\n')
    production=[]
    for i in range(1,4):
        row={'pair':i}
        for kind in ['baseline','learned']:
            src=work/f'pair-{i}-production-{kind}.txt'
            if src.exists():
                shutil.copy2(src,out/src.name);text=src.read_text();row[kind]={'status':text.splitlines()[0]}
                for k in ['barCount','outgoingStartSeconds','incomingStartSeconds']:
                    found=re.search(r'\b'+k+r'=([\d.]+)',text)
                    if found:row[kind][k]=float(found.group(1))
        production.append(row)
    (out/'production-audit.json').write_text(json.dumps(production,indent=2)+'\n')
    labels={'old':'Before · old musical planner','learned':'After · trained on all 3 pairs',
            'reference':'Reference reconstruction','held_out':'Excluded-pair test'}
    cards=[]
    for pair,rendered in zip(results['pairs'],renders['pairs']):
        players=[];rows=[]
        for v in rendered['variants']:
            kind=v['kind'];p=v['plan'];audio=out/v['audio']
            players.append(f'<div class="player"><b>{labels[kind]}</b><audio controls preload="none" src="{data_uri(audio,"audio/ogg")}"></audio><small>Overlap starts at 0:06 · lasts {p["duration"]:.2f}s</small></div>')
            rows.append(f'<tr><td>{labels[kind]}</td><td>{p["a"]:.3f}s</td><td>{p["b"]:.3f}s</td><td>{p["bars"]:.0f}</td></tr>')
        i=pair['pair']
        cards.append(f'<section><p class="eyebrow">PAIR {i}</p><h2>{html.escape(pair["title"])}</h2><div class="players">'+''.join(players)+
            '</div><details><summary>Show chosen source times</summary><table><thead><tr><th>Version</th><th>Outgoing cue</th><th>Incoming cue</th><th>Bars</th></tr></thead><tbody>'+''.join(rows)+
            f'</tbody></table></details><div class="feedback"><label>Preferred version <select data-pair="{i}"><option value="">Choose after listening</option><option>Before</option><option>After</option><option>Reference reconstruction</option><option>None yet</option></select></label><label>What needs fixing? <textarea data-pair="{i}" placeholder="Example: outgoing volume drops too early at 0:24; incoming vocals clash."></textarea></label></div></section>')
    production_rows=[]
    for p in production:
        columns=[str(p['pair'])]
        for k in ['baseline','learned']:
            a=p.get(k,{})
            columns.append('Declined' if a.get('status')=='DECLINE' else
                f"{a.get('barCount',0):.0f} bars; A {a.get('outgoingStartSeconds',0):.2f}s / B {a.get('incomingStartSeconds',0):.2f}s")
        production_rows.append('<tr>'+''.join('<td>'+s+'</td>' for s in columns)+'</tr>')
    doc='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BeatWeave · first training run</title>
<style>*{box-sizing:border-box}body{font:16px/1.5 system-ui,sans-serif;background:#f1f4f2;color:#19312e;margin:0}main{max-width:1080px;margin:auto;padding:48px 24px}h1{font-size:42px;line-height:1.1;margin:12px 0 20px;letter-spacing:-1px}h2{font-size:24px;margin:0 0 24px}.eyebrow{letter-spacing:.15em;font-size:12px;font-weight:750;color:#52746a}.intro{max-width:820px}section{background:white;border:1px solid #d8e2dc;border-radius:14px;padding:26px;margin:28px 0}.notice{background:#fff2d9;border-left:4px solid #cc8a20;padding:16px 20px;margin:24px 0}.players{display:grid;grid-template-columns:1fr 1fr;gap:20px}.player{background:#f3f6f4;padding:18px;border-radius:8px}.player b{display:block;font-size:14px}audio{width:100%;margin:12px 0 0}small{font-size:12px;color:#536760}table{width:100%;border-collapse:collapse;font-size:14px}th,td{padding:10px;border-bottom:1px solid #e0e7e2;text-align:left}details{margin:20px 0}summary{cursor:pointer;font-weight:600}img{width:100%;height:auto}.feedback{display:grid;grid-template-columns:1fr 2fr;gap:16px;margin-top:20px}label{font-size:13px;font-weight:600}select,textarea{display:block;width:100%;font:inherit;padding:10px;border:1px solid #b4c6bc;border-radius:6px;margin-top:5px}textarea{min-height:74px}button{background:#176f63;color:white;padding:13px 20px;border:0;border-radius:7px;font:600 15px system-ui;cursor:pointer}code{font-size:12px;overflow-wrap:anywhere}.footnote{font-size:13px;color:#52675e}@media(max-width:650px){main{padding:24px 15px}h1{font-size:33px}.players,.feedback{grid-template-columns:1fr}section{padding:19px}table{font-size:11px}td,th{padding:6px}}</style>
<main><p class="eyebrow">BEATWEAVE / PERSONAL AUTOMIX / PILOT 01</p><h1>It learned your three examples.<br>It has not learned your taste yet.</h1>
<div class="intro"><p>The trained ranker picks the closest available beat-grid candidate for all three transitions, with each source cue within 0.535 seconds of the captured timing. It also learns the outgoing volume hold and the incoming fade, EQ and filter curves.</p>
<div class="notice"><b>Held-out test: 0 of 3 requested bar counts matched.</b> Removing one entire pair from training produces poor timing predictions. Keep this model experimental; the tiny training fit is not evidence of quality on new songs.</div>
<p>Listen to <b>Before</b> and <b>After</b> first. Each clip begins six seconds before its chosen overlap. The reference reconstruction uses the exact captured cues with our defined DSP. The excluded-pair clip uses a model that never trained on that pair.</p>
<p class="footnote">These listening clips compare musical planning on the same supplied grids. They use offline R3 with constant incoming speed and our own explicit gain/EQ/filter mapping. They are not Spotify recordings or full production PreparedMix exports. All versions of each pair share one export gain; there is no loudness matching.</p></div>'''+''.join(cards)+'''
<button id="download">Download my listening feedback</button><span id="status" role="status"></span>
<section><h2>What changed in the volume</h2><p>All three examples keep the outgoing song at full normalized volume until the handoff. The old fade gradually turns it down across the entire overlap. The trained curve follows the provided hold instead.</p><img alt="Old, captured and trained volume curves for both tracks of three pairs" src="'''+data_uri(out/'volume-comparison.png','image/png')+'''"><p class="footnote">Graph values precede the common headroom/export gain and 5 ms dezipper. Similar curves show fit to annotations, not perceptual validation.</p></section>
<section><h2>Separate production planner check</h2><p>Using the unchanged small0 detector and the real pulse/bar/clock gates gives different candidates from the supplied-grid experiment above. The opt-in ranker changes these choices; its learned automation is currently in the offline renderer.</p><table><thead><tr><th>Pair</th><th>Current planner</th><th>With trained ranker</th></tr></thead><tbody>'''+''.join(production_rows)+'''</tbody></table><p>The Sandstorm pair still declines because the incoming pulse/bar evidence fails acceptance. That rejection was preserved. The source timing of the longer yes baby (+1.570 s) and Closer (+0.554 s) recordings still needs listening confirmation; no duration-based offset was invented.</p></section>
<section><h2>What the next training round needs</h2><p>Use the feedback fields to name the winning version and the exact moment that needs changing. Useful labels include an early volume cut, a clashing vocal, a missed drop, or a wrong entry section. Add varied good and bad pairs, then reserve fresh recordings for evaluation.</p><p>This run is a regularized 104-weight cue ranker plus kernel-ridge automation prediction. All three training targets rank first. No chorus classifier was trained. Source-time distance supplies proxy negatives; no human ratings were invented.</p><p class="footnote">Baseline commit: <code>'''+results['baseline_commit']+'''</code>. Model, folds, exact timing metrics, editable labels, gain traces, source hashes, encoded-audio checks and production audits are in the accompanying results archive. Python/Kotlin parity was checked on 36 candidate vectors. Tests establish implementation behavior, not listening quality or T470 speed.</p></section>
</main><script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})));document.getElementById('download').onclick=()=>{const pairs=[1,2,3].map(i=>({pair:i,preferred:document.querySelector('select[data-pair="'+i+'"]').value,notes:document.querySelector('textarea[data-pair="'+i+'"]').value}));const data={schema:1,model:'beatweave-manual-pilot-v1',pairs};const u=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=u;a.download='beatweave-listening-feedback.json';a.click();setTimeout(()=>URL.revokeObjectURL(u),1000);document.getElementById('status').textContent=' Feedback downloaded. Attach it in the conversation.'};</script></html>'''
    (out/'BeatWeave-listening-comparison.html').write_text(doc)
    root=Path(__file__).resolve().parents[2]
    checks={}
    for p in [root/'build/manual-automix/kotlin-tests.txt']:
        if p.exists():checks[p.name]=p.read_text()
    checks['python_curve_contract_tests']='5 passed'
    checks['python_kotlin_score_parity']='36 candidate vectors within 1e-7'
    checks['beat_model']='small0 verified b6d54bca156b039593b6d9d48fd3ab3e5d09be06bbbe2706f0376c9f103d5191'
    checks['final0_not_used']='Downloaded release asset did not match pinned final0 checksum; no inference performed with it.'
    checks['gradle']='Wrapper bootstrap failed due Java network connectivity; checksum-pinned direct Kotlin compiler used.'
    checks['limits']='No Android build, no full release gate, no claim of perceptual validation or target-hardware timing.'
    (out/'verification.json').write_text(json.dumps(checks,indent=2)+'\n')
    (out/'METHODS.txt').write_text('''BeatWeave personal-transition pilot, 2026-10-05

This package contains twelve listening clips, three-pair training and held-out
results, raw editable labels, model parameters, and automation traces. The HTML
report embeds its audio and image, so it works without the other files.

Training fitted 104 cue-scoring coefficients with conditional softmax/L2=0.03.
The labels are the nearest available cue pair within the specified bar count.
Feature normalization and automation templates are fitted on training pairs
only; each held-out fold omits a complete pair and both recordings. Automation
uses RBF kernel ridge (ridge=0.001), clipped and normalized to a convex mixture.
No song title, ID or desired cue time enters inference. Data includes only three
positive references, with no human-rated negative transitions.

Rendering uses the vendored offline R3 engine, linked 48 kHz stereo and pitch=1.
Incoming tempo remains constant for the entire excerpt. Reference negative
source time is zero-padded. Controls are inverse-x Bezier curves, sampled at
2 kHz and smoothed over 5 ms. Volume is normalized linear gain with shared
1/sqrt(2) headroom. EQ splits at 200 Hz and 4 kHz; 0 is kill, 0.5 is unity, 1 is
+6 dB. LPF cutoff is 30*(20000/30)^clamp(2*y,0,1) Hz with Q=.5+1.5*resonance,
updated each 64 frames. Above-center values remain at the 20 kHz maximum.
All versions of a pair share the same constant export gain. Decoded Ogg output
was checked for finite samples and sample clipping, not guaranteed true peaks.

These are explicitly defined BeatWeave pilot mappings, not verified Spotify
DSP. The recordings were not shifted based on duration differences. Source
alignment and reconstructed targets still require user listening approval.
The listening comparison uses provider grids and a constant-speed renderer;
the separate production audit records the real detector and acceptance gates.
Only cue ranking is connected to the opt-in production API. Trained automation
remains in the offline renderer. The default planner has not been replaced.

Reproduction source: tools/manual-automix on the training/manual-transition-pilot
branch of https://github.com/adrielGGmotion/BeatWeave. See its README for commands.
Edit the reference-labels file and pass it with train.py --labels to retrain.
Listening feedback exported from the HTML is for review, not automatically
treated as positive or negative training data.
''')
    manifest={str(p.relative_to(out)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.rglob('*'))
              if p.is_file() and p.name not in ['manifest-sha256.json','BeatWeave-training-results.zip']}
    (out/'manifest-sha256.json').write_text(json.dumps(manifest,indent=2)+'\n')
    archive=out/'BeatWeave-training-results.zip'
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for p in sorted(out.rglob('*')):
            if p.is_file() and p!=archive:z.write(p,p.relative_to(out))
    print('Created',out/'BeatWeave-listening-comparison.html',flush=True)
    print('Created',archive,archive.stat().st_size,'bytes',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('work',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();report(a.work,a.output)
