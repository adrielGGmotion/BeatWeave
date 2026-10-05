#!/usr/bin/env python3
"""Render feedback-directed comparisons without replacing the approved control."""
import argparse
import base64
import hashlib
import html
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import zipfile
import numpy as np
from common import write_json

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('pilot_render',ROOT/'tools/manual-automix/render.py')
pilot=importlib.util.module_from_spec(spec);spec.loader.exec_module(pilot)


def render(data,previous,output):
    refs=json.loads((data/'references.json').read_text())['pairs']
    old=json.loads((previous/'training-results.json').read_text())['pairs']
    model=json.loads((previous/'personal-transition-model.json').read_text())
    proposals=json.loads((output/'proposals.json').read_text())['pairs']
    audio=output/'audio';audio.mkdir(exist_ok=True)
    report=[]
    for p,(pair,item,proposal) in enumerate(zip(refs,old,proposals)):
        if p==1:
            target=audio/'pair-2-approved-control.ogg'
            source=previous/'audio/pair-2-learned.ogg'
            shutil.copyfile(source,target)
            assert hashlib.sha256(source.read_bytes()).digest()==hashlib.sha256(target.read_bytes()).digest()
            report.append({'pair':2,'title':pair['title'],'variants':[{'kind':'approved-control','plan':item['learned'],
                'audio':'audio/'+target.name,'unchanged_sha256':hashlib.sha256(target.read_bytes()).hexdigest()}]})
            continue
        variants=[]
        for kind in ['previous','new-cue' if p==0 else 'timing-trial']:
            plan=item['learned'] if kind=='previous' else proposal['automatic_proposal' if p==0 else 'alignment_trial']
            weights=old[1]['automation_mixture'] if kind=='new-cue' else item['automation_mixture']
            v=pilot.render_pair(pair,plan,'corpus-'+kind,model['automation']['templates'],weights,data,
                                ROOT/'build/manual-automix/stretch-r3')
            v['kind']=kind;variants.append(v)
        gain=min(1.,.8/max(v['peak'] for v in variants))
        for v in variants:
            target=audio/f"pair-{p+1}-{v['kind']}.ogg"
            subprocess.run(['ffmpeg','-v','error','-y','-f','f32le','-ar','48000','-ac','2','-i',v['path'],
                '-af',f'volume={gain}','-c:a','libvorbis','-q:a','6',str(target)],check=True)
            raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(target),'-f','f32le','-'])
            decoded=np.frombuffer(raw,dtype='<f4')
            assert np.isfinite(decoded).all()
            v['decoded_peak']=float(np.max(np.abs(decoded)));v['clipped_samples']=int(np.sum(np.abs(decoded)>1))
            assert v['clipped_samples']==0
            v['audio']='audio/'+target.name;v['export_gain']=gain;v.pop('path')
            write_json(output/f"pair-{p+1}-{v['kind']}-automation.json",v.pop('traces'))
            print('Rendered',target.name,'peak',round(v['decoded_peak'],3),flush=True)
        report.append({'pair':p+1,'title':pair['title'],'variants':variants})
    write_json(output/'render-results.json',{'pre_roll_seconds':6,'post_roll_seconds':6,'sample_rate':48000,
        'renderer':'Pilot R3 constant speed; declared experimental EQ/LPF mapping. Not production PreparedMix.',
        'new_cue_automation':'Frozen automation of user-approved pilot pair 2; not a newly trained fader policy.',
        'pairs':report})


def report(output,corpus):
    phase=json.loads((output/'phase-training-results.json').read_text())
    style=json.loads((output/'dj-style-training-results.json').read_text())
    renders=json.loads((output/'render-results.json').read_text())
    props=json.loads((output/'proposals.json').read_text())
    labels={'previous':'Previous result','new-cue':'New acoustic cue proposal','approved-control':'Approved control · unchanged',
            'timing-trial':'Same transition · uncertain timing trial'}
    notes={1:'Your rating: horrible. This proposal changes the cue pair and overlap length, and retains the approved Mantra automation. It is not yet listening-approved. The phase model declined a further timing adjustment because its estimate was ambiguous.',
           2:'Your rating: the only good result. This audio is byte-for-byte identical to the approved pilot output.',
           3:'Your rating: almost good except for the offset. The incoming source cue moves forward by '+
             f"{props['pairs'][2]['alignment_only_adjustment']['proposed_incoming_shift']*1000:.1f} ms. The outgoing cue, tempo, overlap and automation stay fixed. The score margin failed the experimental automatic-adjustment guard, so this clip is an ungated diagnostic for listening only. It is not a confirmed repair or accepted production plan."}
    cards=[]
    for p in renders['pairs']:
        players=[]
        for v in p['variants']:
            a=v['plan'];encoded=base64.b64encode((output/v['audio']).read_bytes()).decode()
            players.append(f'<div class="player"><h3>{labels[v["kind"]]}</h3><audio controls preload="none" src="data:audio/ogg;base64,{encoded}"></audio><p class="meta">A {a["a"]:.3f}s · B {a["b"]:.3f}s · {a["bars"]:.0f} bars · {a["duration"]:.2f}s overlap</p></div>')
        cards.append(f'<section><small>PAIR {p["pair"]}</small><h2>{html.escape(p["title"])}</h2><p>{notes[p["pair"]]}</p><div class="players">'+''.join(players)+'</div></section>')
    test=phase['splits']['test'];dj=style['splits']['test']
    doc='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BeatWeave — corpus training review</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f4f3ef;color:#20342e;font:16px/1.6 system-ui,sans-serif}main{max-width:1020px;margin:auto;padding:42px 24px}h1{font-size:38px;line-height:1.16;letter-spacing:-1px}h2{font-size:24px}h3{font-size:15px}small{letter-spacing:.13em;font-weight:700;color:#5a6d61}section{background:#fff;padding:26px;margin:24px 0;border:1px solid #dce0d8;border-radius:12px}.players{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:16px}.player{background:#f2f5f0;padding:18px;border-radius:8px}audio{width:100%}.meta{font-size:12px;color:#566259}table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:12px 8px;border-bottom:1px solid #dce0d8}.note{border-left:4px solid #b08032;padding:12px 18px;background:#fff5df}a{color:#176853}@media(max-width:600px){main{padding:22px 14px}h1{font-size:30px}section{padding:18px}.players{grid-template-columns:1fr}}</style>
