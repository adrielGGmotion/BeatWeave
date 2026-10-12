# Building

The default build targets Android and JVM. It needs JDK 17, the Android SDK,
a C++ compiler, and CMake. Use the included Gradle wrapper.

| Dependency | Version |
| --- | --- |
| Gradle | 8.10.2 |
| Kotlin | 2.1.21 |
| Android Gradle plugin | 8.7.3 |
| Android SDK | 35 |
| Android build tools | 34.0.0 |
| Android NDK | 27.2.12479018 |
| Android CMake | 3.22.1 |

Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your SDK directory. Install the
SDK packages with Android Studio or its command-line tools:

```sh
sdkmanager "platforms;android-35" "build-tools;34.0.0" \
  "ndk;27.2.12479018" "cmake;3.22.1"
```

On Linux, install a C++ toolchain such as `build-essential`. JVM native tests
use CMake from `PATH`. To use the SDK's copy instead, pass
`-PbeatweaveCmake="$ANDROID_HOME/cmake/3.22.1/bin/cmake"` to Gradle.
The Rubber Band source is included; native binaries are built locally.

## Tests

Run the tests that do not need model files:

```sh
./gradlew --no-daemon --max-workers=2 \
  :analysis:jvmTest :learned-beats:jvmTest :rubberband:jvmTest
```

These tasks run the model-independent suite, including native rendering and
pitch-analysis/shifting tests. Four ONNX
inference tests are a separate task because model files are distributed
separately from the source. After [setting up both models](models.md), run:

```sh
python3 tools/check-models.py
./gradlew --no-daemon --max-workers=2 :learned-beats:modelIntegrationTest
```

The integration task fails when either model is missing. It is not part of the
default `check` task. Reports are written under each module's
`build/reports/tests/` directory.

GitHub Actions runs the model-independent tests and builds the Android/JVM
publications. It does not run model inference, musical accuracy benchmarks,
Android devices, or emulators. A successful CI build therefore establishes
build and regression-test results, not automatic beat or bar accuracy.

## Local publications

```sh
./gradlew --no-daemon --max-workers=2 publishAllPublicationsToLocalRepository
```

This builds all three modules and writes their Android/JVM artifacts to
`dist/maven`. Nothing is uploaded. Add that directory as a Maven repository in
a consuming project:

```kotlin
repositories {
    maven { url = uri("/absolute/path/to/BeatWeave/dist/maven") }
    google()
    mavenCentral()
}
```

The Android Rubber Band AAR contains libraries for `arm64-v8a`, `armeabi-v7a`,
and `x86_64`. The JVM JAR bundles its Linux x86_64 native library. Publication
therefore requires a Linux x86_64 build host. JVM consumers load that library
automatically; no separate native build or `java.library.path` is needed. The JVM
loader unlinks its temporary extracted copy immediately after Linux loads it,
with process-exit deletion retained only as a fallback if the filesystem refuses.
For development, `-PbeatweaveNativeLibraryPath=/path/to/native` still selects a
prebuilt library for module tests. A JVM `java.library.path` override takes
precedence over the bundled resource.

Verify the staged publications and the model-free standalone JVM consumer:

```sh
python3 tools/check-release.py
./gradlew --no-daemon --max-workers=2 -p examples/jvm checkPublishedJars nativeSmoke
```

The checker validates POMs, source/documentation JARs, GPL source inclusion,
and native payloads. The consumer resolves Maven artifacts rather than project
source, forces bundled native loading, and exercises planning and rendering.
If using different coordinates, pass matching group/version overrides described
in [Publishing](publishing.md). That guide also covers signed local staging,
manual Central upload, and the separate model release archive.

## Apple targets

`-PenableAppleTargets=true` adds `iosArm64` and `iosSimulatorArm64` to the
`analysis` and `learned-beats` modules. These declarations are retained for
porting work. They have not been validated, and the repository does not provide
an iOS ONNX Runtime adapter or pitch-preserving rendering backend. Apple
compilation and publication need a compatible macOS host and Xcode.
