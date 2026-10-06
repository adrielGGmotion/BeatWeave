# Preference training and automatic listening tests

## Opening-entry repair after round 6

The latest feedback requires each incoming song to enter near its beginning.
Earlier ranking only rewarded early starts, so acoustic similarity or a learned
score could still select a middle section. Automatic transition candidates now
must start within `min(30 seconds, 0.10 * incoming decoded duration)`, before
shortlisting or model scoring. Ordered retries and the final candidate guard
enforce the same limit. Both detector interpretations use the full recording's
absolute source clock. Overlap length may extend beyond this entry window.

If no opening candidate meets meter, pulse, acoustic and clock checks, the
planner declines instead of jumping later or relaxing those checks. The
`incomingStartLimitSeconds` search diagnostic and exported plan record the
limit. Outgoing selection, explicit manual bar selection, full overlap mode and
full-span volume curves keep their existing behavior. The public search options
allow callers to explicitly configure both entry bounds; the frozen audition
runner exposes no such per-song override and always uses the opening defaults.

This is a deterministic policy correction, **not a new model-training round**.
The detector, cue and fader weights are byte-identical to round 6. Previously
approved middle entries are historical feedback and do not override this newer
requirement. Future cue fits must use the newly filtered candidate pools;
unavailable old references cannot supervise an eligible candidate. No positive
label is silently moved to a different intro location.

`opening_compare.py` compares round-6 experimental cue weights before and after
the policy, rerendering both with the same faders, R2 renderer, no EQ and one
common export gain per pair. All first automatic results and declines are kept.
It requires complete decoded source PCM under each pair's `data/` directory,
the original-source hash manifest, and a fresh opening release. Model weights,
recordings and generated evidence stay outside Git.

```sh
python3 tools/preference-automix/verify_candidates.py
python3 tools/preference-automix/build_training_engine.py ROUND6/frozen
python3 tools/preference-automix/freeze_opening.py ROUND6/frozen OPENING
OPENBLAS_NUM_THREADS=2 python3 tools/preference-automix/opening_compare.py OPENING ROUND6
python3 tools/preference-automix/run_ensemble.py outgoing.opus incoming.opus \
  --release OPENING/frozen --out NEW_EMPTY_DIRECTORY --experimental-cues
```

The fixed-length, automatic-length, fallback and ensemble regressions use an
adversarial late-biased scorer, incompatible opening meters, quiet intros,
short/long recordings and exact window boundaries. Older full-track ranking and
large-search-budget fixtures explicitly widen the window to isolate their
original contracts. Incoming pins still cannot bypass the default opening rule.
Passing these tests establishes entry-policy enforcement, not musical quality.

## Round 6: candidate coverage and feasible-window cue training

The previous scorer could not choose some approved windows because candidate
generation discarded them first. This round changes the actual library planner
on this experimental branch, then fits a new scorer on its supported candidates.
The whole update remains in draft; neither the search change nor the new scorer
has established a broad listening-quality improvement.

- For transitions, acoustic recurrence uses an eight-interval window clamped
  inside the selected span when that span contains enough measured intervals.
  The context is chosen by geometry, never by the best score. Short spans retain
  centered context. Per-bar direct activity, phase/meter, pulse geometry and
  final clock checks remain required, with unchanged numerical thresholds.
- `UNCERTAIN_REFERENCE_PHASE` already identifies individually unsupported
  events. Removing its extra eight-beat exclusion padding recovers independently
  supported phrase starts; every remaining island is still re-audited. Other
  cadence failures retain their context padding. No beats are extrapolated.
- `AutoMixAnalysisEnsemble.bestTransition` accepts one or two independently
  measured analyses per track and compares up to four normal searches. It uses
  the same scorer and acceptance gates in each pool and selects the highest
  accepted score, preferring the earlier pool on ties. Original source clocks
  are never combined or shifted. Each pool keeps its normal search budget.
  The caller must supply analyses of the same recording/decoder: the API checks
  equal decoded duration, while this experiment also verifies source hashes.
  Incoming beat-index pins require a single incoming analysis.

The ensemble audition uses the existing released Beat This small0 and final0
models. Neither detector was trained here. final0 was obtained from the official
BeatWeave v0.10.0 model archive; its SHA-256 is
`c8293b7b787e73b40ad5e899624dbe08763268031543ef7ebcab53f8e78da66f`.
The larger model and extra searches cost more CPU/memory. No T470 benchmark is
claimed. Single-analysis library entrypoints remain available.

