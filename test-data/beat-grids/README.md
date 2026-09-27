# Beat grid fixtures

These CSV files contain the original detected pulse timestamps used by
`tools/FullSongProfile.kt`. The harness generates audio impulses on those clocks,
renders the resulting time warp, and measures it against the unchanged source
observations.

`track-1-beats.csv` came from The Goo Goo Dolls' "Iris";
`track-2-beats.csv` came from Conan Gray's "Eleven Eleven". Both were generated
with the 0.7.0-rc1 analyzer at 22050 Hz. Keep the original timestamps intact when
updating the renderer.

The files are regression inputs, not human beat annotations. They test rendering
and timing behavior; they cannot establish whether the original detector chose
the correct musical beat. They contain no audio.
