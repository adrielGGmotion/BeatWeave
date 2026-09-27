# JVM consumer

This standalone project resolves BeatWeave from the local Maven repository in
`dist/maven`. It checks the published JARs, analyzes generated audio with both
model variants, and exercises planning, native rendering, seeking and cleanup.
It does not measure beat detection accuracy on music.

From the repository root, publish the libraries using the commands in
[Building and testing](../../docs/build.md), then run:

```sh
python3 tools/verify-publications.py --platform jvm
```

The verification script requires both [model files](../../docs/models.md), builds
this project, and runs it with networking disabled. Reports go to
`build/verification/publications`. No separate host verification run is required.

For a model-free bundled-native check, run from the repository root:

```sh
./gradlew -p examples/jvm checkPublishedJars nativeSmoke
```

For a separate consumer build, use `../../gradlew` from this directory.
`beatweaveRoot` defaults to `../..`; `beatweaveVersion` defaults to `0.10.0`.
`beatweaveGroup` defaults to `io.github.adrielggmotion.beatweave`. Pass matching
overrides when publishing under different coordinates. The JVM JAR bundles the Linux
x86_64 (glibc) native library; no external native path is needed.
