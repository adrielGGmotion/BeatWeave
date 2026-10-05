# Preference update and frozen automatic test

This round uses the user's actual listening choices from the preceding report:

| Pair | Chosen ordinal | Actual variant |
| --- | --- | --- |
| yes baby → Sandstorm | 3 | Corrected timing, previous volume, no EQ |
| Mantra → Subway Surfers | 2 | Previous timing and volume, no EQ |
| Love Me Like You Do → Closer | 3 | Corrected timing, previous volume, no EQ |

The three selections supervise a fine-tune of the entire 5,450-parameter fader
network. Source-audio features and time go in; two amplitude envelopes come out.
There are three independent user preferences, not 259 independent frame examples.
The selected volume traces originally came from the user's transition designs.
This is personal imitation training, not new independent expert-DJ supervision.

Seven independent real DJ transitions replay their earlier estimated gain labels.
Neither of the two earlier external test transitions nor the validation transition
enters fitting. The objective weights user examples 2/3 and replay examples 1/3,
with masked Huber loss (beta .1), a .01 mean-squared parameter anchor and a fixed
160 L-BFGS iterations. Optimization hits that preset iteration limit; it is not
reported as converged. NumPy/SciPy analytical gradients pass finite-difference
checks; PyTorch, a GPU and a cloud training service are unnecessary.

Leave-one-pair-out fits omit the entire pair and both source recordings. The
updated network reduces volume-envelope error substantially relative to the old
network. A mean-profile baseline is also reported: it beats the network on these
three similar preferences. The audio-conditioned network retains lower average
spectral reconstruction error on the two earlier test mixes, while the earlier
validation mix worsens. No broad listening-quality improvement is claimed.

## Cue selection stays separate

The approved pair-1 preview extrapolated three endpoint beats. The fully observed
automatic candidates cannot represent that exact plan. Subway's inferred meter
also prevents the exact approved pair-2 plan from appearing in the automatic
candidate set. Training a nearest available candidate would silently substitute
a different transition. `cue-coverage-audit.json` records that mismatch.

The cue scorer therefore remains the earlier `beatweave-manual-pilot-v1` model.
No new cue-selection or bar-model training is claimed here. The frozen test uses
`LocalMixPlanner.bestTransition` to choose cues and length, with all existing
pulse, bar and clock acceptance limits. A typed rejection remains a rejection.

## Fixed inference contract

`run_auto.py` accepts two audio paths, a frozen release and a fresh output folder.
It has no offset, bar-count, cue, meter, EQ or volume override. It verifies the
frozen weights, executable artifacts and inference code before decoding, checks
whole-file PCM durations, and records the input and release hashes before running.
The first output or decline is retained. No training path is invoked at inference.

Playback uses the new fader network with the same independent monotone projection,
0–1 gain bound and short endpoint joins for every pair. Join duration is the median
release width measured from the three accepted traces (about 17.5 ms at the trace's
10 ms resolution); it is not manually set for a new song. EQ/filter processing is
omitted, pitch scale is 1, and one fixed export gain prevents clipping.

The experimental R3 renderer samples the accepted continuous source clock at
50 ms intervals. Passing the library clock gate does not establish the musical
quality or exact prepared-renderer parity of this separate native audition path.
These artifacts are not enabled in the library's default production API.

### Renderer repair after the second listening test

The archived R3 runner failed while rendering Yeah, No → Somebody Told Me:
1,193,427 frames arrived instead of 1,202,880. Replaying the identical native
input/map reproduced the output byte for byte. A separate synthetic decreasing
speed map also exposed missing frames and attack drift over 200 ms. R3's local
ratio updates do not provide the required timing on these dense changing maps.

`build_renderer.py` builds a separate R2 offline mapped renderer. The synthetic
decreasing and oscillating speed tests check full duration, independently placed
attacks within 30 ms, unchanged 440 Hz pitch within 2 Hz, linked stereo, and
invalid-map rejection. Measured maximum attack errors were 8.4 ms and 7.5 ms.
These synthetic checks do not establish musical beat annotations or listening
quality. The exact failed music input now returns all 1,202,880 frames.

`retry_render.py` reuses the first accepted plan and clock map, verifies the
original source and release hashes, and applies the original trained faders.
There is no cue, duration, bar-count, EQ, gain, or timing override. It records the
replacement renderer hash and preserves the original failed attempt and release.
This is explicitly a renderer-repair retry, not an unchanged frozen-runner result
or a new training round. The historic `run_auto.py` and frozen binary remain
unchanged; this repair is not enabled in the default production library.

```sh
python3 tools/preference-automix/build_renderer.py
OPENBLAS_NUM_THREADS=2 python3 -m unittest discover \
  -s tools/preference-automix -p test_renderer.py -v
python3 tools/preference-automix/retry_render.py outgoing.opus incoming.m4a \
  --attempt ORIGINAL_FAILED_ATTEMPT --release OUTPUT/frozen --out NEW_EMPTY_DIRECTORY
python3 tools/preference-automix/audit_bars.py \
  --attempt ORIGINAL_FAILED_ATTEMPT --release OUTPUT/frozen --out NEW_AUDIT_DIRECTORY
```

The read-only bar-count audit compares automatic selection with separately
requested 8 and 16 bars using the same frozen compiled library and cue model.
Both lengths pass timing checks on this pair, but the older cue scorer assigns
11.7175 to its accepted 8-bar choice and 1.0662 to its accepted 16-bar choice.
The scored candidates have different cues as well as different lengths. These
are relative model scores, not calibrated musical-quality ratings. The recent
volume training did not train a new duration preference, and no ranking change
is made by this renderer repair.

```sh
python3 tools/preference-automix/bootstrap.py
python3 tools/preference-automix/prepare_feedback.py INPUTS WORK OUTPUT
OPENBLAS_NUM_THREADS=2 python3 tools/preference-automix/train_faders.py INPUTS WORK OUTPUT
OPENBLAS_NUM_THREADS=2 python3 -m unittest discover -s tools/preference-automix -p test_preference.py -v
python3 tools/preference-automix/render_feedback.py INPUTS WORK OUTPUT
python3 tools/preference-automix/freeze.py INPUTS OUTPUT
python3 tools/preference-automix/run_auto.py outgoing.opus incoming.opus \
  --release OUTPUT/frozen --out NEW_EMPTY_DIRECTORY
```

`INPUTS` contains the restored private `pilot`, `round3` and `sources` artifacts.
The ten source/mix excerpts from `mir-aidj/transition-analysis` remain at the
checksum-pinned revision recorded by the preceding experiment. Research audio is
not redistributed. The new bundle includes spectral-feature checkpoints for
replaying this fit, not that source audio. See `handoff-automix/README.md` for data
and model provenance. The existing mean-profile diagnostic is an ablation, not a
second inference path selected after the unseen test.

During verification, incomplete intermediate PCM files were found and rebuilt.
Decode subprocesses disable interactive stdin; temporary training decodes are
validated before atomic replacement, and both mono and stereo clocks must agree.
All six regenerated canonical clocks exactly match the previous round afterward.
The approved training excerpts were inside the complete portions, but fitting
and comparisons were rerun after repair.

Source code is in Git. Weights, feature caches, compiled artifacts and listening
outputs remain outside Git. Frozen binaries must retain the corresponding source
reference and bundled third-party notices.
