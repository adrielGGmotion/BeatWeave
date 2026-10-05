# Corpus transition learning experiment

The three-example pilot overfit. Listening feedback accepted only its second
transition; the first was rejected and the third needed timing work. This second
round acquires its own published corpus, learns two new models, and evaluates
them on recordings excluded from training. It does not require additional user
uploads. No new model is enabled in production playback.

## What was actually trained

* Beat-phase scoring: 49,920 perturbed phase candidates from 768 windows of 150
  usable recordings (152 downloaded, two lacked usable windows). Four audio
  onset bands feed a 160-tree histogram gradient-boosting classifier.
* DJ timing prior: 42,210 candidates for 4,690 observed transitions. Eleven
  position/tempo/duration features feed another 160-tree classifier. These
  features do not describe vocals, choruses, keys, or timbre.
* Fader policy: **not trained in this round**. The annotations do not provide
  actual human fader automation. New first-pair proposals freeze the automation
  of the user-approved pilot output. The third pair freezes its own automation.

Both models use fixed hyperparameters, seed 20261005, CPU execution and portable
JSON exports. Numeric inference parity is checked against scikit-learn. Recording
IDs and normalized track titles are disjoint across DJ folds; mixes/DJs may cross
folds. Audio folds group original filenames and deduplicate byte-identical audio.
Versions/masterings with different names may escape this grouping. The six user
tracks are development examples, excluded from these new fits and evaluations.

## Measured results (2026-10-05)

| Task | Held-out result | Interpretation |
| --- | --- | --- |
| Beat phase, 23 recordings / 124 windows | Median 15.06 ms; 61.29% within 30 ms; p90 139.65 ms | Against published **automatic** IRCAM beats, not manually verified truth |
| Simple low-band onset baseline | Median 58.78 ms; 25.81% within 30 ms | This is not the full old BeatWeave model |
| DJ observed-choice ranking, 229 transitions / 441 recordings | Top-1 12.66%; top-3 46.72%; mean rank 3.93 | Nine alternatives; chance top-1 11.11%. Weak exact-choice performance |

Negatives are generated phase/cue perturbations, not human bad-transition ratings.
The timing prior alone is insufficient. These metrics cannot establish listening
quality, correct chorus selection, or user preference. Test sets were not used
to tune model hyperparameters. Future model revisions should reserve a fresh
test set before using these reported test errors for development decisions.

## Corpus acquisition and training

Use Python 3.11+ (this run used 3.12.14), ffmpeg and curl. Run from the repository
root. Approximately 2.8 GB of archives plus extracted/cache files are downloaded.
Leave generated data, audio and model weights under ignored build/output paths.

```sh
python3 -m venv .venv-training
.venv-training/bin/pip install -r tools/corpus-automix/requirements.txt
export OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2
.venv-training/bin/python tools/corpus-automix/acquire.py build/corpus-training
.venv-training/bin/python tools/corpus-automix/train_phase.py build/corpus-training build/corpus-results
.venv-training/bin/python tools/corpus-automix/train_style.py build/corpus-training build/corpus-results
.venv-training/bin/python -m unittest discover -s tools/corpus-automix -p 'test_*.py' -v
```

Cached feature arrays are reused. Start with a fresh build directory when changing
feature extraction, splits or source data. Acquisition verifies official UnmixDB
MD5 values and pins the observed Raveform archive SHA-256. The latter is a local
reproducibility pin, not a publisher-supplied authenticity claim.

Sources:

