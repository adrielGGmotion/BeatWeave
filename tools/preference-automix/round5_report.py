#!/usr/bin/env python3
"""Build a self-contained listening report from preserved automatic results."""
import argparse,base64,html,json
from pathlib import Path

TITLES={'love-closer':'Love Me Like You Do → Closer','homicide-last-page':'Homicide Love → The Last Page',
    'yeah-no-somebody':'Yeah, No → Somebody Told Me','yes-baby-sandstorm':'yes baby → Sandstorm',
    'mantra-subway':'Mantra → Subway Surfers'}
LABELS={'before':('01 · Previous','Previous automatic cues + previous faders'),
    'fade-only':('02 · New fades','Same automatic cues + new trained faders'),
    'new-cues-and-fade':('03 · Experimental cues','New automatic cue model + new trained faders')}

def report(root):
    read=lambda n:json.loads((root/n).read_text())
    listening=read('listening-results.json');fade=read('full-span-training-results.json')
    cues=read('cue-training-results.json');verification=read('decode-verification.json')
    esc=html.escape;cards=[]
    order=['yeah-no-somebody','homicide-last-page','love-closer','mantra-subway','yes-baby-sandstorm']
    by_id={p['id']:p for p in listening['pairs']}
    for pid in order:
        pair=by_id[pid];players=[]
        for v in pair['variants']:
            label,description=LABELS[v['kind']];plan=v['plan']
            if v['status']!='rendered':
                players.append(f'<div class="variant"><h3>{label}</h3><p>{description}</p><p class="decline">Declined: {esc(plan["reason"])}</p></div>');continue
            encoded=base64.b64encode((root/v['audio']).read_bytes()).decode()
            players.append(f'<div class="variant {"focus" if v["kind"]=="fade-only" else ""}"><h3>{label}</h3><p>{description}</p>'
                f'<audio controls preload="none" src="data:audio/ogg;base64,{encoded}"></audio>'
                f'<p class="timing"><b>{plan["bars"]} bars · {plan["duration"]:.2f} s overlap</b><br>'
                f'Source cues: {plan["a"]:.2f} s → {plan["b"]:.2f} s<br>Transition in preview: 0:06 → {6+plan["duration"]:.2f} s</p></div>')
        notes={'yeah-no-somebody':'Both selectors accept the same 8-bar plan. This round does not force 16 bars.',
            'homicide-last-page':'Both selectors accept the same plan. Your earlier lukewarm approval receives reduced cue-training weight.',
            'love-closer':'The new selector changes the incoming section. The previously approved outgoing cue is 2.50 s outside the nearest same-length supported candidate.',
            'mantra-subway':'Cue regression: the experimental selector chooses only 2 bars. Neither selector reproduces the previously approved incoming start at 0 s; the supported candidate grid excludes it.',
            'yes-baby-sandstorm':'The automatic planner still rejects the inferred bar grid. No preview is manually rescued. The approved acoustic window supplies a training label only.'}
        cards.append(f'<section class="pair"><h2>{esc(TITLES[pid])}</h2><p>{esc(notes[pid])}</p><div class="players">{"".join(players)}</div></section>')
    metric_rows=[]
    for row in fade['external_regression']:
        metric_rows.append('<tr><td>'+esc(f"{row['split']} {row['id']}")+'</td>'+''.join(f'<td>{row["metrics"][v]["projected_target_gain_mae"]:.4f}</td>' for v in ['old','new','equal_power','learned_time_only'])+'</tr>')
    rank_rows=[]
    for p in cues['pairs']:
        fit=p['leave_one_pair_out'];top=fit['top_candidate'];floor=fit['same_length_combined_cue_error_floor_seconds']
        rank_rows.append(f'<tr><td>{esc(TITLES[p["id"]])}</td><td>{top["a"]:.2f} → {top["b"]:.2f}</td><td>{top["bars"]:.0f}</td><td>{fit["outgoing_error_seconds"]:.2f} / {fit["incoming_error_seconds"]:.2f}</td><td>{floor:.2f}</td></tr>')
    optimization=fade['optimization']
    doc='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BeatWeave · Round 5 listening review</title><style>
