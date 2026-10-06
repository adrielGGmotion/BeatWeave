# Using BeatWeave

Build and publish the modules locally as described in [build.md](build.md), then
install one of the pinned models from [models.md](models.md). The examples below
use the Android/JVM backend and assume the host supplies decoded PCM and a sink.

## Analyze a recording

Decode the original recording to mono `FloatArray` PCM at **22050 Hz**. Use a
band-limited resampler and preserve leading silence. Keep the same untrimmed
source clock when decoding stereo PCM for rendering.

```kotlin
import org.metrolist.beatweave.learned.LocalSongAnalyzer
import org.metrolist.beatweave.learned.OrtBeatThisBackend

val (firstAnalysis, secondAnalysis) =
    OrtBeatThisBackend(modelBytes, threads = 2).use { backend ->
        val analyzer = LocalSongAnalyzer(backend)
        analyzer.analyze(firstMono22050, cancellationCheck = checkCancelled) to
            analyzer.analyze(secondMono22050, cancellationCheck = checkCancelled)
    }
```

`modelBytes` is the chosen local ONNX file. `checkCancelled` is a host-supplied
`() -> Unit` that throws when cancellation is requested. Run analysis on a worker
thread. Cancellation is checked during analysis and between inference chunks;
it cannot interrupt an individual native ONNX call. The backend owns its ONNX
session and must be closed.

The result keeps the estimated clock and its evidence separately:

| Property | Contents |
| --- | --- |
| `audio` | Tempo alternatives, beats/downbeats, approximate key, energy and spectral features |
| `model` | Original model events, scores and logits |
| `reference` | Independent spectral analysis |
| `pulse` | Canonical beat proposal, repairs and unresolved issues |
| `pulseSelection` | Selection reasons and alternative metrical levels |
| `pulseRegions`, `excludedPulseRegions` | Accepted ranges and local rejection reasons |
| `barTracking` | Proposed bars, boundary evidence and per-bar usability |
| `acousticPulse` | Local attack recurrence and activity on the canonical clock |

These confidence scores are not calibrated probabilities of musical correctness.
For current measured errors, see [accuracy.md](accuracy.md).

Cache analyses by recording/decoding identity, complete model identity and library
analysis-policy version. Recompute 0.9 analyses for 0.10, including their bar and
region checks; unchanged raw timestamps do not imply unchanged eligibility.

## Analyze pitch (unreleased source API)

```kotlin
import org.metrolist.beatweave.PitchAnalyzer

val pitches = PitchAnalyzer().analyze(monoPcm, sampleRate, checkCancelled)
val voiced = pitches.frames.filter { it.isVoiced }
// Each voiced frame exposes frequencyHz, midiNote, noteName, cents and confidence.
```

Pitch analysis uses FFT-accelerated YIN and runs independently of beat/key analysis.
It estimates the fundamental frequency of isolated voices or instruments; a full
mix can produce octave errors or ambiguous results. It does not extract a lead
melody or transcribe chords. Confidence measures periodicity, not musical accuracy.
Unvoiced windows have a null frequency and note. Notes use A4 = 440 Hz and cents
relative to the nearest equal-tempered note.
Estimates within one cent of a configured frequency endpoint are clamped to that
endpoint to avoid rejecting a boundary tone due to interpolation error.

The default range is 50–2000 Hz, with 20 ms hops and a window of at least 80 ms.
`PitchAnalysisOptions` configures the range, window, hop and silence threshold.
The analysis rate must provide at least five samples per period at the maximum
configured frequency; the default range therefore needs a rate of at least 10 kHz.
Only complete windows are analyzed, and timestamps identify window centers on
the original audio clock. The `StereoPcm` overload reads bounded windows, choosing
the channel with greater energy after DC removal to avoid selecting a biased,
silent channel or cancelling antiphase stereo. The host supplies
band-limited resampling when the requested analysis rate differs from the source.

## Shift pitch (unreleased source API)

