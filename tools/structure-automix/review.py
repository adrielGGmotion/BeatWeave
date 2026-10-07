#!/usr/bin/env python3
"""Report held-out results and every automatic personal-song structure diagnostic."""
import argparse
import html
import json
from pathlib import Path
from features import sha
from infer import infer
from prepare_rwc import save


def chart(d):
    width=960;height=140;duration=d['duration'];times=d['source_clock_seconds']
    elements=['<svg viewBox="0 0 960 140" role="img" aria-label="Predicted chorus and section-boundary scores over source time">']
    for start,end in d['chorus_regions']:
        elements.append(f'<rect x="{start/duration*width:.2f}" y="0" width="{(end-start)/duration*width:.2f}" height="120" fill="#244433"/>')
    for field,color in [('chorus_score','#85e5b0'),('boundary_score','#ffc987')]:
        points=' '.join(f'{t/duration*width:.1f},{120-v*110:.1f}' for t,v in zip(times,d[field]))
        elements.append(f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="1.3"/>')
    for t in d['section_boundaries']:
        elements.append(f'<path d="M {t/duration*width:.1f} 0 V 120" stroke="#ffc987" opacity=".22"/>')
    for i in range(6):
        x=i*width/5
        elements.append(f'<text x="{max(2,min(width-36,x)):.1f}" y="138" fill="#afbecb" font-size="12">{duration*i/5:.0f}s</text>')
    return ''.join(elements)+'</svg>'


def review(root,previous):
    folder=root/'personal-diagnostics';folder.mkdir(exist_ok=False)
    pairs=json.loads((previous/'pair-labels.json').read_text())['pairs'];diagnostics=[]
    for pair in pairs:
        for i,path in enumerate(pair['sources']):
            audio=Path(path);assert sha(audio)==pair['source_sha256'][i]
            target=folder/f'{pair["id"]}-{i}.json'
            result=infer(root,audio,target);result.update(pair=pair['id'],role_in_pair='outgoing' if i==0 else 'incoming')
            diagnostics.append(result)
            print('Automatic structure:',audio.name,flush=True)
    save(root/'personal-diagnostics.json',diagnostics)
    report(root)


def report(root):
    training=json.loads((root/'training-results.json').read_text());summary=json.loads((root/'dataset-summary.json').read_text())
    selection=json.loads((root/'selection.json').read_text());diagnostics=json.loads((root/'personal-diagnostics.json').read_text())
    metrics=[]
    for task in ['chorus','boundary']:
        results=training['tasks'][task]
        for split in ['validation','test']:
            a=results['audio'][split];b=results['position'][split]
            metrics.append(f'<tr><td>{task}</td><td>{split}</td><td>{a["songs"]}</td><td>{b["macro_f1"]:.1%}</td><td>{a["macro_f1"]:.1%}</td><td>{a["macro_precision"]:.1%}</td><td>{a["macro_recall"]:.1%}</td></tr>')
    tests=[]
    for task in ['chorus','boundary']:
        a=training['tasks'][task]['audio']['test']['per_song'];b={x['id']:x for x in training['tasks'][task]['position']['test']['per_song']}
        rows=''.join(f'<tr><td>{r["id"]}</td><td>{html.escape(r["artist"])}</td><td>{b[r["id"]]["f1"]:.1%}</td><td>{r["f1"]:.1%}</td></tr>' for r in a)
        tests.append(f'<details><summary>Every test song: {task}</summary><table><thead><tr><th>Recording</th><th>Artist</th><th>Position F1</th><th>Audio F1</th></tr></thead><tbody>{rows}</tbody></table></details>')
    personal=[]
    for d in diagnostics:
        meaningful=[r for r in d['chorus_regions'] if r[1]-r[0]>=4]
        regions=', '.join(f'{a:.1f}–{b:.1f}s' for a,b in meaningful) or 'None lasting at least 4 seconds'
        personal.append(f'<section><h3>{html.escape(d["source_name"])}</h3>{chart(d)}<p>Predicted chorus regions ≥4 seconds: {regions}.</p></section>')
    nodes=sum(selection[t]['audio']['learned_nodes'] for t in ['chorus','boundary'])
    boot=''.join(f'<p>{task.title()} test F1 gain over position-only: {training["tasks"][task]["test_difference"]["mean_difference"]*100:+.1f} percentage points; artist-bootstrap 95% interval {training["tasks"][task]["test_difference"]["percentile_95_interval"][0]*100:+.1f} to {training["tasks"][task]["test_difference"]["percentile_95_interval"][1]*100:+.1f}.</p>' for task in ['chorus','boundary'])
    content='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BeatWeave — round 8 audio structure training</title><style>body{background:#101a23;color:#edf1f4;font:16px/1.6 system-ui;max-width:1060px;padding:30px;margin:auto}h1{font-size:34px;line-height:1.15}h2{margin-top:40px}section{border-top:1px solid #344c60;margin-top:25px;padding-top:12px}table{width:100%;border-collapse:collapse}td,th{text-align:left;border-bottom:1px solid #344c60;padding:8px}svg{width:100%;background:#172532;border-radius:8px}a{color:#9cd6ff}.note{padding:16px;background:#263348;border-radius:10px}details{margin:20px 0}summary{cursor:pointer}h3{overflow-wrap:anywhere}@media(max-width:650px){body{padding:18px;font-size:14px}td,th{padding:5px}table{font-size:12px}}</style><h1>Round 8: learning musical structure from audio</h1>'''
    content+=f'<p>Two new classifiers trained from scratch on RWC-P audio and human AIST structure labels: chorus membership and section boundaries. The complete corpus has {summary["songs"]} songs and {summary["frames"]:,} half-second feature windows. Training uses 57 songs, validation 15, and testing 28. Artists, recording IDs, normalized titles and byte-identical recordings stay within a single split.</p>'
    content+=f'<p>Four model sizes per task were compared against equally tuned position-only baselines: 16 fits in total. The selected audio models contain {nodes:,} learned tree nodes. Every fit, validation choice, exported model and test result is retained. Test scores were computed after the model/threshold selection was frozen.</p>'
    content+='<p class="note"><strong>These are structure models, not a newly validated complete automixer.</strong> The existing transition picks, opening rule, beat/bar gates and full-span fades were not changed. Chorus classification does not decide whether two tracks sound good together. No claim of perfect or improved audible transitions is made.</p>'
    content+='<h2>Held-out structure accuracy</h2><p>Chorus F1 is computed on half-second frames. Boundary F1 uses one-to-one matching within 3 seconds. This tolerance does not measure beat alignment. Values are averaged across songs, not across correlated windows.</p><table><thead><tr><th>Task</th><th>Split</th><th>Songs</th><th>Position F1</th><th>Audio F1</th><th>Audio precision</th><th>Audio recall</th></tr></thead><tbody>'+''.join(metrics)+'</tbody></table>'+boot
    n=training['novelty_baseline']['test']
    content+=f'<p>The separately selected untrained novelty boundary baseline scored {n["macro_f1"]:.1%} test F1. The models use timbre, chroma, relative energy, spectral change, repetition and temporal context. Full-song normalization and future context require offline analysis.</p>'+''.join(tests)
    content+='<h2>Automatic predictions on your recordings</h2><p>All ten familiar recordings are shown. None was used to train the models or choose thresholds. These diagnostics have no human reference labels, so they are not another accuracy benchmark. Green shows chorus score and shaded predicted chorus regions; orange shows section-boundary score and selected boundary peaks. Scores are uncalibrated. The source time is untouched.</p>'+''.join(personal)
    content+='<h2>What remains</h2><p>Validation across more genres, precise beat-grid alignment of structural predictions, paired-song compatibility and learned transition preferences remain separate tasks. RWC is mostly Japanese pop, and the test set covers seven held-out artist groups within this corpus. This research checkpoint is not enabled in production. Existing model-selected transitions remain unchanged.</p>'
    content+='<p>Sources: <a href="https://zenodo.org/records/18656623">RWC audio</a>, <a href="https://github.com/rwc-music/rwc-annotations">metadata</a>, <a href="https://github.com/rwc-music/rwc-annotations-archive">AIST annotations</a>. Credit Goto et al. (2002), Goto (2006), and Balke et al. (2026). Source material and these research checkpoints are kept under CC BY-NC 4.0; historical AIST notices are preserved. Source audio is not redistributed in the evidence bundle. <a href="https://github.com/adrielGGmotion/BeatWeave/pull/8">Source and experiment history</a>.</p></html>'
    destination=root.parent/'BeatWeave-round8-training-report.html';destination.write_text(content);print(destination,flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('previous',type=Path)
    a=p.parse_args();review(a.root,a.previous)
