# Audio structure training

Round 8 addresses a missing capability of the automix scorer: recognizing a
chorus or a section boundary from sound. The preceding timing-only fit changed
weights without changing any of the five automatic development selections.
Repeating that fit is not evidence of musical progress.

This experiment trains two new CPU models from scratch on RWC-P recordings and
AIST human structure annotations: chorus membership and section-boundary
detection. It does not train a beat detector, DJ preference model, overlap-length
model, or fader. The existing automatic transition picker remains unchanged;
structure classification must be validated before it can responsibly influence
that picker. Inference takes audio and frozen weights, with no hand-set cues.

## Data and evaluation

The checksum-pinned RWC-P release contains 100 full songs. Shared artists and
normalized titles stay in the same split: 57 training, 15 validation and 28 test
songs. Byte-identical source recordings are checked across splits. No personal
development recordings participate in fitting, threshold selection or testing.
The corpus is predominantly Japanese pop; held-out artists in this corpus do
not establish generalization to all Western pop, rock, game music or EDM.

Features describe timbre, chroma, relative energy, spectral changes, repetition
and time context every 0.5 seconds. Global normalization and future context mean
the entire recording must be available. Annotation timestamps remain in source
seconds; no grid is invented from these labels. Section evaluation permits a
3-second error and therefore cannot certify beat/bar alignment.

For each task, four boosted-tree sizes are fitted, together with equally tuned
position/duration-only baselines. Thresholds and model choice use validation
macro F1 only. Models and selection are frozen before any test predictions are
computed. A separate untrained novelty baseline is also selected on validation.
Test uncertainty resamples artists as groups. These metrics measure annotated
structure, not transition listening quality. Scores are not calibrated promises.

The frozen run selected 120 trees for chorus recognition and 240 for boundaries,
with 10,440 learned nodes combined. On the 28 test songs, chorus macro F1 was
0.7168 versus 0.6207 for the selected position-only baseline. Boundary macro F1
was 0.6101 versus 0.4124 for position-only and 0.5206 for untrained spectral
novelty. Artist-bootstrap 95% intervals for improvement over position-only were
+4.2 to +13.6 percentage points for chorus and +16.6 to +27.3 for boundaries.
Only seven artists are represented in the test split. No full automix quality
improvement is inferred from these structure metrics.

## Reproduce

Requires Python with NumPy 2.3.5, SciPy 1.17.0, scikit-learn 1.8.0, FFmpeg, curl
and git. CPU work is limited to two numerical-library threads. The audio archive
is about 4.1 GB; allow space for temporary download parts and feature caches.

```sh
python3 tools/structure-automix/acquire_rwc.py build/audio-cue-corpus
OPENBLAS_NUM_THREADS=2 OMP_NUM_THREADS=2 python3 tools/structure-automix/prepare_rwc.py \
  build/audio-cue-corpus NEW_ROUND8_DIRECTORY
OPENBLAS_NUM_THREADS=2 OMP_NUM_THREADS=2 python3 tools/structure-automix/train.py \
  NEW_ROUND8_DIRECTORY
python3 -m unittest discover -s tools/structure-automix -p test_structure.py -v
python3 tools/structure-automix/infer.py SONG \
  --release NEW_ROUND8_DIRECTORY --out NEW_PREDICTIONS.json
```

Preparation can resume complete feature files, checking their annotation hash and
split identity. Training refuses to overwrite an evaluated selection. Portable
JSON exports are checked against the training implementation. Silence, gain
invariance, source-clock placement and one-to-one event matching have regression
checks. Production Kotlin, renderer and timing gates are unchanged; these
Python checks do not imply an Android integration test.

Use a fresh output directory after changing feature extraction. A temporary file
decode was truncated during preparation and rejected by the duration check;
verified ZIP-member bytes now pass directly to FFmpeg. All 100 final decodes
match the published recording durations within 30 ms.

The resulting JSON models and feature caches are research artifacts outside Git.
`infer.py` produces automatic structure diagnostics. It does not move transition
cues, change bar counts, alter volume curves or override clock acceptance.

## Sources and terms

Audio: [RWC Music Database, 2026 release](https://zenodo.org/records/18656623),
RWC-P.zip, publisher MD5 `960a11a2d7fb603ad0dae8428f53d4f0`.
Metadata: [rwc-annotations](https://github.com/rwc-music/rwc-annotations),
commit `0a1a6c31dbe73a7f5d44f7caef8cd0999402a4c2`.
Human structure labels: [rwc-annotations-archive](https://github.com/rwc-music/rwc-annotations-archive),
commit `994499597853664890b517711f97df9f47c0358a`,
`AIST_RWC-MDB-P-2001_CHORUS` (centisecond source times).

Both current repositories and the audio release declare CC BY-NC 4.0. The archive
also preserves older AIST research-only notices. Preserve those notices and
attribution. These checkpoints are research-only and are not promoted into the
library's default distribution. Source audio is not included in result bundles.

Credit Masataka Goto, Hiroki Hashiguchi, Takuichi Nishimura and Ryuichi Oka,
*RWC Music Database: Popular, Classical, and Jazz Music Databases* (ISMIR 2002);
Masataka Goto, *AIST Annotation for the RWC Music Database* (ISMIR 2006); and
Stefan Balke et al., *RWC Revisited: Towards a Community-Driven MIR Corpus*
(TISMIR 2026). The training code is covered by BeatWeave's source license;
dataset and derived research artifact terms remain separate.