* [UnmixDB](https://zenodo.org/records/1422385), Diemo Schwarz and Dominique Fourer,
  IRCAM (2018): sets 044, 123, 222 and 275; music excerpts and automatic beat
  annotations. Cut/join mapping is applied to timestamps; windows across the
  excerpt splice are excluded. Zenodo metadata names CC BY-NC-ND 4.0 while the
  archive license link names BY-NC-ND 3.0. Preserve both and the per-track
  attribution; these are restricted research inputs, not unrestricted product
  audio. This project does not relicense them or redistribute their audio.
* [Raveform](https://mir-aidj.github.io/raveform/), official
  [dataset](https://huggingface.co/datasets/taejunkim/raveform): estimated track/mix
  alignments from real DJ mixes. No Raveform audio is downloaded. Alignment cost,
  tempo, coverage and overlap checks are explicit in `train_style.py`.
  `match_rate` describes source coverage, not confidence, and is not used as a
  confidence filter. Upstream data terms still apply.

## Acoustic cue proposals and listening review

`acoustic_cues.py` uses independently pretrained
[CUE-DETR](https://github.com/eth-disco/cue-detr) (MIT), upstream code revision
`d0462856ed2f59a1fb65267cfbe87340a65ad1bb`. BeatWeave did not train this checkpoint.
Pretraining overlap with the six commercial development recordings is unknown.
Inference follows its mel/viridis/window preprocessing, with batches of four on
two CPU threads. Temporal peak suppression uses seconds rather than candidate
indices. Raw scores are retained; they are not calibrated probabilities of a
good transition.

The optional inference environment is separate because torch 2.3 uses NumPy 1.x:

```sh
python3 -m venv .venv-corpus
.venv-corpus/bin/pip install torch==2.3.1+cpu torchvision==0.18.1+cpu --index-url https://download.pytorch.org/whl/cpu
.venv-corpus/bin/pip install numpy==1.26.4 transformers==4.42.3 timm==1.0.7 librosa==0.10.2.post1 matplotlib==3.10.8
mkdir -p build/corpus-training/cue-checkpoint
curl -fL https://huggingface.co/disco-eth/cue-detr/resolve/main/model.safetensors -o build/corpus-training/cue-checkpoint/model.safetensors
curl -fL https://huggingface.co/disco-eth/cue-detr/resolve/main/config.json -o build/corpus-training/cue-checkpoint/config.json
```

The inference script checks checkpoint SHA-256
`195b80f5c94e4b424b417a7c04e0aac238082c584ddcb65d01be8a97c69cadd3`.
It disables extra pretrained-backbone downloads and checks for missing weights.
The checkpoint is not checked into this repository.

After reproducing `tools/manual-automix` preparation and its baseline outputs:

```sh
HF_HUB_OFFLINE=1 .venv-corpus/bin/python tools/corpus-automix/acoustic_cues.py build/manual-automix/data build/corpus-training/cue-checkpoint build/corpus-results/acoustic-cues.json
.venv-training/bin/python tools/corpus-automix/propose.py build/manual-automix/data build/manual-results build/corpus-results
.venv-training/bin/python tools/corpus-automix/render_review.py build/manual-automix/data build/manual-results build/corpus-results build/corpus-training
```

Replace `build/manual-results` with the previous pilot output directory. The
offline R3 binary must already be built by that pilot. The report embeds Ogg
audio and needs no server. The results ZIP excludes downloaded source audio and
the CUE-DETR checkpoint. It includes private listening derivatives of the user's
six recordings; keep that archive out of the public repository.

Automatic proposals combine acoustic support, a small timing-prior term and
existing level/affinity evidence with **hand-set weights**. Provider grids and
fixed search bounds isolate musical cue choice; this is not end-to-end training.
The phase guard is experimental, not confidence-calibrated. An uncertain timing
trial is explicitly separated from an accepted adjustment. Negative incoming
positions retain source-clock meaning through zero padding.

Feedback-directed review preserves the approved second-pair audio byte for byte,
tries a new first-pair cue, and isolates a +69.04 ms incoming-cue trial for the
third pair. No listening improvement is asserted. The third-pair score margin
fails the experimental automatic-adjustment guard; its diagnostic trial is not
automatically accepted. The old source cues, normalized gain curves and production
gates remain inspectable. Four newly encoded clips were checked for finite samples,
exact R3 duration and decoded clipping; the fifth clip is the untouched control.

The next missing work is an audio-conditioned handoff/fader policy with real
mix/source supervision and independent human listening evaluation. Neither the
weak timing prior nor frozen volume templates fulfill that objective yet.