`train_cues_round6.py` fits all 104 existing linear/quadratic weights, anchored to
the prior checkpoint with a fixed L2 coefficient of 0.5. The model sees acoustic
and positional features, not song names or pair identifiers. Positives must be
actual supported candidates matching both approved starts (31 ms tolerance),
both ends (150 ms tolerance) and bar count. Only three of five personal windows
are now available; unavailable references are coverage checks, not synthetic
training positives or playback overrides. Homicide's weak approval has weight
0.35. Other candidates are unreviewed contrastive background.

L-BFGS converges in 21 iterations: 90 parameters change, and the regularized
objective falls from 2.591204 to 1.499529. A preceding 13-weight residual fit with
synthetic references failed; its checkpoint/results remain in the evidence
bundle as an ablation. The final fit also has regressions and is not promoted.
Leave-one-update-out retains a prior already exposed to personal examples, so
these folds test retention, not clean unseen generalization. Only Love/Closer
retains an exact approved raw top candidate when its latest label is omitted.

All entries below are **accepted automatic plans**, after clock fitting:

| Pair | Round 5 cues | Expanded search + prior scorer | Expanded search + fitted scorer |
| --- | --- | --- | --- |
| Love / Closer | 194.66 → 68.48, 8 bars | 192.16 → 68.48, 8 bars | 192.16 → 68.48, 8 bars |
| Homicide / Last Page | 91.82 → 43.18, 4 bars | 80.36 → 126.14, 2 bars | 91.82 → 40.30, 4 bars |
| Yeah / Somebody | 145.32 → 3.96, 8 bars | 146.90 → 5.70, 8 bars | 145.32 → 3.96, 8 bars |
| yes baby / Sandstorm | Declined | 144.04 → 31.28, 16 bars | 2.54 → 103.56, 2 bars |
| Mantra / Subway | 115.12 → 32.02, 8 bars | 117.16 → 38.00, 8 bars | 100.76 → 12.00, 16 bars |

Love's approved outgoing cue at 192.16 seconds is recovered by the source fix.
The fitted scorer restores Yeah's approved choice after the expanded search
regressed it, but moves Homicide's incoming cue and misses the other two labels.
The exact approved yes baby and Mantra windows are still unavailable. Their
nearest same-length candidate start errors sum to 3.58 and 2.011 seconds,
respectively; neither is relabeled as an approved substitute.

The round-5 full-span fader network is unchanged. `round6_compare.py` rerenders
all three conditions with the same R2 renderer, no EQ and one common export gain
per pair. All 14 previews have exact decoded frame counts, no clipped samples,
strictly monotone in-overlap gains and exact control endpoints. These checks do
not establish musical alignment, absence of stretch artifacts or listening
quality. The HTML listening report includes every result, including regressions.

`verify_candidates.py` compiles analysis and learned-beats separately and runs
all common tests: **185 pass**. New regressions cover recording-edge context,
quiet/irregular spans, weak ternary bars, independent phase support, alternative
analysis acceptance, all-pool rejection and cancellation. Android and native
integration suites were not run. Earlier incomplete temporary PCM decodes were
discarded and rebuilt; all final pools use complete, hash-checked recordings.

Reproduce from the separate evidence bundle and matching Git revision:

```sh
python3 tools/preference-automix/verify_candidates.py
python3 tools/preference-automix/build_training_engine.py ROUND5/frozen
OPENBLAS_NUM_THREADS=2 python3 tools/preference-automix/train_cues_round6.py ROUND6 ROUND5
python3 tools/preference-automix/freeze_ensemble.py ROUND5/frozen ROUND6
python3 tools/preference-automix/run_ensemble.py outgoing.opus incoming.opus \
  --release ROUND6/frozen --out NEW_EMPTY_DIRECTORY --experimental-cues
```

Do not recreate an existing frozen directory. Omit `--experimental-cues` to use
the prior scorer with the same expanded search; the entire ensemble runner is
experimental. It verifies release, code, model and dependency hashes, accepts no
cue/bar/offset/EQ/gain overrides, and retains the first result or decline.
Whole-file PCM and encoded audio are atomically written and duration checked.
The bundled native renderer is Linux x86-64 and must be executable after unzip.

For fresh training-pool collection, update only the source paths in the labels
manifest while retaining their hashes, then run `collect_ensemble.py ROUND6
ROUND6/frozen --scope NEW_SCOPE`. The fitting script reads `expanded/pool-*`.
Reference-window features are extracted separately for training diagnostics;
no reference property is supplied by the automatic audition runner. Detector
logit caching is keyed by verified model identity and exact input features.

