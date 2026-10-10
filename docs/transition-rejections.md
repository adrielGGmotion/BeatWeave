# Explicit transition planning

`LocalMixPlanner.transition` preserves its existing plan for equal corresponding
bar pulse counts. Otherwise it pairs canonical pulses from the selected entry
downbeats for the exact requested outgoing bars. Different internal downbeats do
not have to coincide. This handles 4 vs 3/6 pulses and tracker bar splits without
forcing a 4:3 or 6:4 speed change just to match bar durations.

On mismatched bar pairs, observed pulse intervals within 15% of a 2:1/1:2 tempo
ratio also try the matching octave ratio first. Beat count alone is insufficient
evidence for octave correction. The renderer uses the original clocks and cycle
ratio; the planner never invents intermediate observations. It retries 1:1 if
the octave candidate fails geometry, coverage, pin or clock checks. Pitch and
clock-fit/speed limits remain unchanged.

Inspect `transitionAlignment` for the source indices, fade endpoint and cycle
ratio. The endpoint may be fractional at half time. `incomingBars` is null when
the fade does not end on an incoming downbeat; `barMatches` then claims only
entry alignment. Full original PCM is preserved. Caller pins still address
original incoming canonical indices and must fall inside the consumed fade.

Automatic bar searches retain their existing complete-bar policy. These changes
apply to explicit calls such as Metrolist's own candidate-span search.

## Sparse outgoing evidence

Strict acoustic checks remain the default. Opt in with:

```kotlin
LocalMixPlanner.transition(
    outgoing, incoming, outgoingStartBar = 10, incomingStartBar = 1, outgoingBars = 4,
    options = LocalTransitionOptions(maximumInheritedOutroBars = 2),
)
```

Only a trailing run of at most two selected outgoing bars may inherit acoustic
support from a directly accepted preceding bar in this example. All underlying
pulse geometry and meter/phase gates still pass. Meter must stay constant and
each inherited interval must stay within 10% of the anchor bar's mean interval.
The missing evidence must be sparse local attacks or only unavailable acoustic
context, not a fully active but nonrecurring passage. Internal failed gaps and
incoming bars never inherit. `transitionAlignment.inheritedOutgoingBars` identifies
the original bar indices; these are not claimed as independently verified.

For rejected timing, including a rubato outro, query the last usable region:

```kotlin
val coverage = outgoing.transitionCoverage(isCancelled)
val region = coverage.regions.lastOrNull { it.barCount >= 4 }
if (region != null) {
    val plan = LocalMixPlanner.transition(outgoing, incoming,
        outgoingStartBar = region.endBarExclusive - 4, incomingStartBar = 1,
        outgoingBars = 4, isCancelled = isCancelled)
}
```

`lastAcceptedPulseSeconds` reports geometry acceptance before acoustic/meter
checks; `lastUsableBarEndSeconds` and `regions` report actual strict bar coverage.
Times are relative to the analyzed source window: add its full-track decode
offset when scheduling playback. Empty regions mean no strict span is available;
timing is not extrapolated to make one.

## Rejection messages

| Message / exception | Meaning and caller action |
| --- | --- |
| `Corresponding bar pulse counts differ; pulse alignment is disabled` | Caller selected `allowDifferentPulseCounts=false`. Default explicit planning no longer rejects differing counts alone. |
| `Not enough complete outgoing bars...` | The requested outgoing fade exceeds available complete bars. Try an earlier cue or shorter supported fade. |
| `Not enough observed incoming pulses...` | Incoming observations do not cover the entire requested fade at the chosen ratio. No unobserved endpoint is fabricated. |
| `Bar N is uncertain: underlying beat timestamps are outside a jointly accepted pulse region` | Geometry/phase selection rejects the bar. Sparse acoustic inheritance cannot override it. Use `transitionCoverage()` to schedule before the last usable endpoint. |
| `Bar N is uncertain: complete local recurring attack evidence is unavailable...` | Geometry may pass, but acoustic corroboration does not. Strict mode declines; the bounded outgoing option can accept only the specific sparse trailing case described above. |
| Other `Bar N is uncertain...` / `UncertainBarsException` | Missing observation window, weak/conflicting downbeat evidence, insufficient meter evidence or uncertain phase. The pulse compatibility change does not override these checks. |
| `Requested pulse range crosses uncertain or rejected beat evidence` | The selected range is not contained in an accepted canonical region, including an internal gap. Inspect coverage; do not infer confidence from global BPM/key. |
| `Requested pulse range lacks complete recurring audible attack evidence...` | Acoustic range check failed. The message names missing context, nonrecurring attacks and/or insufficient local attacks. |
| `Pinned incoming canonical beat lies outside the selected ... range` | A caller pin is not consumed by this fade. Move the cue/length or change the caller pins; no pin is silently discarded. |
| `Bar evidence does not refer to the supplied canonical source clock` | Analysis components use different timestamps. Reanalyze with the same source clock. |
| `Selected ... must lie inside ... unchanged source recordings` | Invalid source duration or out-of-bounds anchors. Use the same untrimmed decoding clock. |
| `Beat-clock refinement declined...` / `UnsafeClockFitException` | Available evidence could not meet the unchanged convergence, displacement, residual or speed gates. Its typed `report` contains details. |
| `MixCancelledException` | Cancellation, not a declined musical span. Propagate it out of candidate searches. |

Matching BPM and key do not establish accepted local pulse or bar evidence.
