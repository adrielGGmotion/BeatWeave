#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-or-later
set -euo pipefail
native_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
out_dir="${1:-$native_dir/../build/hostNative}"
mkdir -p "$out_dir"
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    abi="${2:-arm64-v8a}"
    toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
    case "$abi" in
      arm64-v8a) compiler="$toolchain/bin/aarch64-linux-android26-clang++" ;;
      x86_64) compiler="$toolchain/bin/x86_64-linux-android26-clang++" ;;
      armeabi-v7a) compiler="$toolchain/bin/armv7a-linux-androideabi26-clang++" ;;
      *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
    esac
    extra=(-static-libstdc++ -Wl,-z,max-page-size=16384)
else
    compiler="${CXX:-g++}"
    jni_dir="${BEATWEAVE_JNI_INCLUDE:-${JAVA_HOME:?Set JAVA_HOME to a JDK with JNI headers}/include}"
    extra=(-I"$jni_dir" -I"$jni_dir/linux")
fi
"$compiler" -std=c++17 -O3 -DNDEBUG -fPIC -shared "${extra[@]}" \
    "$native_dir/beatweave_rubberband.cpp" "$native_dir/beatweave_jni.cpp" \
    "$native_dir/vendor/rubberband/single/RubberBandSingle.cpp" \
    -o "$out_dir/libbeatweave_rubberband.so"
