#!/usr/bin/env python3
"""Self-contained review report plus a portable checkpoint/results archive."""
import argparse
import base64
import hashlib
import html
import json
from pathlib import Path
import zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

def build(out):
    def read(name):return json.loads((out/name).read_text())
    def embed(path,mime):return 'data:'+mime+';base64,'+base64.b64encode(path.read_bytes()).decode()
    f=read('handoff-training-results.json');b=read('bar-phase-results.json');a=read('evaluation-audit.json')
    time=read('time-only/handoff-training-results.json');listen=read('listening-results.json');audit=read('user-bar-audit.json')
    plots=out/'plots';plots.mkdir(exist_ok=True)
    plt.rcParams.update({'font.size':10,'axes.spines.top':False,'axes.spines.right':False,'figure.facecolor':'white','axes.facecolor':'white'})
    fig,axes=plt.subplots(1,2,figsize=(11,3.4),layout='constrained')
    axes[0].barh(['Bass-energy rule','Trained bar phase'],[46.379,75.193],color=['#bbc4d0','#166455'])
    lo,hi=np.array(a['bar_test_cluster_bootstrap']['accuracy_95_percent_interval'])*100
    axes[0].errorbar([75.193],[1],xerr=[[75.193-lo],[hi-75.193]],fmt='none',color='#132c24',capsize=4)
    axes[0].set(xlim=(0,100),xlabel='Correct phase (%) · higher is better',title='Held-out bar phase · known input beats')
    values=[f['splits']['test']['linear_spectral_mae_db'],f['splits']['test']['equal_power_spectral_mae_db'],f['splits']['test']['learned_spectral_mae_db'],time['splits']['test']['learned_spectral_mae_db']]
    axes[1].barh(['Linear fade','Equal-power fade','Trained audio + time','Trained time only'],values,color=['#bbc4d0','#bbc4d0','#166455','#cd782c'])
    axes[1].invert_yaxis();axes[1].set(xlim=(0,6.5),xlabel='Spectral MAE (dB) · lower is better',title='Held-out mix reconstruction · only two mixes')
    for i,v in enumerate(values):axes[1].text(v+.07,i,f'{v:.2f}',va='center')
    fig.savefig(plots/'evaluation.png',dpi=150);plt.close(fig)
    fig,axes=plt.subplots(2,2,figsize=(11,5.7),layout='constrained')
    for row,e in enumerate([e for e in f['examples'] if e['split']=='test']):
        curves=read(f"example-{e['id']}-curves.json");baseline=read(f"time-only/example-{e['id']}-curves.json")
        t=np.array(curves['time']);mask=np.array(curves['observable']);teacher=np.array(curves['estimated_teacher']);teacher[~mask]=np.nan
        for side in range(2):
            ax=axes[row,side];ax.plot(t,teacher[:,side],label='Estimated teacher',color='#212f42',lw=2)
            ax.plot(t,np.array(curves['predicted'])[:,side],label='Audio + time',color='#166455',lw=1.8)
            ax.plot(t,np.array(baseline['predicted'])[:,side],label='Time only',color='#cd782c',lw=1.5,ls='--')
            ax.set(title=f"Held-out example {e['id']} · {'outgoing' if side==0 else 'incoming'}",ylim=(0,1.4),ylabel='Amplitude gain',xlabel='Aligned excerpt time (s)')
    axes[0,0].legend(frameon=False,fontsize=8)
    fig.savefig(plots/'held-out-faders.png',dpi=150);plt.close(fig)
    sections=[]
    for p in listen['pairs']:
        rows=[]
        for v in p['variants']:
            plan=v['plan'];details=f"A {plan['a']:.3f}s · B {plan['b']:.3f}s · overlap {plan['duration']:.3f}s"
            if v['kind']=='trained-faders':details+=' · short-window extrapolation'
            if plan.get('extrapolated_end_beats'):details+=f" · last {plan['extrapolated_end_beats']} endpoint beats extrapolated"
            rows.append(f'<div class="take"><div><strong>{html.escape(v["label"])}</strong><small>{details}</small></div><audio controls preload="none" src="{embed(out/v["audio"],"audio/ogg")}"></audio></div>')
        sections.append(f'<section class="pair"><h3>{p["pair"]}. {html.escape(p["title"])}</h3>'+''.join(rows)+'</section>')
    tracks=''.join(f'<tr><td>{html.escape(t["title"].removesuffix(".opus"))}</td><td>{t["old_cue"]:.3f}s</td><td>{t["suggested_bar_time"]:.3f}s</td><td>{t["vote_agreement"]:.0%}</td></tr>' for t in audit['tracks'])
    modelrows=''.join(f'<tr><td>{html.escape(name)}</td><td class="hash">{value}</td></tr>' for name,value in read('experiment-provenance.json')['model_sha256'].items())
    page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BeatWeave — independent training and listening review</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f1f3f0;color:#172722;font:16px/1.6 system-ui,sans-serif}main{max-width:1120px;margin:auto;padding:48px 28px}header{border-bottom:2px solid #183f34;padding-bottom:26px}h1{font-size:clamp(30px,5vw,49px);line-height:1.12;letter-spacing:-1.5px;margin:12px 0 18px}h2{font-size:26px;margin-top:40px}h3{font-size:19px}p{max-width:930px}.eyebrow{font-size:12px;text-transform:uppercase;letter-spacing:2px;font-weight:700;color:#365e50}.status{background:#e1eadf;border-left:4px solid #2d725a;padding:15px 20px}.limit{background:#f8eddf;border-left:4px solid #be7229;padding:14px 20px}table{border-collapse:collapse;width:100%;font-size:14px}th,td{padding:11px 13px;text-align:left;border-bottom:1px solid #d6ddd6}th{background:#e4e9e3}td:first-child{max-width:460px}.pair{padding:18px 24px;margin:22px 0;background:white;border:1px solid #d4ded5;border-radius:12px}.take{display:grid;grid-template-columns:1fr 330px;gap:20px;align-items:center;padding:18px 0;border-top:1px solid #e0e6df}audio{width:100%;height:38px}small{display:block;color:#57635d;font-size:12px;margin-top:4px}.plot{width:100%;height:auto;margin:15px 0;background:white;border-radius:8px}a{color:#14664d}.hash{word-break:break-all;font:11px/1.7 monospace}footer{margin:45px 0 15px;font-size:13px;color:#58665d}.scroll{overflow:auto}li{margin:8px 0}@media(max-width:760px){main{padding:25px 16px}.take{grid-template-columns:1fr;gap:9px}.pair{padding:12px 16px}table{font-size:12px}th,td{padding:8px}}</style><main>
