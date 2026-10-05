# Manual automix training pilot

This CPU-only experiment learns musical cue preferences and separate automation
from positive transition annotations. It does not retrain Beat This! or infer
chorus/vocal labels. Model weights and recordings remain outside source control.

The first run used three disjoint song pairs and six user-provided recordings.
All three closest available grid candidates ranked first after training. Every
leave-one-pair-out fold missed the reference bar count, so this is an overfitting
pilot and must not replace the default planner. A listening report contains old,
trained, reference-reconstruction, and held-out clips for each pair.

## Run

Requires Python with NumPy/SciPy/Matplotlib, ffmpeg, g++, JDK 17, and the repository's
checksum-verified small0 ONNX model. `bootstrap.py` downloads the checksum-pinned
Kotlin compiler/runtime dependencies from `tools/verification-dependencies.json`.

```sh
python3 tools/manual-automix/prepare.py /path/to/uploads build/manual-automix/data
python3 tools/manual-automix/bootstrap.py build/manual-automix/data learned-beats/models/beat-this-small0.onnx
OPENBLAS_NUM_THREADS=2 python3 tools/manual-automix/train.py build/manual-automix/data /path/to/results
python3 tools/manual-automix/render.py build/manual-automix/data /path/to/results
python3 tools/manual-automix/report.py build/manual-automix/data /path/to/results
```

Input is the supplied `transitions.jsonl` plus six Opus recordings. `prepare.py`
currently maps this pilot's recording names explicitly; adapt its patterns for a
different capture. It preserves source time zero, metadata, SHA-256 hashes, raw
Bezier handles, zero-width jumps and negative reference cues. No duration-based
alignment shift is inferred. The longer yes baby / Closer files still require
listening confirmation of correspondence to the captured versions.

To correct the targets, edit `editable-reference-labels.json` (seconds in `a`, `b`,
`duration`; normalized automation in each side) and run:

```sh
OPENBLAS_NUM_THREADS=2 python3 tools/manual-automix/train.py build/manual-automix/data /path/to/results --labels /path/to/edited-labels.json
python3 tools/manual-automix/render.py build/manual-automix/data /path/to/results
python3 tools/manual-automix/report.py build/manual-automix/data /path/to/results
```

Changing the available grids or recordings requires feature extraction again.
Correction labels affect training and the reference renderer, never inference
inputs. A cue outside the available candidates cannot be reproduced exactly;
`candidate_grid_floor` reports the nearest supported choice in the pilot.

## What was trained

The ranker is a regularized conditional softmax over candidate transitions with
13 measured features and their quadratic interactions (104 learned coefficients).
The preferred candidate is the closest to the annotation within its requested
bar count. Other candidates are proxy negatives, not human-rated bad mixes.
Normalization fits training pairs only. Inference takes no titles, IDs, pair
numbers or target times. Hyperparameters are fixed, with no held-out tuning.

Automation uses kernel ridge regression on the selected candidate's features to
predict a convex mixture of the training envelopes. Raw envelopes remain editable.
Each held-out model excludes that pair's recording features, label and envelope.
This tiny data set cannot demonstrate reliable generalization, and repeated edits
against these folds would turn them into development data.

The candidate experiment calls the existing `MusicalCueRanking` implementation
on supplied provider beat grids and the same per-track shortlist cap (128). It
filters constant speed to 0.80–1.25. It does not run the production pulse/bar/warp
acceptance gates. These clips isolate musical preferences; they are not full
production `PreparedMix` exports. Separate production audit text files run the
unchanged small0 detector and real `LocalMixPlanner.bestTransition` gates.

## Defined rendering behavior

- Original 48 kHz stereo, six seconds before and after the overlap.
- Vendored offline Rubber Band R3, linked stereo, pitch scale 1. The incoming
  speed is constant through the excerpt, including its tail. The production
  variable clock and post-overlap rate-release path are not used in this pilot.
- Negative source time means zero-padded unavailable samples, without clamping
  the cue or advancing source frame zero.
- Volume is normalized linear gain, with 1/sqrt(2) common headroom. The baseline
  uses the actual old cosine/sine crossfade formula. The trained renderer holds
  outgoing volume according to the learned curve.
- Segment-local Bezier x is inverted to obtain y at graph time. Ordered
  zero-width segments are right-continuous changes. Controls use a 2 kHz lookup
  and 5 ms causal smoothing, including endpoint resets.
- EQ uses complementary low/mid/high bands at 200 Hz / 4 kHz: y=0 kills a band,
  .5 is unity, 1 is +6 dB. Empty curves are neutral because these references
  indicate no effect; this is an explicit renderer convention.
- These low-pass styles use cutoff = 30 * (20000/30)^(clamp(2*y,0,1)) Hz,
  Q = .5 + 1.5 * resonance. Values at/above .5 are fully open at 20 kHz;
  their raw coordinates are retained. RBJ coefficients update every 64 frames.
  This is our own mapping; Spotify's physical filter/EQ/gain mapping is unknown.
- One constant export attenuation is shared by all versions of a pair; no
  loudness normalization or compressor masks overlap dips. Encoded Ogg files
  are decoded again to check finite samples and sample clipping.

Reference reconstructions are proposed BeatWeave targets, not recordings of
Spotify playback. Their sound needs user approval before treating them as final
positive training examples. A held-out miss is not proof that a transition sounds
bad; source-time error measures adherence to the user's chosen timing only.

## Kotlin opt-in

Load `mean`, `scale`, and `weights` from the JSON with the host's parser, or read
the three comma-delimited rows of `model-runtime.tsv`, then construct:

```kotlin
val model = TrainedCueModel(mean, scale, weights, "beatweave-manual-pilot-v1")
val plan = LocalMixPlanner.bestTransition(first, second,
    searchOptions = AutoMixSearchOptions(cueModel = model))
```

This changes ranking only, retains the old shortlist, and leaves all acceptance
gates intact. `cueModelId` records when a model actually scored the selection.
The default is null and preserves prior behavior. Gain/EQ/filter automation is
currently exercised by the offline renderer, not wired into production playback.

For numerical parity and the production audit after training:

```sh
python3 tools/manual-automix/bootstrap.py build/manual-automix/data learned-beats/models/beat-this-small0.onnx /path/to/results/model-runtime.tsv
python3 tools/manual-automix/test_pilot.py
```

`AutoMixPlannerTest` also verifies that an opt-in model changes cue preference
while incompatible clocks still reject. Synthetic correctness and source-time
fit do not establish perceived quality, artifact-free sound, or T470 performance.

Next data should include many varied good transitions and specific bad ones
with reasons (early volume cut, clashing vocals, lost beat/drop, wrong section).
Hold out entire recordings, and reserve fresh pairs never used during tuning.
