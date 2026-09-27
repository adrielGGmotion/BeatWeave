# Optional Rubber Band engine

This optional module and its adapter code are licensed GPL-2.0-or-later.
The separate `analysis` core retains its MIT license. Distribution of an app
linking this engine must comply with Rubber Band's license; a commercial license
is a separate option offered by its authors. No commercial license is included.

The full, unmodified Rubber Band source tree is included under
`native/vendor/rubberband`, with its upstream COPYING and notices.

- Project: https://github.com/breakfastquay/rubberband
- Release: `v3.3.0`
- Commit: `2be46b0dffb13273a67396c77bc9278736bb03d2`
- Archive: https://codeload.github.com/breakfastquay/rubberband/tar.gz/refs/tags/v3.3.0
- Archive SHA-256: `2bb837fe00932442ca90e185af8a468f7591df0c002b4a9e27a1bced1563ac84`
- Per-file source checksums: `native/vendor/rubberband-source-sha256.json`

Compilation uses upstream `single/RubberBandSingle.cpp`, its built-in FFT and
resampler, and no downloaded runtime service. On Apple platforms that upstream
file uses Accelerate. The optional C ABI header is provided for platform ports;
the shipped Kotlin engine currently supports Android and JVM. An iOS Kotlin
engine has not been implemented or validated.

Android binaries use NDK r27c (27.2.12479018), min API 26, static C++ runtime and
16 KiB ELF segment alignment. The shipped Android ABIs are arm64-v8a and x86_64.
They have been cross-compiled and inspected, but not executed on Android here.
Linux x86_64 is executed by the regression suite; Android compilation alone is
not a playback or quality test on a handset.
