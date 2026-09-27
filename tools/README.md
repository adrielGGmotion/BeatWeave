# Verification tools

Run commands from the repository root. Toolchain requirements and publication
commands are in [Building and testing](../docs/build.md).

The full host verifier needs Linux, Python 3.11+, JDK 17 with JNI headers, `curl`,
`cc`, and `g++`. Install [both model files](../docs/models.md), then prepare the
Python dependencies:

```sh
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r tools/requirements-validation.txt
```

The host verifier runs all 143 tests, including model inference. It does not need
Gradle or the Android SDK.

| Command | Purpose |
| --- | --- |
| `./tools/verify-library.sh` | Compile the modules and run JVM tests, native audio checks, and model lifecycle checks on Linux. |
| `python3 tools/check-release.py` | Check all nine local publications, POMs, sources, documentation, and native payloads without models or credentials. |
| `python3 tools/verify-publications.py` | Check the local Maven artifacts through the standalone JVM and Android consumers. |

The host verifier downloads checksum-pinned dependencies to `.cache/verify`.
Use `--offline` after the first run, `--cache PATH` to change the dependency
cache, and `--output PATH` to change the report directory. The default output is
`build/verification`. Temporary render caches use the system temporary directory;
`--resource-cache-root PATH` overrides that location for the resource stress test.

The full `verify-publications.py` checks require `dist/maven`, both model files,
and a Linux C compiler for the network-denying JVM launcher; the complete host
gate is not a prerequisite. Use `--platform jvm`, `--platform android`, or the
default `both`. Reports go to `build/verification/publications`. Both publication
checkers accept `--group` and `--version` overrides for release coordinates.
`check-release.py --central --signed` also requires public SCM metadata and
detached signatures; it does not cryptographically verify the signatures.
Use GPG to verify them before upload.

For a model-free bundled-native runtime check, run:

```sh
./gradlew --no-daemon --max-workers=2 -p examples/jvm checkPublishedJars nativeSmoke
```

The JVM consumer no longer needs an external native library path. The fuller
verifier builds the existing seccomp launcher to deny runtime networking.

The Android publication checker also needs SDK build tools 35.0.0 for its
`aapt2` and `zipalign` checks, in addition to the tools used by the Gradle build:

```sh
sdkmanager "build-tools;35.0.0"
```

Set `ANDROID_HOME`, or pass
`--android-build-tools /path/to/sdk/build-tools/35.0.0` to the checker.

The Kotlin harnesses generate independent audio fixtures and exercise planning,
pitch preservation, timing, seeking, cancellation, and resource cleanup.
`validate_audio.py` measures the rendered fixtures. These checks do not establish
beat or bar detection accuracy on arbitrary music. `LibraryDemo.kt` and
`AutomixDemo.kt` are command-line usage examples compiled by the host verifier.

JVM verification uses a Linux seccomp wrapper that denies IPv4 and IPv6 sockets.
Android publication checks build and inspect an APK without launching it.