```kotlin
import org.metrolist.beatweave.PitchShift
import org.metrolist.beatweave.rubberband.RubberBandEngine

val shifted = RubberBandEngine(cacheDirectory).preparePitchShift(
    stereoPcm, sampleRate = 48000,
    pitchShift = PitchShift(semitones = 2.0, preserveFormants = true),
    progress = reportProgress,
)
try {
    val block = shifted.readFrames(0L, 4096, 48000)
    // Reuse the prepared audio for playback, seeking or export.
} finally {
    shifted.close()
}
```

Positive semitones raise pitch; negative values lower it. Fractional values permit
fine tuning (0.01 semitones = one cent). Shifts are limited to ±24 semitones.
Pitch-only processing retains the original frame count at the requested rate.
Nonzero Rubber Band shifts require at least 100 ms of source and output audio;
shorter input fails explicitly. The decoded PCM rate must be 8000–192000 Hz.
Formant preservation is enabled by default to retain the original spectral
envelope, especially for vocals; it can be disabled for deliberate transposition
of that envelope. The backend uses offline R3, two passes and linked stereo.
Zero pitch and unchanged duration bypass DSP and retain the original PCM exactly.
Nonzero shifting is not lossless; large shifts and complex material can introduce
audible artifacts. This API does not add lossy encoding or limiting.

To combine incoming pitch and beat matching in one preparation, pass the shift
to the checked planner's preparation method:

```kotlin
val session = transition.prepare(
    firstStereoPcm, secondStereoPcm, RubberBandEngine(cacheDirectory),
    incomingPitchShift = PitchShift(-1.0),
    isCancelled = isCancelled, progress = reportProgress,
)
```

Existing mixer calls preserve pitch. A nonzero shift requires `PitchShiftEngine`;
an engine implementing only time stretching fails explicitly. Pitch is constant
through the incoming recording; pitch automation and automatic key matching are
not implemented. Both APIs above are source additions and are absent from 0.10.0.

## Choose a mix

```kotlin
import org.metrolist.beatweave.learned.LocalMixPlanner

val transition = LocalMixPlanner.bestTransition(
    firstAnalysis, secondAnalysis, outputSampleRate = 48000,
    isCancelled = isCancelled,
)

val fixedLength = LocalMixPlanner.autoTransition(
    firstAnalysis, secondAnalysis, bars = 8,
)

val selectedTransition = LocalMixPlanner.transition(
    firstAnalysis, secondAnalysis,
    outgoingStartBar = 8, incomingStartBar = 4, outgoingBars = 16,
)

val overlap = LocalMixPlanner.overlap(firstAnalysis, secondAnalysis)
```

In the current source checkout, `bestTransition` ranks timing-safe candidates
across **2, 4, 8, 16 and 32 bars**. It uses measured energy and onset changes to
favor an incoming lift and an outgoing release. When the global key estimate,
tempo, spectral centroid and loudness agree, it also rewards longer spans whose
measured levels stay balanced through the candidate overlap. This can select a
16-bar blend for compatible recordings while keeping the clock and bar checks.
`autoTransition` ranks cues at its requested length (16 by default); it does not
silently shorten the fade. Both automatic transition APIs restrict the incoming
**start** to the first 30 seconds or first 10% of the decoded recording, whichever
is shorter. The overlap may continue beyond that window. This is an eligibility
rule before ranking, not a score preference; a trained model, ordered retry,
pinned beat or alternative detector cannot move the entry past it. If no opening
candidate passes the normal checks, the planner declines. The search report's
`incomingStartLimitSeconds` records the absolute limit. Callers can explicitly
configure both bounds with `AutoMixSearchOptions.maximumIncomingStartSeconds`
and `maximumIncomingStartFraction`; ordinary automix uses the opening defaults.
Manual bar selection and full-track `overlap` keep their existing behavior.