<header><div class="eyebrow">BeatWeave · Training round 3 · 5 October 2026</div><h1>Trained weights.<br>Evidence kept separate from the audition.</h1><p class="status"><strong>Actual training completed:</strong> a bar-phase classifier, an audio-conditioned fader network and a time-only fader baseline. The six review songs and Spotify controls were excluded from their training.</p><p>The bar model learned a useful signal on its evaluation task. The audio-conditioned fader model did <strong>not</strong> beat the trained time-only baseline on spectral reconstruction. These checkpoints remain experimental.</p></header>
<h2>What the held-out tests show</h2><div class="scroll"><table><thead><tr><th>Task</th><th>Training</th><th>Independent test</th><th>Result</th></tr></thead><tbody>
<tr><td>Four-beat bar phase</td><td>325 recordings<br>1,956 windows</td><td>101 recordings / 64 album-or-replica groups<br>649 windows</td><td><strong>75.2%</strong> versus 46.4% bass-energy baseline<br>95% group-bootstrap interval: 69.1–80.8%</td></tr>
<tr><td>Two independent amplitude faders</td><td>7 transitions / 6 mixes<br>1 separate validation mix</td><td>2 transitions / 2 mixes<br>Neither source track crosses a fold</td><td><strong>3.79 dB</strong> spectral MAE<br>3.67 time-only · 4.41 equal-power · 5.69 linear</td></tr>
</tbody></table></div>'''
    page+=f'<img class="plot" alt="Bar-phase accuracy and fader reconstruction error on held-out data" src="{embed(plots/"evaluation.png","image/png")}">'
    page+='''<p>The bar test supplies manually annotated beat positions and asks which of four beats starts the bar. It does not measure end-to-end beat tracking. Album groups, documented replicas and byte-identical sources stay within one fold. The validation-selected score margin accepts 363 of 649 test windows at 88.4% accuracy; that is not a calibrated confidence guarantee for pop songs.</p>
<p>The fader network has 5,450 trainable parameters and uses only source audio features, progress and duration. The retained checkpoint is epoch 143, selected by validation error. Labels are independent, monotone amplitude envelopes estimated by reconstructing actual DJ mixes from aligned source audio. They are <strong>not recorded DJ knob positions</strong>; EQ, limiting and residual alignment errors can affect them. Silent or padded source regions are excluded from gain supervision.</p>
<p>Audio-conditioned teacher-gain MAE is 0.197, versus 0.228 for time-only. This different ranking shows why one proxy metric cannot establish musical quality. With only two test mixes, neither model has a credible broad listening-quality claim.</p>
<h2>Listen to one change at a time</h2><p>Each pair uses one shared export gain and includes six seconds before and after the overlap. All newly rendered versions omit the guessed EQ and filter effects. The previous pilot remains the control. New faders are trained independently; the cue region and overlap count are inherited from the pilot.</p>
<div class="limit"><strong>These are diagnostic auditions, not accepted automatic mixes.</strong> Mantra → Subway retains its approved timing because the new bar model proposes a conflicting phase. The other two use estimated beat anchors and agreed bar hypotheses. Pair 1 extrapolates three endpoint beats after the detector stops. The new fader model was trained on 59–73 second excerpts; these 16–29 second overlaps are outside that range. Gain caps and half-second endpoint joins are renderer rules.</div>'''
    page+=''.join(sections)
    page+='''<p>When comparing: first check whether the drums and phrase starts line up; then whether the outgoing track stays present through the important moment; then whether the incoming track takes over without an energy hole. A change that passes a numerical test may still fail that listening test.</p>
