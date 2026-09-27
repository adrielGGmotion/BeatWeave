# Android consumer

This standalone app checks that published BeatWeave AARs resolve and survive an
R8 release build. It contains a small smoke test, not the transition editor.

Publish the libraries using the commands in
[Building and testing](../../docs/build.md), then run from the repository root:

```sh
python3 tools/verify-publications.py --platform android
```

The script packages the final0 model, checks transitive JNI keep rules, compares
the packaged libraries with the published AARs, and verifies 16 KiB alignment.
Reports go to `build/verification/publications`.

This command does not launch a device or emulator. A passing report verifies the
build and package, not Android runtime behavior. The release APK uses the debug
signing key and is intended for testing.