If the shortlisted cues yield no accepted plan, the
planner retries the earlier earliest-incoming, latest-outgoing scan within the
remaining candidate-pair and clock-fit budgets. Fixed-length requests retain
their exact length; automatic requests retry 8, 4, 16, 2 and 32 bars in that
conservative order. This can recover a compatible meter sequence omitted by the
shortlist without relaxing any timing checks. If a ranked transition scan or a
variable-meter overlap scan reaches its candidate-pair budget, it still tries
the candidates already collected within the separate clock-fit budget. Small
transition budgets may cover only a subset of fade lengths. If no candidate
passes before the remaining search budget is exhausted, the planner reports
`SEARCH_LIMIT_REACHED`.
Caller-assembled analyses without energy features use the earlier earliest-incoming,
latest-outgoing policy for fixed lengths. The released 0.10.0 artifacts still use
the earlier policy and a default of 16 bars.

These relative audio features do not identify a chorus, drop, phrase or vocal
part. Long-blend affinity accepts high-confidence enharmonic keys, relative
major/minor pairs and same-mode circle-of-fifths neighbours; the independent tempo,
timbre and loudness gates still have to pass. Global key estimates can be wrong, so
this relationship does not prove that two passages are harmonically compatible.
`automaticSelection.musicalCueEvidence` reports the measured cue score and
long-blend inputs when available; `automaticSelection.policy` indicates the
fallback. Explicit bar indices are zero-based. Every corresponding bar must contain
the same number of canonical pulses, including any matched meter changes.

For `LocalMixPlanner` and `AutoMixPlanner` bar operations,
`ClockFitOptions.pinnedIncomingBeats` uses original incoming `pulse.beats` indices.
An explicit selection must contain every requested pin; automatic searches select
a supported range containing them. The planner remaps those indices onto its
cropped clock and keeps the corresponding source timestamps fixed. The low-level
`BeatClockRegularizer.regularize` API instead accepts indices of the grid passed
directly to it.

`overlap` selects the longest supported common bar sequence by outgoing duration.
Both complete recordings remain in the output. Only the selected common span is
claimed as matched; introductions and tails continue at the endpoint rate.
A transition fades across its selected bars and then smoothly returns the
incoming recording to its original tempo.

Inspect `automaticSelection` for selected original indices and search diagnostics,
and `barMatches` for the original/prepared boundary times. Bar groups do not
identify verses, choruses or musical phrases.

Use `barTracking.grid()` or `bars()` to draw proposed bars in an editor. Check
`barTracking.isBarUsable(index)` and `issuesForBar(index)` to distinguish supported
bars from uncertain proposals. Choosing a visible bar does not bypass the planner's
range checks.

Planning can decline a request. `AutoMixPlanningException.report` distinguishes
missing evidence, incompatible bars, unsafe coverage, rejected clocks and search
limits. Keep the current playback available while planning; the host decides
whether to skip the transition, select another recording or use a plain fade.
No fallback is applied by the library. Search limits are configurable through
`AutoMixSearchOptions`, and search is cancellable.

For beat-only overlap, use `LocalMixPlanner.beatOverlap`. It accepts optional
`outgoingCueBeat` and `incomingCueBeat` indices and makes no bar-alignment claim.
`BeatOverlapPlanningException` reports a declined request. `beatCoverage` retains
the original paired span; `matchedSpan`, when present, gives the narrower span
with sufficient acoustic context. Unsupported interior passages are not skipped.

## Prepare and render

The host implements `StereoPcm` for both original recordings and `PcmSink` for
the output. Each source read returns exactly `frames * 2` finite interleaved
stereo samples at the requested rate, with zero padding outside the recording.
Override `readFrames` when exact integer-frame access is available. Reads must be
deterministic, and source content and duration must stay unchanged.

```kotlin
import org.metrolist.beatweave.rubberband.RubberBandEngine

val session = transition.prepare(
    firstStereoPcm, secondStereoPcm, RubberBandEngine(cacheDirectory),
    isCancelled = isCancelled,
    progress = reportProgress,
)
try {
    session.renderComplete(pcmSink, transition.mode)
} finally {
    session.close()
}
```

