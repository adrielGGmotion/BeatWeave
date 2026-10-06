#!/usr/bin/env python3
"""Self-contained listening report; includes every automatic result and decline."""
import argparse,base64,html,json
from pathlib import Path

NAMES={'yes-baby-sandstorm':'yes baby → Sandstorm — Radio Edit','mantra-subway':'Mantra → Subway Surfers',
    'love-closer':'Love Me Like You Do → Closer','homicide-last-page':'Homicide Love → The Last Page',
    'yeah-no-somebody':'Yeah, No → Somebody Told Me'}
KINDS={'previous':'1 · Previous automatic cues','expanded':'2 · Expanded search, prior scorer','trained':'3 · Expanded search, trained scorer'}

def report(root):
    results=json.loads((root/'listening-results.json').read_text())
    training=json.loads((root/'cue-training-results.json').read_text());lookup={p['id']:p for p in results['pairs']}
    cards=[]
    for identifier in NAMES:
        pair=lookup[identifier];variants={v['kind']:v for v in pair['variants']};rows=[]
        for kind in KINDS:
            variant=variants[kind];p=variant['plan']
            if variant['status']=='declined':
                rows.append(f'<article><h3>{KINDS[kind]}</h3><p>Declined: {html.escape(p.get("reason","unsupported grid"))}. No rescued preview.</p></article>');continue
            audio=root/variant['audio'];encoded=base64.b64encode(audio.read_bytes()).decode()
            pre=min(6,p['a'])
            rows.append(f'<article><h3>{KINDS[kind]}</h3><p>{p["a"]:.2f}s → {p["b"]:.2f}s · {p["bars"]} bars · {p["duration"]:.2f}s overlap</p>'
                f'<audio controls preload="none" src="data:audio/ogg;base64,{encoded}"></audio><p class="small">Overlap starts {pre:.2f}s into this preview and ends {pre+p["duration"]:.2f}s in.</p></article>')
        cards.append(f'<section><h2>{NAMES[identifier]}</h2><div class="variants">'+''.join(rows)+'</div></section>')
    coverage=''.join(f'<tr><td>{NAMES[p["id"]]}</td><td>{"Yes" if p["after"]["approved_window_available"] else "No"}</td><td>{"Yes" if p["after"]["top_matches_approved"] else "No"}</td></tr>' for p in training['pairs'])
    document='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BeatWeave · Round 6</title>
<style>body{background:#10161d;color:#e6edf3;font:16px/1.55 system-ui,sans-serif;max-width:1240px;margin:0 auto;padding:36px 24px}h1{font-size:36px;line-height:1.15}h2{font-size:24px;margin:0 0 18px}h3{font-size:17px}p{max-width:980px}section{border-top:1px solid #34414e;padding:28px 0}.variants{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}article{background:#1b2530;padding:18px;border-radius:12px}audio{width:100%}.small{font-size:13px;color:#b8c6d4}.note{border-left:4px solid #edb450;padding:4px 20px;background:#27251e}table{border-collapse:collapse;width:100%;max-width:850px}th,td{text-align:left;border-bottom:1px solid #34414e;padding:12px}a{color:#8dccff}@media(max-width:850px){.variants{grid-template-columns:1fr}}</style>
<h1>BeatWeave · Round 6</h1><p>Candidate search fixes + an actual cue-model update. All previews below are automatic planner outputs. No per-song cue, offset, bar-count or EQ edits were applied.</p>
<div class="note"><p><strong>Mixed result — the new scorer is not promoted.</strong> It selects the approved Love/Closer and Yeah/Somebody starts, but misses Homicide's incoming cue and regresses on Mantra and yes baby. These are previously reviewed development songs; matching an approved cue is not proof of listening quality.</p></div>
<p>Each row separates the previous planner, the expanded candidate search with the prior scorer, and the expanded search with the newly fitted scorer. All three use the same round-5 full-span volume model and R2 renderer, no EQ, and one shared export gain per pair.</p>
<p>The outgoing fade begins at the overlap's start and reaches zero at its end; the incoming fade spans the same interval. All 14 exports pass exact decoded-duration and zero-clipping checks, and all gain traces pass strict monotonicity and exact control-endpoint checks. This does not rule out perceptual problems in beat alignment or time stretching.</p>
'''+''.join(cards)+'''
<section><h2>What changed</h2><p>Candidate acoustic checks now use observed context inside the selected transition. Quiet or irregular spans still fail. Reference-phase failures no longer discard an extra eight independently supported beats on both sides; every remaining span is re-audited. A bounded ensemble compares two existing beat-detector interpretations and preserves each original source clock. Neither beat detector was trained this round.</p>
<p>The approved Love/Closer outgoing cue at 192.16 seconds is now selectable. A supported yes baby/Sandstorm transition is available through the larger detector, although the approved exact plan remains unavailable. Mantra's exact approved opening also remains unavailable.</p>
<table><thead><tr><th>Pair</th><th>Approved window available</th><th>Trained raw top candidate matches</th></tr></thead><tbody>'''+coverage+'''</tbody></table>
<p>These raw top candidates also match the final accepted playback starts and bar counts in this run. “Available” checks both starts, both ends and bar count within documented measurement tolerances; it does not mean a synthetic reference was inserted into the candidate pool.</p></section>
<section><h2>Training evidence and limits</h2><p>The final fit optimizes all 104 linear/quadratic cue weights from the previous checkpoint; 90 change. L-BFGS converges in 21 iterations, with the regularized objective decreasing from 2.591204 to 1.499529. Only three supported approved windows supervise fitting. Homicide has weight 0.35 because its feedback was weak acceptance. Unreviewed alternatives are contrastive background, not explicit human dislikes.</p>
<p>An earlier 13-weight residual experiment using synthetic reference features failed and is retained as an ablation. The final experiment excludes unavailable references from fitting. Both experiments used a fixed 0.5 parameter anchor. The result is still too sparse to establish general musical judgment.</p>
<p>Leave-one-update-out checks retain the prior checkpoint, which already saw some personal examples. They are retention diagnostics, not clean unseen-song validation. Only Love/Closer retains an exact approved raw top choice when its latest label is omitted. The fader network and its full-span constraints are unchanged this round.</p>
<p>185 common Kotlin tests pass across separately compiled analysis and learned-beats modules. Android, native runtime integration and a T470 performance benchmark were not run. Comparing two analyses per track may require up to four normal candidate searches; the larger detector adds CPU and memory cost.</p>
<p>The source update stays in <a href="https://github.com/adrielGGmotion/BeatWeave/pull/8">draft PR #8</a>. The frozen runner defaults to the prior scorer; testing the new scorer requires the explicit experimental-cues option. Neither checkpoint is production-enabled.</p></section>
<script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})));</script></html>'''
    destination=root.parent/'BeatWeave-round6-listening.html';destination.write_text(document)
    print(destination)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('root',type=Path);args=parser.parse_args();report(args.root)