<main><small>BEATWEAVE / CORPUS TRAINING / ROUND 02</small><h1>Broader training.<br>Your listening feedback stays in control.</h1>
<p>I acquired the training material independently: 152 source excerpts and 4,690 usable DJ transitions. Your six tracks were excluded from both new model fits and held-out corpus tests.</p>
<p class="note">Timing recovery improved. Musical choice is still experimental: the timing-only transition model scores only slightly above chance. No new expert fader policy was learned.</p>
<p>Each clip has six seconds of lead-in. Before/after versions of a pair share one export gain. Listen through the handoff; there is no automatic loudness matching. The Mantra control is preserved exactly.</p>'''+''.join(cards)+f'''
<section><h2>What the new training actually achieved</h2><table><thead><tr><th>Held-out task</th><th>Result</th><th>Meaning</th></tr></thead><tbody>
<tr><td>Beat phase · {test['recordings']} recordings / {test['windows']} windows</td><td>Median {test['median_error_ms']:.1f} ms vs {test['low_band_peak_baseline_median_ms']:.1f} ms</td><td>Recovery of published automatic beat timestamps; baseline is a simple low-band onset peak, not the full previous BeatWeave model.</td></tr>
<tr><td>Phase robustness</td><td>{test['within_30ms_fraction']*100:.1f}% within 30 ms; p90 {test['p90_error_ms']:.1f} ms</td><td>Large errors remain. This does not establish human beat accuracy or good transitions.</td></tr>
<tr><td>Observed DJ choice · {dj['transitions']} transitions</td><td>Top-1 {dj['top1']*100:.1f}% vs 11.1% chance; top-3 {dj['top3']*100:.1f}%</td><td>One observed choice among eight generated alternatives. Alternatives may also sound good.</td></tr>
</tbody></table><p>Recording IDs and normalized titles are separated across train, validation and test. Mixes and DJs can cross the transition folds. No hyperparameter selection used the test results.</p></section>
<section><h2>Learned parts and retained behavior</h2><p>The new CPU-trained phase model uses audio onset features. The new DJ timing prior uses observed entry, exit and overlap choices. Both are exported as portable numeric trees and were checked against the training runtime.</p>
<p>The first pair also uses <a href="https://github.com/eth-disco/cue-detr">CUE-DETR</a>, an independently pretrained acoustic cue detector. I ran it on CPU; I did not train it. Its original training overlap with these commercial songs is unknown. Combining its scores with the weak timing prior uses explicit hand-set weights.</p>
<p>The first pair retains the normalized automation from the approved Mantra result. Pair 3 retains its original automation. These are frozen controls, not evidence that the new corpus taught DJ fader decisions, chorus recognition or your taste.</p>
<p>These listening exports use the same supplied grids and experimental offline R3 renderer as the pilot. They are not production PreparedMix exports. The production Sandstorm bar-confidence rejection remains in place; no acceptance gate was bypassed or enabled by default.</p></section>
<section><h2>Data and reproducibility</h2><p><a href="https://zenodo.org/records/1422385">UnmixDB</a> supplies audio excerpts with automatic IRCAM beat annotations. <a href="https://mir-aidj.github.io/raveform/">Raveform</a> supplies estimated alignments of tracks in real DJ mixes. Neither gives us manually rated good/bad fader curves for these examples.</p>
<p>The accompanying archive contains both new models, splits, metrics, acquisition hashes, exact proposals, automation traces and listening exports. Downloaded corpus audio and the external CUE-DETR checkpoint are not redistributed. Source-specific attribution and licensing records are included.</p>
<p>Next research step: learn audio-conditioned phrase and handoff decisions with real mix/source supervision, and test against human listening judgments. The current evidence is insufficient to call this a finished automatic DJ.</p></section></main>
<script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{{if(a!==b)b.pause()}})));</script></html>'''
    (output/'BeatWeave-corpus-listening-review.html').write_text(doc)
    for name in ['corpus-provenance.json','style-dataset-summary.json','style-queries.json','phase-windows.json']:
        shutil.copyfile(corpus/name,output/name)
    manifest=json.loads((corpus/'audio-corpus.json').read_text())
    for t in manifest['tracks']:t.pop('filename',None)
    write_json(output/'audio-corpus-manifest.json',manifest)
    shutil.copytree(corpus/'provenance',output/'source-attribution',dirs_exist_ok=True)
    hashes={str(p.relative_to(output)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.rglob('*'))
            if p.is_file() and p.suffix!='.zip' and p.name!='checksums.json'}
    write_json(output/'checksums.json',hashes)
    with zipfile.ZipFile(output/'BeatWeave-corpus-training-results.zip','w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(output.rglob('*')):
            if p.is_file() and p.suffix!='.zip':z.write(p,p.relative_to(output))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('data',type=Path);p.add_argument('previous',type=Path);p.add_argument('output',type=Path);p.add_argument('corpus',type=Path)
    a=p.parse_args();render(a.data,a.previous,a.output);report(a.output,a.corpus)