<h2>Held-out fader traces</h2><p>These are the two independent evaluation mixes, not your songs. Missing teacher segments indicate unobservable gain in a silent/padded source. The study's source audio is not redistributed.</p>'''
    page+=f'<img class="plot" alt="Estimated teacher and learned fader curves for the two held-out mixes" src="{embed(plots/"held-out-faders.png","image/png")}">'
    page+='<h2>Review-song bar hypotheses</h2><p>These timings are model outputs, not human annotations. Window agreement only measures consistency across overlapping windows.</p><div class="scroll"><table><thead><tr><th>Recording</th><th>Previous cue</th><th>Suggested bar</th><th>Window agreement</th></tr></thead><tbody>'+tracks+'</tbody></table></div>'
    page+='''<h2>What remains untrained or unproven</h2><ul><li>Choosing a compatible chorus, drop or phrase, and choosing overlap duration. This round does not solve autonomous cue selection.</li><li>EQ policy. No trained EQ model is included; new auditions omit those effects.</li><li>Bar accuracy on these exact recording versions. The new phase classifier learned from Ballroom material and assumes four-beat bars.</li><li>General fader taste. Ten curated examples are too few, and the audio features did not win the reconstruction ablation.</li></ul>
<h2>Checks and reproducibility</h2><p>Eight new regressions pass, including power-to-amplitude conversion, ignoring unobservable labels, independent fader holds, continuous gain endpoints, rejecting invalid time maps, and an independently generated variable-tempo attack fixture. The latter's maximum measured attack error was 18.1 ms against a 30 ms tolerance. Model exports match their training-library outputs numerically. All eleven encoded auditions decode without clipped samples.</p><p>No production library behavior changes in this round. The full JVM/Android production suite was not rerun for these isolated experiment tools. Checkpoints, split manifests, predictions, timing diagnostics, gain traces and audio are in the accompanying archive. Source code is in <a href="https://github.com/adrielGGmotion/BeatWeave/pull/8">BeatWeave PR #8</a>, under <code>tools/handoff-automix</code>.</p>
<h3>Checkpoint fingerprints · SHA-256</h3><table><tbody>'''+modelrows+'''</tbody></table>
<h3>Independent sources</h3><ul><li><a href="https://github.com/mir-aidj/transition-analysis">Kim, Yang &amp; Nam: real DJ transition analysis demonstration</a> — 10 transitions from 9 mixes; source commit and audio checksums recorded. Whole mixes and both source track IDs are separated. DJ identities are unavailable, so distinct-DJ separation is not claimed.</li><li><a href="https://mtg.upf.edu/ismir2004/contest/tempoContest/data1.tar.gz">Ballroom audio</a> with <a href="https://github.com/CPJKU/BallroomAnnotations">CPJKU manually corrected beat/bar annotations</a> — published archive checksum verified; 521 usable four-beat recordings.</li></ul><p>Research audio is not included in this report or archive. No unrestricted product-training or model-distribution license is asserted.</p>
<footer>Assessment: useful conditional bar learning; inconclusive audio-conditioned fader learning. No claim that these transitions now sound good. This report plays offline and sends no data.</footer></main></html>'''
    report=out/'BeatWeave-trained-models-review.html';report.write_text(page)
    # Hash all deliverables before archive construction; no source audio or repo code.
    paths=[p for p in out.rglob('*') if p.is_file() and p.suffix!='.zip' and p.name!='checksums.json']
    (out/'checksums.json').write_text(json.dumps({str(p.relative_to(out)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},indent=2)+'\n')
    archive=out/'BeatWeave-trained-models-and-comparisons.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(out.rglob('*')):
            if p.is_file() and p!=archive and p.suffix!='.zip':z.write(p,p.relative_to(out))
    print(json.dumps({'report_bytes':report.stat().st_size,'archive_bytes':archive.stat().st_size,'audio_players':page.count('<audio ')}))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);a=p.parse_args();build(a.out)
