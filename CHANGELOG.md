# Changelog

## Unreleased

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