## Round 5: full-span faders and experimental cue training (historical)

The latest feedback rejects abrupt outgoing cuts and fades beginning halfway
through the overlap. `full_span_faders.py` changes the model's output from direct
amplitude to two positive phase speeds, bounded to 0.5–1.5. Smoothed, integrated
phase is normalized across the complete accepted transition. Cosine outgoing
and sine incoming gains meet exactly at `[1, 0]` and `[0, 1]` and move throughout
the overlap. There is no 17.5 ms forced release. Endpoints, monotonicity, smoothing
and rate bounds are playback constraints; the audio-conditioned curve is learned.

All 5,450 MLP parameters are optimized from the previous checkpoint; 5,440 change.
Seven real DJ transitions from six mixes supervise the fit using checkpointed
acoustic gain estimates projected into this full-span policy. These are neither
new human knob logs nor new downloads. Old personal volume traces are excluded
because the latest feedback supersedes their sharp endings. Mix-balanced masked
Huber loss with a fixed parameter anchor falls from 0.546410 to 0.018682 in the
preset 160 L-BFGS iterations. It reaches the iteration limit, not convergence.

Three previously inspected external examples are retention checks, not fresh
tests. New projected-target gain MAE is 0.0582 / 0.0827 / 0.0456; old is
0.2622 / 0.0995 / 0.0818. A learned time-only profile scores
0.0463 / 0.0585 / 0.0411, beating the audio-conditioned model on all three.
Original mix spectral MAE improves on the validation example but slightly
worsens on both earlier test examples. This does not establish a general
advantage from audio conditioning or better musical quality.

`train_cues_round5.py` genuinely fits a new cue scorer to five exact accepted
acoustic windows. Homicide/Last Page has weight 0.35 for its lukewarm feedback;
the other pairs have weight 1. A regularized 13-feature linear ranker replaces
the pilot's quadratic capacity for this experiment (quadratic weights are zero).
Listwise fitting uses other windows as unreviewed contrastive background, not
human-rated bad transitions. Five leave-one-entire-pair-out fits are reported.
The final model converges in 14 iterations with 12 nonzero linear weights.

Exact approved windows may fall outside the supported bar grid. A training-only
synthetic grid encodes their acoustic endpoints for feature extraction; it is
never used as detected beat truth or inserted into playback candidates. The
training build observes the actual production shortlist without changing its
ranking, order or acceptance gates. For yes baby/Sandstorm, whose actual planner
declines, background windows come from the older acoustic exporter instead;
they are explicitly not supported automatic candidates. No nearest feasible
cue is falsely labeled as the user's approval.

Cue results regress on some pairs. The previous cue model remains the runner's
default. A high rank for an off-grid reference is not successful cue selection.
No new chorus/drop classifier, annotated downbeat model, or invented 16-bar
preference is claimed. Fixing unsupported musical windows remains necessary.

| Pair | Previous automatic plan | Experimental automatic plan |
| --- | --- | --- |
| Love Me Like You Do → Closer | 194.66 → 68.48 s, 8 bars | 194.66 → 28.06 s, 8 bars |
| Homicide Love → The Last Page | 91.82 → 43.18 s, 4 bars | Same |
| Yeah, No → Somebody Told Me | 145.32 → 3.96 s, 8 bars | Same |
| yes baby → Sandstorm | NO_CONFIDENT_BAR_GRID | Same decline |
| Mantra → Subway Surfers | 115.12 → 32.02 s, 8 bars | 127.42 → 56.02 s, 2 bars |

These are planner bar counts and source cue seconds, not independent meter
annotations. The candidate scoring report precedes final clock fitting; the
table above records the actual accepted playback plans after all gates.

`round5_compare.py` makes three conditions per accepted pair: previous cues and
faders; previous cues with newly trained faders; experimental cues and new faders.
All cues come from the automatic planner. Every condition uses the repaired R2
renderer, one common export gain per pair and no EQ. Declines remain declines.
All 12 exported previews decode to the exact expected duration with zero clipped
samples; all eight new gain traces are strictly monotone with exact endpoints.
Four new tests cover full-span behavior under extreme rate predictions, analytic
gradients, unobservable target masking and invalid rates. The new two-input
runner also reproduces the Yeah/Somebody automatic plan in an end-to-end smoke
test. This is a known development pair, not unseen evaluation. No production
library API is enabled and the complete JVM/Android suite was not rerun.

