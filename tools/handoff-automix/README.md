# Independent fader and bar-phase training experiment

This round trains weights on independent audio. It does not copy Spotify controls
into its training labels. Nothing here changes or enables production automixing.

## Findings

* Four-beat bar phase: 75.2% on 649 held-out windows from 101 recordings / 64
  album-or-replica groups, versus 46.4% for strongest bass energy at the bar start.
  The test uses human beat timestamps: this is conditional bar-phase accuracy,
  **not end-to-end beat/downbeat tracking accuracy**. Group bootstrap 95% interval:
  69.1–80.8%. The validation-selected margin covers 363 test windows with 88.4%
  accuracy; it does not establish confidence on the review pop songs.
* Independent faders: 7 train transitions from 6 mixes, 1 validation transition,
  2 test transitions from 2 other mixes. The 5,450-parameter source-audio MLP
  reaches 3.79 dB mean absolute spectral reconstruction error on test, versus
  5.69 linear / 4.41 equal-power. A separately trained time-only baseline reaches
  **3.67 dB**, so a useful contribution from audio conditioning is not established
  by that metric. Audio-conditioned gain error against estimated teachers is
  0.197 versus 0.228 time-only; these metrics need not rank models identically.
* Actual fader knob settings are unknown. The supervision is a constrained
  estimate from actual mixes and aligned sources, and absorbs EQ, limiter and
  alignment error. Silent/zero-padded source regions have no observable gain
  labels and are excluded from the supervised loss and gain-error metric.
* All six user songs are development auditions, never independent evaluation.
  Their short overlaps are outside the 59–73 second fader training windows.
  No new chorus/drop/cue-selection model is claimed in this round.

## Data and reproducibility

Python 3.12, ffmpeg, NumPy, SciPy and a C++17 compiler are required. Training used
PyTorch 2.3.1+cpu, NumPy 1.26.4 and CVXPY 1.7.5 (CLARABEL) in the fader environment;
scikit-learn 1.8.0 and NumPy 2.3.5 in the bar-phase environment. Both exports have
numerical parity checks against their originating training library. Train-only
normalization, seeded fitting and whole-recording/mix splits are recorded.

Sources:

* [transition-analysis](https://github.com/mir-aidj/transition-analysis), commit
  `bc2ae4f1d345ab2edc0332b23afe67ad68aa07c1`: the authors' ten published demo
  transitions, actual mix audio and aligned source audio. This is a small curated
  demo, not the full paper corpus. The independently monotone, nonnegative
  spectral-power teacher is estimated here with convex optimization; square root
  converts power coefficients to amplitude faders. The demo's published curves
  are not our training labels. Whole mixes and track IDs are disjoint; DJ identity
  separation is unknown. Relevant work: Kim, Yang and Nam, NIME 2021,
  “Reverse-Engineering The Transition Regions of Real-World DJ Mixes using
  Sub-band Analysis with Convex Optimization.”
* [Ballroom audio](https://mtg.upf.edu/ismir2004/contest/tempoContest/data1.tar.gz),
  published MD5 `2872a3e52070bc342a4510a95e2fa0b8`, verified before extraction.
* [BallroomAnnotations](https://github.com/CPJKU/BallroomAnnotations), commit
  `1db08914a8ae15edb01f104046e30bad88effe67`: manually corrected beat/bar labels.
  This experiment uses 521 four-beat-bar recordings. Album groups, every documented
  replica and byte-identical audio are kept together. Unknown alternate masters
  could remain. Other meters are outside this model's task.

The research audio is downloaded for the experiment and is not redistributed.
No unrestricted commercial dataset/model license is asserted. Repository source
licensing does not automatically license all referenced audio or resulting weights.

Example commands (use separate Python environments if required):

```sh
python tools/handoff-automix/train_handoff.py \
  build/transition-analysis build/handoff-training ../beatweave-handoff-training
python tools/handoff-automix/train_handoff.py \
  build/transition-analysis build/handoff-training ../beatweave-handoff-training/time-only --time-only
python tools/handoff-automix/train_bar_phase.py \
  build/handoff-training/ballroom.tar.gz build/BallroomAnnotations \
  build/handoff-training ../beatweave-handoff-training
python tools/handoff-automix/evaluate.py ../beatweave-handoff-training
```

Prepared data caches are intentionally reused. Use a fresh work directory when
changing data sources, feature definitions or label estimation. Do not mix cached
features from different versions. Manifests contain source checksums and folds.

## Review-song diagnostics

`export_timing.py` compiles actual library analysis code with checksum-verified
host dependencies and accepts only the verified BeatThis small0 checkpoint.
`audit_user_bars.py` applies the frozen bar model around earlier cue regions.
There are no human bar labels for these songs; agreement with a second model is
not musical ground truth. The Subway phase hypothesis conflicts with the approved
transition, so its timing is retained in the listening control.

`render_review.py` separates four conditions: previous pilot, EQ removed,
experimental bar/beat timing with the previous volume, and that same timing with
newly trained faders. Pair 2 omits the timing change. Cues and overlap counts stay
near the previous pilot region: this is not autonomous cue selection.

The renderer uses offline linked-stereo Rubber Band R3 at pitch scale 1. Beat
anchors control variable time stretching. The source origin must be implicit in
the R3 map: passing (0,0) causes a ratio division by zero in this vendored engine.
New variants omit all guessed EQ/filter processing. Monotone projection, 0–1
playback gain cap and half-second boundary joins are **renderer rules**, not
learned decisions. All versions of a pair share one export gain. Decode checks
reject clipping. The synthetic map regression measured 18.1 ms maximum attack
error; that validates this fixture, not music bar accuracy.

```sh
g++ -std=c++17 -O3 -DNDEBUG -pthread -I. \
  tools/handoff-automix/warp.cpp \
  rubberband/native/vendor/rubberband/single/RubberBandSingle.cpp \
  -o build/handoff-training/warp-r3
python -m unittest discover -s tools/handoff-automix -p test_handoff.py -v
python tools/handoff-automix/export_timing.py build/manual-automix/data \
  learned-beats/models/beat-this-small0.onnx build/handoff-training/source-clocks
python tools/handoff-automix/audit_user_bars.py build/manual-automix/data \
  build/handoff-training/source-clocks ../beatweave-training-pilot \
  ../beatweave-handoff-training ../beatweave-handoff-training
python tools/handoff-automix/render_review.py build/manual-automix/data \
  build/handoff-training/source-clocks ../beatweave-training-pilot ../beatweave-handoff-training
```

The render commands require the existing private review-song preparation and pilot
artifacts. User audio, research audio, generated renders and weights remain outside
Git. Production clock gates are not bypassed or relaxed by these diagnostics.

Before deployment: acquire broader aligned mix/source supervision, establish data
rights for the intended distribution, train and evaluate musical cue selection,
validate bars with independent annotations on the target repertoire, then run
blind listening comparisons. Do not substitute spectral error for listener quality.

For pair 1 the beat tracker stops before the requested overlap ends. The last
three endpoint beats are extrapolated from the final 16 observed beats, explicitly
marked in the listening plan. This is another reason that version is a timing
trial rather than a verified bar correction.
