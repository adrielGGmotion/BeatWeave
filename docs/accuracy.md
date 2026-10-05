# Accuracy and limitations

BeatWeave 0.10.0 can produce useful transitions, but automatic bar selection is
not reliable enough for unattended mixing across arbitrary music. It sometimes
accepts the wrong bar level or phase, and its checks also reject usable passages.
Model confidence and agreement between detectors do not establish musical truth.

The current source checkout adds an unreleased energy/onset cue ranking pass and
automatic fade-length selection, including longer blends when high-confidence keys
are equal, enharmonic, relative major/minor or adjacent on the same-mode circle of
fifths; tempo, spectral centroid, loudness and overlap-level gates must also pass.
Dynamics changes require measured feature coverage on both sides; recording boundaries
and feature gaps are neutral rather than being compared with the song-wide median.
The 0.10.0 measurements below predate that change;
they do not measure its musical quality. The new scores do not classify choruses,
drops or vocal overlap. Compare its rendered choices by listening before treating
it as suitable for unattended mixing.

The results below are from the archived **0.10.0 validation run**, before this
repository reorganization. They are not a fresh execution of the repository's
tests. The full report, frozen input identities, output hashes and failure records
are in the separate `BeatWeave-0.10.0-evidence.zip`, under `validation/`.

## Annotated music

The regression cohort contains 41 recordings and 246 fixed transition requests.
A second cohort contains 29 recordings and 174 requests, fixed before the local
evaluation: 20 GTZAN clips, six Candombe recordings and three ASAP performances.
Both model variants were evaluated separately, without manual BPM or cue changes.

A correct source bar must match consecutive annotated downbeats within 70 ms
and contain the expected canonical pulse count. A 30 ms metric is also retained
in the archive. Missing bar annotations remain unscorable rather than being
removed or relabeled.

| Version / model | Cohort | Accepted requests | Correct source-bar plans | Plans with incorrect bars |
| --- | --- | ---: | ---: | ---: |
| 0.9 / small0 | Previous 41 | 97 / 246 | 62 | 35 |
| 0.10 / small0 | Previous 41 | 77 / 246 | 49 | 28 |
| 0.10 / final0 | Previous 41 | 73 / 246 | 61 | 12 |
| 0.10 / small0 | New 29 | 74 / 174 | 48 | 26 |
| 0.10 / final0 | New 29 | 72 / 174 | 52 | 20 |

No accepted 0.10 plan used an unscorable bar. All four current runs included
correct 32-bar examples, but all failed the zero-wrong-bar and zero-wrong-plan
release criteria. The small0 regression results lose correct accepted transitions
as well as incorrect ones; they do not demonstrate an overall accuracy improvement.

Results vary substantially by dataset. On the new cohort, both models selected
correct bars in all 36 Candombe requests and declined all 18 ASAP requests. On
GTZAN, small0 accepted 38 of 120 requests with 12 correct source-bar plans; final0
accepted 36 with 16 correct. These aggregates are not a general music accuracy rate.

The new cohort was unseen by the local evaluation, but not entirely unseen by
the pretrained model. Upstream excludes GTZAN from training; Candombe and ASAP
are training corpora. In the previous cohort, ten GuitarSet accompaniment
recordings overlap training and two solo recordings do not.

Correct bar endpoints and pulse counts do not guarantee every interior beat.
For the new-cohort plans passing those checks, annotation-relative beat residuals
had p95/max values of 31.05/78.90 ms for small0 and 28.60/108.49 ms for final0.
These are measurements of the returned timing maps on repeated selected windows,
not independent measurements of audible attacks.

## Rendering and integration

The historical host verification passed 143 common/JVM tests, native PCM fixtures,
seeking, cancellation and cache cleanup checks. A complete-song synthetic fixture
recovered 511 of 511 events, with timing p95 14.00 ms and maximum 21.56 ms against
original observations; its worst rolling pitch error was 0.098 cents. Synthetic
clock tests verify the renderer, not automatic interpretation of real music.

Android verification covered compilation, R8/JNI retention and native packaging,
including 16 KiB alignment. No Android device or emulator was run. Linux x86_64/JVM
was exercised; Apple builds and runtime were not. Declared iOS targets do not
include iOS inference or stretching adapters.

Supplied-song checks also found coverage regressions. In 0.10, both models rendered
a 16-bar *Yeah, No → Somebody Told Me* transition, but declined full shared
beat-only overlap. Both declined a requested 16-bar *The Last Page → Homicide Love*
transition, while accepting 2, 4 and 8 bars. These recordings have no independent
beat/bar annotations in the project, so render success does not establish musical
accuracy, and rejection does not establish that a transition would sound bad.

## Practical limits

- The unreleased `PitchAnalyzer` estimates monophonic fundamental frequency; it
  does not extract a lead melody from a complete mix. Periodicity confidence is
  not a calibrated accuracy probability, and octave errors remain possible.
- Nonzero pitch shifting uses offline Rubber Band R3 with linked stereo and
  optional formant preservation. It is not lossless and can introduce artifacts,
  especially at large shifts. Synthetic tone and stereo regressions do not
  establish transparent quality on arbitrary recordings.
- BPM, key and meter are estimates. Key chroma compensates for a consistent global tuning offset,
  but low-confidence full mixes and local or changing tuning remain ambiguous. Half-time,
  double-time and downbeat phase can remain ambiguous even when a clock is regular.
- A successful plan passes the implemented evidence and timing checks. It can
  still use the wrong musical interpretation.
- Matching applies to the plan's declared observed span. Outside it, overlap
  playback continues at the endpoint rate without additional beat observations.
- Automatic cue selection is deterministic bar selection, not verse/chorus or
  phrase recognition. Provider-declared bars require an external interpretation.
- Large models and prepared PCM need memory and temporary disk space. Recorded
  host resource measurements are not handset performance guarantees.

For the full accounting, consult `validation/RELEASE-VALIDATION.md` and
`validation/automix-0.10.0/REPORT.md` inside the evidence archive.
The latter includes per-dataset results, declined requests, reference provenance
and the separate beat-only overlap evaluation.