Reproduce fitting from the separate round-5 evidence bundle (source audio is not
needed for the cached-feature fits):

```sh
OPENBLAS_NUM_THREADS=2 python3 tools/preference-automix/full_span_faders.py ROUND5/previous ROUND5
OPENBLAS_NUM_THREADS=2 python3 tools/preference-automix/train_cues_round5.py ROUND5
OPENBLAS_NUM_THREADS=2 python3 -m unittest discover -s tools/preference-automix -p test_full_span.py -v
```

For new automatic auditions, use the bundle's frozen release with this source
revision. The optional flag explicitly tests the experimental cue checkpoint;
neither mode accepts manual cues, bar count, offset or gain overrides:

```sh
python3 tools/preference-automix/run_full_span.py outgoing.opus incoming.opus \
  --release ROUND5/frozen --out NEW_EMPTY_DIRECTORY
# Separate experiment, preserving the default run:
python3 tools/preference-automix/run_full_span.py outgoing.opus incoming.opus \
  --release ROUND5/frozen --out ANOTHER_EMPTY_DIRECTORY --experimental-cues
```

The release pins models, R2 renderer, engine and Python inference source hashes.
Dependency checksums are preserved from the prior release. Native binaries remain
platform-specific. Building a new release requires `build_renderer.py`,
`build_training_engine.py PREVIOUS_FROZEN`, and
`freeze_full_span.py PREVIOUS_FROZEN ROUND5`. Fresh candidate extraction uses the
training engine's optional `beatweave.candidateFile` and `beatweave.referenceFile`
JVM properties. Neither property is passed during automatic inference.

## Earlier round 4 (historical)

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
# Round 7: opening-constrained timing update

`train_opening_timing.py` makes a real, deliberately limited cue-model update.
It fits the 14 linear/quadratic timing coefficients of the existing 104-weight
scorer on Raveform's estimated DJ mix alignments. The 90 acoustic/cross terms,
feature normalization, beat detectors, volume model and renderer remain fixed.
Corpus examples contain no acoustic features: standardized acoustic values are
held at zero for that objective. This is a timing prior, not chorus recognition.

Only observations and generated alternatives whose incoming cue lies within
`min(30 seconds, 10% of recording duration)` are eligible. All ten personal
development works are excluded from the corpus. Mix IDs, recording IDs and
normalized recording titles are disjoint across folds. DJ identity and alternate
masters are not fully controlled. Raveform has been used in earlier experiments;
the new split is not a never-seen benchmark.

The fixed objective combines 90% corpus ranking and 10% personal replay with an
L2 anchor of 0.5 to the previous experimental weights. Only the supported
Yeah, No → Somebody Told Me approval survives both current policy and candidate
availability. Old middle-of-song approvals and unavailable cue windows are not
inserted as synthetic positives. Alternatives are unreviewed, not human-rated bad.

This run used 511 training, 59 validation and 73 test transitions, each with nine
options. Fourteen coefficients changed. Test top-1 increased from 5.5% to 8.2%,
still below the 11.1% random baseline; validation top-1 decreased from 6.8% to
5.1%. Test mean rank improved from 4.74 to 4.15. This is mixed timing-imitation
evidence, not demonstrated musical improvement, so the checkpoint remains
experimental and is not the default. No hyperparameter search was performed.

```sh
python3 tools/preference-automix/train_opening_timing.py \
  RAVEFORM_ZIP OPENING_RELEASE_ROOT ROUND6_ROOT NEW_ROUND7_ROOT
# Commit source before freezing the trained model.
python3 tools/preference-automix/freeze_round7.py \
  OPENING_RELEASE_ROOT/frozen NEW_ROUND7_ROOT
python3 tools/preference-automix/round7_compare.py \
  NEW_ROUND7_ROOT OPENING_RELEASE_ROOT
python3 tools/preference-automix/run_ensemble.py outgoing.opus incoming.opus \
  --release NEW_ROUND7_ROOT/frozen --experimental-cues --out NEW_EMPTY_DIRECTORY
```

The comparison retains every first automatic output/decline for all five pairs,
with matching per-pair export gains, unchanged full-span faders, no EQ, and no
cue/bar/offset overrides. The preceding opening model is the comparison baseline.
Runtime models and research checkpoints remain outside Git; preserve their
checksum manifests and third-party notices. Dataset attribution and source terms
are documented in `../corpus-automix/README.md`.
