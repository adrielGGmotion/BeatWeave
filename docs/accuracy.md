# Accuracy and limitations

BeatWeave 0.10.0 can produce useful transitions, but automatic bar selection is
not reliable enough for unattended mixing across arbitrary music. It sometimes
accepts the wrong bar level or phase, and its checks also reject usable passages.
Model confidence and agreement between detectors do not establish musical truth.

The current source checkout adds an unreleased energy/onset cue ranking pass and
automatic fade-length selection, including longer blends when high-confidence keys
are equal, enharmonic, relative major/minor or adjacent on the same-mode circle of
fifths; tempo, spectral centroid and global loudness affinity checks must also pass,
and longer blends are weighted by local overlap-level balance.
For ordinary cue ranking, average overlap-level differences up to 2 dB are neutral;
larger mismatches receive a score penalty based on the raw measured difference instead of being
silently ignored. Missing local level coverage earns no balance or long-blend bonus and uses the
conservative six-decibel fallback cost rather than substituting the song-wide median.
Dynamics changes require measured energy coverage on both sides; recording boundaries
and energy gaps are neutral rather than being compared with the song-wide median. The onset
contribution is also neutral unless both comparison windows contain enough measured time;
boundary frames are weighted by their exact overlap with the requested window.
Caller-supplied energy blocks outside the declared recording span invalidate cue evidence instead
of selecting an audio-aware policy from measurements that cannot describe the source audio.
Onset-activity normalization uses only frame coverage inside the declared recording span, so
leading or trailing feature padding cannot attenuate otherwise identical in-recording changes.
When a short candidate's endpoint-analysis windows overlap, their dynamics contribution is
weighted by the non-overlapping fraction so one change is not counted as independent evidence at
both ends of the fade.
Ranked cue search groups candidates by their exact meter sequence and shares its bounded pair
budget across compatible groups, so incompatible meters cannot consume the scan or erase measured
cue evidence for a rarer supported sequence.
Positive downbeat peaks are attached to the canonical beat clock only within the model's
three-frame (60 ms) observation tolerance; more distant peaks remain unsupported instead of being
moved onto unrelated beats. The ordered peak clocks are matched in linear time, with cooperative
cancellation retained through postprocessing on long recordings.
Non-finite onset clocks and envelope values cannot provide independent audio support for inserting
or relocating canonical pulses, or inflate candidate onset agreement during pulse selection.
Candidate onset agreement uses each evaluated tempo candidate's selected analysis, counts only
neural events inside its measured envelope span, and remains unusable unless that span covers at
least 55% of the candidate events. Leading or trailing envelope padding outside the declared
recording duration cannot contribute matches or measured coverage.
The independent fallback beat clock must also have a finite recording duration and finite, strictly
ordered observations inside that duration before it can confirm or repair the model pulse.
The public beat-grid quality audit likewise rejects a supplied recording duration that is non-finite
or non-positive instead of certifying timestamps against malformed source metadata. When callers
supply an independent reference clock, it must contain at least three observations; its timestamps
must be finite, non-negative, strictly ordered, and inside that duration, and its strengths must be
finite. Malformed or insufficient references fail the audit instead of being filtered out of
cadence comparisons. At least 80% of candidate intervals must also be bracketed by the measured
reference clock with a bracket between 0.62 and 1.48 times its neighboring local period; the audit
does not treat an extrapolated edge cadence, an internal observation hole, or a duplicate reference
observation as independent evidence. The bracket under test is excluded from its own cadence
baseline.
The report exposes that exact measured fraction as `referenceCoverage`; it is `null` when callers
do not supply a valid independent reference clock. This diagnostic does not relax the 80% gate.
Canonical pulse agreement likewise scores only intervals bracketed by usable reference observations;
unmeasured edges and internal holes cannot raise its numerator or denominator.
Duplicate-pulse removal also requires that measured cadence at the short interval; the normalizer
does not delete an original model observation using an extrapolated edge or internal-gap period.
Weak-pulse relocation applies the same rule in both correction passes, so an audio attack outside
measured reference cadence cannot move an original model timestamp onto an extrapolated grid.
Missing-pulse insertion requires measured reference cadence across the model gap and separately at
every implied pulse; an onset outside that coverage cannot regularize the model clock using an
extrapolated period.
Quiet cadence interpolation likewise requires measured cadence at the implied pulse itself, so
supported repairs on either side cannot bridge an internal reference hole.
Phase assessment and per-event phase support apply the same local-cadence check; observations
inside a reference hole or abnormally short duplicate bracket do not count toward phase coverage
and cannot authorize a canonical range. Regional phase windows stop at those brackets, and their
coverage counts only measured observations.
Automatic pulse diagnostics report that missing-reference rejection without deriving phase, onset,
or score evidence from the malformed candidate.
Automatic pulse selection also propagates cooperative cancellation through pulse preprocessing,
normalization and grid-quality auditing; cancellation does not relax any timing gate.
Clock-quality assessment also streams ordered beat sections without materializing and sorting a
second knot list, and automatic planning polls cancellation throughout that scan.
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
  Stereo analysis uses one-decibel channel-selection hysteresis to avoid note
  flicker from negligible balance changes; it does not separate stereo sources.
  Cancellation is polled throughout maximum-size window and FFT passes, with no
  analyzer-owned linear loop or transform section exceeding 32,768 iterations
  or butterflies between polls.
- Nonzero pitch shifting uses offline Rubber Band R3 with linked stereo and
  optional formant preservation. It is not lossless and can introduce artifacts,
  especially at large shifts. Synthetic tone and stereo regressions do not
  establish transparent quality on arbitrary recordings.
- BPM, key and meter are estimates. Key chroma compensates for a consistent global tuning offset,
  and key confidence separates the selected harmonic family from incompatible profiles rather
  than claiming an exact-mode probability; low-confidence full mixes and local or changing tuning
  remain ambiguous. Half-time,
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