The inputs above are host-owned. `isCancelled` is `() -> Boolean`,
`reportProgress` is `(Double) -> Unit`, and `cacheDirectory` is a writable
`java.io.File` (for example, Android's `Context.cacheDir`). Configure the sink for
the plan's output sample rate and interleaved stereo float PCM.

Preparation runs off the UI and audio callback threads. Rubber Band snapshots
the source and stores prepared output on disk, preserving pitch by default
and linking stereo channels. It processes one continuous stream rather than
restarting at each beat. Provide enough temporary disk space and handle failure
before replacing an existing playable session.

Reuse the session for playback, seeking and export. Serialize reads and `close()`;
sessions are not thread-safe. Closing is idempotent and deletes owned temporary
files. The host still owns its decoders and sink.

`frameCount(mode)` gives the complete export length. `startFrame(mode)` is its
global timeline origin, which can be negative for overlap pre-roll. An event at
global frame `g` appears at file frame `g - startFrame(mode)`. `renderComplete`
preserves this pre-roll; `render` and `renderFrames` start at nonnegative global
positions. `endFrame` is an exclusive global endpoint, not a file length.

Float output can exceed full scale after stretching and mixing. Measure the
prepared mix and apply constant attenuation where needed before integer encoding
or device playback. A normalized input alone does not prevent output clipping.

Android AARs include the native engine and R8 consumer rules. The JVM JAR bundles
the Linux x86_64 (glibc) native engine and loads it automatically. A library supplied
through `java.library.path` takes precedence; see [build.md](build.md). Missing
native support fails explicitly.

## Timing and advanced inputs

The learned detector has 20 ms frame resolution. If a clock fails the warp limits,
bounded fitting may adjust incoming estimates by up to 20 ms by default. Outgoing
anchors, selected cues, fit endpoints and selected downbeats remain pinned.
`clockFit.adjustments` retains original/adjusted times; residuals are measured
against original observations. An accepted clock is left unchanged. Set
`ClockFitOptions(maximumDisplacementSeconds = 0.0)` for precise annotations that
must not move. A rejected fit raises `UnsafeClockFitException`.

Prefer the checked `LocalMixPlanner` facade. Raw `MixPlan` and `TransitionPlanner`
are geometry APIs; callers using them must validate source evidence and timing.
The no-argument `matchingGrid()` cannot certify a full acoustically audited clock:
the first four and final three intervals lack centered context. Use a checked
planner or a supported `matchingGrid(startBeat, endBeatExclusive)` range.
Those indices address `pulse.beats`, with the terminal anchor included.

If another provider already identifies main beats and bars, an
`ObservedMainBeatGrid` can declare them as an ordered subset of original canonical
beat indices. It also stores the exact canonical clock, bar-boundary indices
within that subset, supported bars and provider identity. The declaration does
not overwrite the library's inferred bars.

`declaredTransition` selects explicit declared bars; `declaredAutoTransition`
chooses cues within supplied declarations. Corresponding main beats are matched
one to one, so unmatched subdivisions need not force tempo modulation. All
original canonical spans still undergo pulse/acoustic checks. These APIs do not
infer the provider's interpretation. `declaredMainSelection` retains its provenance
and original/prepared correspondences. Additional `pinnedIncomingBeats` use
original incoming canonical indices and must refer to selected main beats.
# Experimental manual transition training

The optional `AutoMixSearchOptions(cueModel = TrainedCueModel(...))` ranks musical
cue candidates using locally trained preferences. The default remains unchanged;
beat/bar/clock acceptance gates still apply. The current three-pair pilot overfits
and is not a general-purpose automix model. Its volume, EQ and filter training is
an offline experiment, not yet production playback automation. See
[manual training](../tools/manual-automix/README.md) for reproduction and limits.

The follow-up [corpus training experiment](../tools/corpus-automix/README.md)
downloads its own music/annotation data, trains portable phase and timing-prior
models, and evaluates on held-out recordings. Its acoustic cue proposals and
listening renderer remain offline. The timing prior is weak and no new expert
fader policy was learned; these checkpoints are not enabled by this API.