:root{color-scheme:light;--ink:#172c38;--muted:#4f616b;--line:#d7dfde;--accent:#12634f}*{box-sizing:border-box}body{margin:0;background:#f2f4ef;color:var(--ink);font:16px/1.55 system-ui,sans-serif}main{max-width:1160px;margin:auto;padding:48px 24px 80px}header{max-width:840px;margin-bottom:32px}.eyebrow{font-size:12px;letter-spacing:.14em;font-weight:700;color:var(--accent)}h1{font-size:clamp(32px,5vw,54px);line-height:1.08;letter-spacing:-.045em;margin:16px 0}h2{font-size:23px;line-height:1.25;margin:0 0 12px}h3{font-size:16px;margin:0 0 10px}p{margin:0 0 16px}header p{font-size:18px}.note{border-left:4px solid var(--accent);padding:16px 20px;background:#e5ede5}.pair,details{margin:24px 0;padding:26px;background:#fff;border:1px solid var(--line);border-radius:14px}.pair>p{color:var(--muted);max-width:970px}.players{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.variant{padding:18px;background:#f4f5f2;border:1px solid var(--line);border-radius:9px}.variant.focus{background:#edf6ed;border-color:#91bca5}.variant p{font-size:14px;color:var(--muted)}audio{width:100%;margin:8px 0 16px}.timing{font-variant-numeric:tabular-nums}.decline{padding:16px 0;font-weight:650;color:#805415!important}summary{cursor:pointer;font-weight:700;font-size:19px}details[open] summary{margin-bottom:24px}.scroll{overflow:auto}table{border-collapse:collapse;width:100%;font-size:14px;margin:14px 0 24px}td,th{text-align:left;border-bottom:1px solid var(--line);padding:10px 12px}th{background:#edf1ed}small{color:var(--muted)}a{color:var(--accent)}footer{padding:18px 0;color:var(--muted);font-size:13px}@media(max-width:850px){.players{grid-template-columns:1fr}.pair,details{padding:20px}main{padding:28px 14px}.variant p{margin-bottom:6px}}
</style><main><header><div class="eyebrow">BEATWEAVE / TRAINING ROUND 05</div><h1>Fades that reach<br>the transition’s end.</h1><p>Newly trained volume curves, with a separate experimental update to automatic transition picks.</p><div class="note"><b>Start with “02 · New fades”.</b> It keeps the automatic song sections fixed so you can hear the volume change. Every playable preview has six seconds of context before the transition. Column 03 also changes the cue model.</div></header>'''
    doc+=''.join(cards)
    doc+=f'''<details><summary>What was trained—and what was constrained</summary>
<h3>Volume model</h3><p>The 5,450-parameter network was optimized on seven previously collected real DJ transitions from six mixes. {optimization['changed_parameters']:,} parameters changed. Its loss fell from {optimization['initial_objective']:.6f} to {optimization['final_objective']:.6f} over {optimization['iterations']} iterations, reaching the preset limit rather than convergence. The labels are acoustically estimated gains projected into the new full-span fade policy, not recorded DJ knob movements. Older personal volume traces were excluded because your latest feedback rejects their abrupt endings.</p>
<p>The network learns how fade speed varies with source audio. Positive bounded speeds are smoothed and integrated across the full accepted overlap. Exact endpoints, continuous progress and the removal of the short forced release are explicit playback constraints. Training did not discover those rules by itself.</p>
<h3>Transition picks</h3><p>A new regularized linear scorer was fit to five accepted acoustic windows; Homicide received reduced weight. Other candidate sections are unreviewed contrastive examples, not listener-rated failures. Fitting converged in 14 iterations. Exact approved windows supply training features only; automatic inference receives no labeled cue overrides.</p>
<p><b>The cue checkpoint remains experimental.</b> Selection regressions remain in the leave-one-pair-out checks below, and several approved sections are excluded by the inferred supported bar grid. Ranking an unavailable reference highly cannot make the planner choose it. No chorus/drop classifier or new downbeat model was trained this round.</p></details>
<details><summary>Validation and limitations</summary><h3>Volume retention checks</h3><p>Projected-target gain error, lower is better. These three external examples were inspected in previous rounds; they are retention checks, not fresh tests.</p><div class="scroll"><table><thead><tr><th>Example</th><th>Old</th><th>New</th><th>Equal-power</th><th>Learned time-only</th></tr></thead><tbody>{''.join(metric_rows)}</tbody></table></div>
<p>The time-only baseline beats the audio-conditioned network on all three of these target-error checks. Original mix reconstruction improves on the validation example but slightly worsens on both earlier test examples. Broader musical improvement or an advantage from audio conditioning has not been demonstrated.</p>
<h3>Leave-one-pair-out cue checks</h3><p>Each fit excludes the entire listed pair. Results show the highest-scored candidate before final clock fitting; these are not necessarily accepted playback plans. Errors compare source starts with your approved window. The floor is the smallest available combined start error at the approved bar count. yes baby uses acoustic background only because its automatic planner declines.</p><div class="scroll"><table><thead><tr><th>Held-out pair</th><th>Top cue seconds</th><th>Bars</th><th>Start error A / B, s</th><th>Grid floor, s</th></tr></thead><tbody>{''.join(rank_rows)}</tbody></table></div>
<p>All {len(verification['checks'])} comparison exports decode to their exact expected duration with zero clipped samples. All eight new gain traces move monotonically through the overlap with exact endpoints. Four new regression tests pass. A separate end-to-end run reproduces the Yeah, No → Somebody Told Me automatic plan. These are development songs used in fitting, not unseen listening tests.</p>
<p>All conditions use the repaired R2 mapped renderer, no EQ, and one common export gain per pair. The previous faders keep both decks louder through much of the overlap; previews are not individually loudness-matched. Bar counts are the planner’s interpretation, not independent musical annotations. The production library default is unchanged.</p></details>
<footer>Source and experiment history: <a href="https://github.com/adrielGGmotion/BeatWeave/pull/8">BeatWeave draft PR #8</a>. Weights, training evidence, gain traces and frozen runner artifacts accompany this report.</footer></main>
<script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{{if(b!==a)b.pause()}})));</script></html>'''
    dest=root/'BeatWeave-round5-listening.html';dest.write_text(doc);print(dest)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();report(a.root)
