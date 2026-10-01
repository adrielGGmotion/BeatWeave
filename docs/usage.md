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
silently shorten the fade.
Caller-assembled analyses without energy features use the earlier earliest-incoming,
latest-outgoing policy for fixed lengths. The released 0.10.0 artifacts still use
the earlier policy and a default of 16 bars.

These relative audio features do not identify a chorus, drop, phrase or vocal
part. Global key estimates can also be wrong; matching labels do not prove that
two passages are harmonically compatible. `automaticSelection.musicalCueEvidence`
reports the measured cue score and long-blend inputs when available;
`automaticSelection.policy` indicates the fallback. Explicit bar indices are
zero-based. Every corresponding bar must contain the same number of canonical
pulses, including any matched meter changes.

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
the source and stores prepared output on disk, with pitch ratio fixed at 1.0
and linked stereo channels. It processes one continuous stream rather than
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
