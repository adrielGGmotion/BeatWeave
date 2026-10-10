# Publishing to Maven Central

BeatWeave 0.10.0 is published to Maven Central under
`io.github.adrielggmotion.beatweave`. Consumers can use `mavenCentral()` as shown
in the [README](../README.md#getting-started). This version is immutable; future
releases must use a new version.

The verified Central namespace is `io.github.adrielggmotion`. The public source
repository is [github.com/adrielGGmotion/BeatWeave](https://github.com/adrielGGmotion/BeatWeave)
and its URL is configured in `gradle.properties`. Future Central deployments
require maintainer review and explicit approval before publication.

## Release contents

The default build produces nine Maven publications: KMP metadata, Android, and
JVM variants of each module. They include POM metadata, Kotlin sources, and a
`javadoc` JAR containing the usage/build documentation rather than generated API
reference pages. Rubber Band source JARs also contain the native source tree,
CMake build files, and GPL notices.

Android native libraries cover arm64-v8a, armeabi-v7a, and x86_64. The JVM JAR
bundles a Linux x86_64 library and extracts it to a private temporary file when
no library is available through `java.library.path`. The temporary directory
must permit loading native code. Windows, macOS, musl Linux, and iOS adapters
are not supplied or tested by this release setup.

Build releases on Linux x86_64 with JDK 17 and the [pinned toolchain](build.md).
Use Ubuntu 24.04, as CI does, to keep the bundled binary's glibc baseline
consistent. Local native builds inherit their host's system-library requirements.
Apple targets remain a separate experimental port and are rejected by the
Central release configuration.

Models are intentionally separate from Maven artifacts. With both verified
models installed, `./gradlew modelDistribution` creates
`dist/BeatWeave-models-<version>.zip`, including licenses and provenance. Attach
that archive to the matching public repository release before announcing Maven
coordinates. See [model setup](models.md).

The model-source release workflow runs after a successful default-branch Build.
For a version without an existing GitHub release, it verifies the unchanged model
weights, runs inference tests, packages models and creates a source/model
prerelease with a SHA-256 sidecar. It requires version-specific notes under
`docs/releases/`. It does not publish Maven Central or access signing credentials.

## Account and metadata

1. Use a [Central Portal account](https://central.sonatype.com/) with access to
   the verified `io.github.adrielggmotion` namespace.
2. Create a Portal user token. Its username/password are publishing credentials,
   not your website login.
3. Create a GPG signing key and publish its public key following
   [Central's signing requirements](https://central.sonatype.org/publish/requirements/gpg/).
4. The public source repository, including GPL native sources, is
   `https://github.com/adrielGGmotion/BeatWeave`; `beatweaveRepositoryUrl` is
   already configured to this URL.
5. The release group and version are configured in `gradle.properties` as
   `io.github.adrielggmotion.beatweave` and `0.11.0`. Override them with
   `-PbeatweaveGroup=... -PbeatweaveVersion=...` only when needed. Project
   properties take precedence over environment variables. Versions already
   released on Central are immutable.

Keep secrets out of Git. The owner-only, ignored repository-root `.env` uses
`MAVEN_USER` and `MAVEN_PASSWORD`, but Gradle does not load `.env`. After loading
those values securely into your shell or CI environment, the operator must map
them to Gradle's expected names:

```sh
export ORG_GRADLE_PROJECT_mavenCentralUsername="$MAVEN_USER"
export ORG_GRADLE_PROJECT_mavenCentralPassword="$MAVEN_PASSWORD"
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys YOUR_SIGNING_KEY_FINGERPRINT)"
```

For a password-protected key, also set
`ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` securely. Do not paste private
keys or tokens into issues, chat, project properties, or build logs. The POM
credits BeatWeave contributors; its source URL and SCM connection are derived
from your repository URL. Changing the Maven group does not rename Kotlin packages.

The release signing [public key](signing-key.asc) has fingerprint
`6D0D3A6E722C50733CAB68EC7CF38B029A9B9542`. Keep the private key, its passphrase,
and revocation certificate backed up outside the repository.

## Check locally first

Ordinary builds need no credentials. From a clean staging directory, run:

```sh
./gradlew --no-daemon --max-workers=2 \
  :analysis:jvmTest :learned-beats:jvmTest :rubberband:jvmTest \
  publishAllPublicationsToLocalRepository
python3 tools/check-release.py
./gradlew --no-daemon --max-workers=2 -p examples/jvm checkPublishedJars nativeSmoke
```

`nativeSmoke` resolves the published artifacts, disables the external native
library path, and exercises planning/rendering without models. CI runs these
checks as well. If overriding coordinates, also pass matching `--group` and
`--version` to the Python checker and `-PbeatweaveGroup`/`-PbeatweaveVersion` to
the example build.

With both models installed, also run:

```sh
./gradlew --no-daemon --max-workers=2 :learned-beats:modelIntegrationTest modelDistribution
```

The fuller [publication verifier](../tools/README.md) additionally runs both model
variants in a network-denied JVM and inspects an optimized Android consumer APK.
Neither Android device execution nor musical accuracy follows from these checks.
Keep the development/accuracy warning in the release notes.

## Sign, stage, then upload

`centralRelease=true` enables signing and Central tasks. It fails early without
`beatweaveRepositoryUrl`. Ordinary local publication remains unsigned.

Download and extract `linux-x86_64-native-ubuntu-24.04` from the CI run for
this release commit. Pass `-PbeatweaveNativeLibraryPath=/path/to/native` (the
directory containing `libbeatweave_rubberband.so`) to both Gradle commands below
so signing/staging and Central upload use that same binary.

First stage signed artifacts locally; this command does **not** upload anything:

```sh
./gradlew --no-daemon --max-workers=2 -PcentralRelease=true \
  -PbeatweaveNativeLibraryPath=/path/to/native \
  publishAllPublicationsToLocalRepository
python3 tools/check-release.py --central --signed \
  --native-library=/path/to/native/libbeatweave_rubberband.so
```

Inspect the POMs, verify the detached signatures with your public key, and rerun
consumers against these exact coordinates. Only then upload:

```sh
./gradlew --no-daemon --max-workers=2 -PcentralRelease=true \
  -PbeatweaveNativeLibraryPath=/path/to/native publishToMavenCentral
```

For a release version, this uploads a deployment for manual publication. Review
its validation in the Central Portal and click **Publish** yourself. Do not use
`publishAndReleaseToMavenCentral` unless you intend immediate public release.
Snapshot versions have different Portal behavior and are not this release flow.

After publishing a new version, verify that its artifacts are publicly available
from Maven Central before announcing it. Consumers use `mavenCentral()` with the
released coordinates; local repositories are only needed for development builds.
No automatic Central publishing workflow or Central credentials are stored in this repository.
