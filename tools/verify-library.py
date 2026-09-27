#!/usr/bin/env python3
"""Linux release gate using pinned Kotlin/JUnit and the actual JNI production engine.

Requires Python 3.11+, JDK 17+, curl, C/C++ compilers, NumPy and SciPy. This gate does not
replace Android/iOS platform builds. Dependencies are cached with SHA-256 checks.
No Gradle daemon, Android SDK, network access during analysis, or audio downloads
are needed. The optional learned model is checked separately from synthetic tests.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


def release_version() -> str:
    match = re.search(r'^beatweaveVersion=(\S+)$',
                      (ROOT / "gradle.properties").read_text(), re.MULTILINE)
    if match is None:
        raise RuntimeError("Cannot determine beatweaveVersion from gradle.properties")
    return match.group(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / ".cache/verify")
    parser.add_argument("--output", type=Path, default=ROOT / "build/verification")
    parser.add_argument("--offline", action="store_true", help="Only use already cached, verified dependencies")
    parser.add_argument("--resource-cache-root", type=Path,
                        help="Local temporary filesystem for stress audio cache; defaults to the system temporary directory")
    args = parser.parse_args()
    os.environ["ORT_DISABLE_TELEMETRY"] = "1"
    if "JAVA_HOME" in os.environ:
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
        if not Path(java).is_file():
            parser.error("JAVA_HOME does not contain bin/java")
    else:
        java = shutil.which("java")
        if java is None:
            parser.error("Install JDK 17+ and set JAVA_HOME or add java to PATH")
    cache, output = args.cache.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    deps = cache / "deps"
    deps.mkdir(parents=True, exist_ok=True)
    log = (output / "verification.log").open("w", buffering=1)
    started = time.monotonic()
    summary = {"status": "failed", "version": release_version(),
               "platform": platform.platform(), "kotlin": "2.1.21", "gates": []}

    def source_hashes() -> dict[str, str]:
        paths = [p for module in ("analysis", "rubberband", "learned-beats")
                 for p in (ROOT / module / "src").rglob("*.kt")]
        paths += [p for p in (ROOT / "rubberband/native").rglob("*")
                  if p.is_file() and p.suffix in (".h", ".hpp", ".cpp", ".c")]
        paths += [p for p in (ROOT / "tools").iterdir()
                  if p.is_file() and p.suffix in (".kt", ".py", ".json", ".sh")]
        paths += [p for p in (ROOT / "learned-beats/tools").rglob("*")
                  if p.is_file() and p.suffix in (".py", ".sh", ".json")]
        paths += [ROOT / "test-data/beat-grids" / name
                  for name in ("track-1-beats.csv", "track-2-beats.csv")]
        paths += [ROOT / "build.gradle.kts", ROOT / "settings.gradle.kts", ROOT / "gradle.properties",
                  ROOT / "rubberband/native/CMakeLists.txt", ROOT / "learned-beats/tools/run_offline.c"]
        model_root = ROOT / "learned-beats/models"
        required_models = ("beat-this-small0.onnx", "beat-this-final0.onnx",
                           "provenance.json", "final0-provenance.json")
        for name in required_models:
            if not (model_root / name).is_file():
                raise RuntimeError(f"Required pinned model/provenance missing: {name}")
        paths += [p for p in model_root.rglob("*")
                  if p.is_file() and p.suffix in (".onnx", ".json")]
        paths += [ROOT / module / "build.gradle.kts"
                  for module in ("analysis", "rubberband", "learned-beats")]
        paths += [p for consumer in ("jvm", "android")
                  for p in (ROOT / "examples" / consumer).rglob("*")
                  if p.is_file() and p.suffix in (".kt", ".kts", ".py", ".properties", ".xml") and
                  not any(part in ("build", ".gradle", "__pycache__") for part in p.parts)]
        return {str(p.relative_to(ROOT)): sha256(p) for p in sorted(paths)}

    initial_sources = source_hashes()

    def say(message: str) -> None:
        print(message, flush=True)
        print(message, file=log, flush=True)

    def run(command: list[str], gate: str, timeout_seconds: float | None = None) -> None:
        say("RUN " + gate)
        process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)
        assert process.stdout is not None
        timer = threading.Timer(timeout_seconds, process.kill) if timeout_seconds is not None else None
        if timer is not None:
            timer.daemon = True
            timer.start()
        try:
            for line in process.stdout:
                print(line, end="", flush=True)
                log.write(line)
            return_code = process.wait()
        finally:
            if timer is not None:
                timer.cancel()
        if return_code != 0:
            raise RuntimeError(f"{gate} failed (exit {process.returncode}); see {output / 'verification.log'}")
        summary["gates"].append(gate)

    try:
        if sys.platform != "linux":
            raise RuntimeError("This executable gate currently targets Linux; use Gradle/CMake for other hosts.")
        for executable in ("curl", "g++", "cc"):
            if not shutil.which(executable):
                raise RuntimeError(f"Missing required executable: {executable}")
        run([sys.executable, "-c", "import numpy, scipy; print('NumPy', numpy.__version__, 'SciPy', scipy.__version__)"], "Python audio dependencies")
        run([java, "-XX:-UsePerfData", "-version"], "Java runtime")
        locked = json.loads((ROOT / "tools/verification-dependencies.json").read_text())["dependencies"]
        for dependency in locked:
            path = deps / dependency["file"]
            if path.exists() and sha256(path) == dependency["sha256"]:
                continue
            if args.offline:
                raise RuntimeError(f"Missing or invalid cached dependency: {path.name}")
            temporary = path.with_suffix(".download")
            try:
                run(["curl", "--fail", "--silent", "--show-error", "--location", "--retry", "2", "--max-time", "300",
                     dependency["url"], "--output", str(temporary)], "Fetch " + path.name)
                if sha256(temporary) != dependency["sha256"]:
                    raise RuntimeError(f"SHA-256 mismatch for {path.name}")
                temporary.replace(path)
            finally:
                temporary.unlink(missing_ok=True)
        say("Verified checksums of all pinned dependencies")
        compiler_cp = os.pathsep.join(str(deps / d["file"]) for d in locked)
        stdlib = deps / "kotlin-stdlib-2.1.21.jar"
        onnx = deps / "onnxruntime-1.22.0.jar"
        junit = [deps / name for name in ("kotlin-test-2.1.21.jar", "kotlin-test-junit-2.1.21.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar")]
        artifacts = output / "artifacts"
        artifacts.mkdir(exist_ok=True)

        def compile_sources(name: str, sources: list[Path], classpath: list[Path], friends: list[Path] | None = None) -> Path:
            if not sources:
                raise RuntimeError(f"No sources for {name}")
            jar = artifacts / f"{name}.jar"
            jar.unlink(missing_ok=True)
            command = [java, "-XX:-UsePerfData", "-Xmx2g", "-cp", compiler_cp,
                       "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                       "-jvm-target", "1.8", "-module-name", name, "-classpath",
                       os.pathsep.join(map(str, classpath)), "-d", str(jar)]
            if friends:
                command += ["-Xfriend-paths=" + ",".join(map(str, friends))]
            run(command + [str(p) for p in sources], "Compile " + name)
            return jar

        def sources(module: str, *sets: str) -> list[Path]:
            return sorted(p for source_set in sets for p in (ROOT / module / "src" / source_set).rglob("*.kt"))

        core = compile_sources("beatweave-analysis", sources("analysis", "commonMain", "jvmMain"), [stdlib])
        rubber = compile_sources("beatweave-rubberband", sources("rubberband", "commonMain", "jvmSharedMain", "jvmMain"), [stdlib, core])
        learned = compile_sources("beatweave-learned-beats", sources("learned-beats", "commonMain", "ortMain", "jvmMain"), [stdlib, core, onnx])
        libraries = [core, rubber, learned]
        tests = []
        test_jars = []
        # Match Gradle module boundaries: a test may access its own internals,
        # never another module's internals or an undeclared module dependency.
        for module, own_jar, dependencies in (
            ("analysis", core, [stdlib, core]),
            ("rubberband", rubber, [stdlib, core, rubber]),
            ("learned-beats", learned, [stdlib, core, learned, onnx]),
        ):
            module_tests = sources(module, "commonTest", "jvmTest")
            tests += module_tests
            test_jars.append(compile_sources("beatweave-" + module + "-tests", module_tests,
                                             [*dependencies, *junit], [own_jar]))
        classes = []
        for source in tests:
            text = source.read_text()
            if "@Test" not in text:
                continue
            package = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
            classes += [(package.group(1) + "." if package else "") + name
                        for name in re.findall(r"^(?:public\s+)?class\s+(\w+)", text, re.MULTILINE)]
        if not classes:
            raise RuntimeError("No JUnit test classes discovered")

        java_path = Path(java).resolve()
        java_home = Path(os.environ.get("JAVA_HOME", str(java_path.parents[1])))
        jni = Path(os.environ.get("BEATWEAVE_JNI_INCLUDE", str(java_home / "include")))
        if not (jni / "jni.h").exists() or not (jni / "linux/jni_md.h").exists():
            raise RuntimeError("Install JDK 17+ headers or set BEATWEAVE_JNI_INCLUDE to the directory containing jni.h and linux/jni_md.h")
        native = output / "native"
        native.mkdir(exist_ok=True)
        offline = native / "run-offline"
        run(["cc", "-O2", str(ROOT / "learned-beats/tools/run_offline.c"), "-o", str(offline)],
            "Build offline verification launcher")
        run(["g++", "-std=c++17", "-O3", "-DNDEBUG", "-fPIC", "-shared", "-pthread",
             "-I" + str(jni), "-I" + str(jni / "linux"),
             str(ROOT / "rubberband/native/beatweave_rubberband.cpp"),
             str(ROOT / "rubberband/native/beatweave_jni.cpp"),
             str(ROOT / "rubberband/native/vendor/rubberband/single/RubberBandSingle.cpp"),
             "-o", str(native / "libbeatweave_rubberband.so")], "Build Rubber Band R3 JNI")
        runtime = [stdlib, onnx, *libraries, *junit, *test_jars]
        run([str(offline), java, "-XX:-UsePerfData", "-Xmx2g", "-Djava.library.path=" + str(native), "-cp",
             os.pathsep.join(map(str, runtime)), "org.junit.runner.JUnitCore", *sorted(set(classes))], "JUnit common/JVM tests")

        harness_sources = [ROOT / "tools" / name for name in (
            "MixerContract.kt", "MixerRegression.kt", "DetectorRegression.kt",
            "PulseNormalizationRegression.kt", "WarpQualityContract.kt", "LibraryDemo.kt", "AutomixDemo.kt",
            "AutomaticPulseRegression.kt", "FullSongProfile.kt", "ResourceStress.kt",
            "MainBeatProjectionRegression.kt", "ModelVariantLifecycle.kt")]
        harness = compile_sources("beatweave-validation", harness_sources, [stdlib, onnx, *libraries])
        harness_cp = os.pathsep.join(map(str, [stdlib, onnx, *libraries, harness]))
        harness_java = [str(offline), java, "-XX:-UsePerfData", "-Xmx2g", "-Djava.library.path=" + str(native), "-cp", harness_cp]
        run([*harness_java, "MixerContractKt"], "Mixer API contract")
        run([*harness_java, "org.metrolist.beatweave.verification.MainBeatProjectionRegression"],
            "Declared main-beat planning and prepared render contract")
        run([*harness_java, "WarpQualityContractKt"], "Unsafe warp rejection contract")
        run([*harness_java, "PulseNormalizationRegressionKt"], "Pulse normalization contract")
        run([*harness_java, "AutomaticPulseRegressionKt"], "Automatic metrical pulse selection contract")
        fixtures = output / "audio-fixtures"
        if fixtures.exists():
            shutil.rmtree(fixtures)
        run([*harness_java, "MixerRegressionKt", str(fixtures)], "Render independent audio fixtures")
        run([sys.executable, str(ROOT / "tools/validate_audio.py"), "fixtures", str(fixtures),
             "--output", str(output / "audio-metrics.json")], "Independent rendered PCM gates")
        run([*harness_java, "DetectorRegressionKt"], "Detector independent fixtures")
        profile = output / "full-song-profile"
        if profile.exists():
            shutil.rmtree(profile)
        recorded = ROOT / "test-data/beat-grids"
        # Source impulses and output oracle remain the original observed grids.
        # The public bounded regularizer may only change the plan it prepares.
        profile_java = [str(offline), java, "-XX:-UsePerfData", "-Xmx256m", "-Djava.library.path=" + str(native),
                        "-cp", harness_cp]
        run([*profile_java, "FullSongProfileKt", str(profile),
             str(recorded / "track-1-beats.csv"), str(recorded / "track-2-beats.csv"),
             "--regularize"], "Render whole-song original-grid oracle through public regularizer")
        run([sys.executable, str(ROOT / "tools/validate_audio.py"), "fixtures", str(profile),
             "--output", str(output / "full-song-metrics.json")], "Whole-song transient and rolling pitch gates")
        stress = output / "resource-stress"
        if stress.exists():
            shutil.rmtree(stress)
        cache_root = args.resource_cache_root.resolve() if args.resource_cache_root is not None else None
        stress_cache = Path(tempfile.mkdtemp(prefix="beatweave-resource-", dir=cache_root))
        summary["resource_stress_cache_location"] = str(stress_cache)
        stress_java = [str(offline), java, "-XX:-UsePerfData", "-Xmx256m", "-XX:+UseSerialGC",
                       "-Dbeatweave.stress.cache=" + str(stress_cache),
                       "-Djava.library.path=" + str(native), "-cp", harness_cp]
        run([*stress_java, "ResourceStressKt", str(stress),
             str(ROOT / "learned-beats/models/beat-this-small0.onnx"), "48"],
            "Repeated playlist lifecycle and model reuse resource gates", timeout_seconds=600)
        stress_report = stress / "resource-stress.json"
        measured = json.loads(stress_report.read_text())
        if measured.get("status") != "passed" or measured.get("cycles") != 48:
            raise RuntimeError("Incomplete resource stress report")
        shutil.copy2(stress_report, output / "resource-stress.json")
        if sorted(path.name for path in stress_cache.iterdir()) != ["caller-owned.txt"]:
            raise RuntimeError("Unexpected resource cache files remain after the stress JVM exits")
        # The harness preserves the caller's sentinel; this wrapper owns the fresh
        # temporary directory and removes it only after all resource gates pass.
        shutil.rmtree(stress_cache)
        variant_output = output / "model-variant-lifecycle"
        if variant_output.exists():
            shutil.rmtree(variant_output)
        run([str(offline), java, "-XX:-UsePerfData", "-Xmx256m", "-XX:+UseSerialGC",
             "-cp", harness_cp, "ModelVariantLifecycleKt", str(variant_output),
             str(ROOT / "learned-beats/models/beat-this-small0.onnx"),
             str(ROOT / "learned-beats/models/beat-this-final0.onnx")],
            "Both pinned model variants: warmup and repeated session lifecycle", timeout_seconds=180)
        variant_report = variant_output / "model-variant-lifecycle.json"
        variant_measured = json.loads(variant_report.read_text())
        if (variant_measured.get("status") != "passed" or
                variant_measured.get("sessions_opened_and_closed") != 8 or
                variant_measured.get("final0_1500_frame_warmup_calls") != 1):
            raise RuntimeError("Incomplete model-variant lifecycle report")
        shutil.copy2(variant_report, output / "model-variant-lifecycle.json")
        if source_hashes() != initial_sources:
            raise RuntimeError("Source files changed while verification ran; rerun to validate one consistent revision")
        summary.update(status="passed", jars={p.name: sha256(p) for p in libraries},
                       native_sha256=sha256(native / "libbeatweave_rubberband.so"),
                       test_classes=sorted(set(classes)), source_sha256=initial_sources,
                       inference_ipv4_ipv6_sockets="denied by inherited Linux seccomp filter",
                       whole_profile_java_heap_limit_mib=256,
                       resource_stress_java_heap_limit_mib=256,
                       resource_stress_cycles=48,
                       model_variant_lifecycle_report_sha256=sha256(output / "model-variant-lifecycle.json"),
                       resource_stress_report_sha256=sha256(output / "resource-stress.json"))
        say("PASS: common/JVM tests, contracts, rendered audio and resource gates. Android/iOS/device execution is outside this gate.")
        return 0
    except Exception as error:
        summary["error"] = str(error)
        say("FAIL: " + str(error))
        return 1
    finally:
        summary["elapsed_seconds"] = round(time.monotonic() - started, 3)
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        log.close()


if __name__ == "__main__":
    raise SystemExit(main())
