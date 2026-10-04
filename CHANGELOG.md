# Changelog

## Unreleased

- Add local monophonic pitch analysis with frequency, note, cents, periodicity
  confidence and unvoiced frames, including bounded-window stereo source analysis.
- Add independent fractional-semitone pitch shifting through offline Rubber Band
  R3, with optional formant preservation, exact duration and bit-exact identity.
- Allow incoming pitch shifts during mixer preparation without repeating the
  stretch pass; existing calls continue to preserve pitch.
- Preserve primary mixer failures when prepared-audio cleanup also fails.
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
version. The published 0.10.0 artifacts do not include these changes.
