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

Android builds use NDK r27c (27.2.12479018), API 26 or newer, a static C++
runtime, and 16 KiB ELF segment alignment. The AAR targets arm64-v8a,
armeabi-v7a, and x86_64. Gradle builds the native engine from this source;
precompiled libraries are not stored in the repository. The JVM publication
includes a Linux x86_64 native library built on the release host. Its source JARs
include the complete native source tree, CMake files, COPYING, and this notice.

The 0.10.0 validation covered Linux x86_64 execution and Android compilation
and packaging. Android device execution and iOS integration remain unverified.
