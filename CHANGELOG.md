# Changelog

## 0.11.0

- Let explicit `LocalMixPlanner.transition` pair canonical pulses when bar pulse
  counts differ, retaining the exact outgoing fade length and entry downbeats.
  Incoming fades may end within a bar; `incomingBars` is then null. Inspect
  `transitionAlignment` for the original indices and pulse ratio.
- Try observed 2:1 or 1:2 pulse mappings for near-octave interval differences on
  mismatched bar pairs. No timestamps are inserted or dropped; existing equal-bar
  plans and clock/speed limits remain unchanged.
- Add `LocalTransitionOptions` with strict compatibility controls and opt-in,
  bounded inheritance of sparse trailing outgoing acoustic evidence. Inheritance
  never expands rejected pulse geometry, relaxes meter/phase, or applies incoming.
- Add `LocalSongAnalysis.transitionCoverage()` with strict supported bar regions,
  the last accepted pulse timestamp, the last usable bar endpoint and per-bar
  declines. Callers can move a blend before an unsupported sparse/rubato outro.
- Refresh the versioned model distribution. ONNX weights and model checksums are
  unchanged: these fixes belong to planning and evidence policy, not inference.
- Document transition rejection messages in `docs/transition-rejections.md`.

- Add local monophonic pitch analysis with frequency, note, cents, periodicity
  confidence and unvoiced frames, including bounded-window stereo source analysis.
- Add independent fractional-semitone pitch shifting through offline Rubber Band
  R3, with optional formant preservation, exact duration and bit-exact identity.
- Allow incoming pitch shifts during mixer preparation without repeating the
  stretch pass; existing calls continue to preserve pitch.
- Preserve primary mixer failures when prepared-audio cleanup also fails.
- Keep transition-mode stems at unity gain outside the fade and use a bounded,
  constant-sum sine-squared crossfade instead of attenuating the whole mix by 3 dB.
- Render accepted mix timelines beyond four hours while retaining four-hour
  source limits and bounded PCM reads.
- Avoid a phantom source frame when converting a sample-derived duration back
  to a warp schedule.
- Retry automatic-length candidates outside the ranked shortlist within the
  shared search budgets, and remap caller-pinned canonical beats into scoped grids.
- Rank automatic transition cues using measured energy and onset changes, and
  let `bestTransition` select a supported fade length. Similar tracks with
  balanced overlap levels can receive longer blends.
- Retry the ordered scan for fixed-length transitions when ranked candidates
  yield no accepted plan, retaining the shared search budgets and acceptance gates.
- Preserve automatic-planning failure reports in the demo and name their paths
  in the error message.
- Treat non-positive or non-finite duration evidence as unavailable for
  musical cue ranking.

### Source compatibility

`AutoMixSelectionPolicy` adds `AUDIO_AWARE_RANKING` and
`AUTOMATIC_LENGTH_FALLBACK`. `AutoMixSearchStrategy` adds
`RANKED_TRANSITION_SCAN`. Consumers with exhaustive Kotlin `when` expressions
over these enums must handle the new entries when updating to this source
version. The published 0.10.0 artifacts do not include these changes. The original
`transition` overload is retained. New pulse-aligned plans report only the entry
in `barMatches`; interior bar alignment is not promised. Automatic bar searches
(`autoTransition`, `bestTransition`, `overlap`) retain their compatibility policy.
