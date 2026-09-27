#!/usr/bin/env python3
"""Verify already-published JVM/Android artifacts through independent consumers.

Builds run serially to avoid Gradle-cache contention. JVM inference runs with IP
sockets denied and a new owned cache directory. Android verification is compile,
R8 and package inspection only: this script never starts a device or emulator.
Publish dist/maven and install both models before invoking this script.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    properties = dict(line.split('=', 1) for line in (ROOT/'gradle.properties').read_text().splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    parser.add_argument('--group', default=properties['beatweaveGroup'])
    parser.add_argument('--version', default=properties['beatweaveVersion'])
    parser.add_argument('--platform', choices=('jvm', 'android', 'both'), default='both')
    parser.add_argument('--gradle', type=Path, default=ROOT / 'gradlew')
    parser.add_argument('--gradle-argument', action='append', default=[])
    parser.add_argument('--offline', action='store_true', help='Use Gradle dependencies already cached')
    parser.add_argument('--android-build-tools', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/verification/publications')
    args = parser.parse_args()
    version = args.version
    group_path = args.group.replace('.', '/')
    java = Path(os.environ['JAVA_HOME'])/'bin/java' if 'JAVA_HOME' in os.environ else Path(shutil.which('java') or '')
    if not java.is_file():
        raise SystemExit('JDK required: set JAVA_HOME')
    repository = ROOT/'dist/maven'
    models = [
        ('small0', ROOT/'learned-beats/models/beat-this-small0.onnx', ROOT/'learned-beats/models/provenance.json'),
        ('final0', ROOT/'learned-beats/models/beat-this-final0.onnx', ROOT/'learned-beats/models/final0-provenance.json'),
    ]
    for variant, model_file, provenance in models:
        if digest(model_file) != json.loads(provenance.read_text())['onnx_sha256']:
            raise RuntimeError(f'Bundled {variant} model does not match pinned provenance')
    reports = args.output.resolve()
    reports.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, ORT_DISABLE_TELEMETRY='1')
    environment['JAVA_OPTS'] = environment.get('JAVA_OPTS', '') + ' -XX:-UsePerfData'
    gradle = [str(args.gradle.resolve()), '--no-daemon', '--max-workers=2', '--console=plain']
    if args.offline:
        gradle.append('--offline')
    gradle += args.gradle_argument

    def run(command: list[str], cwd: Path, log: Path) -> None:
        print('RUN ' + log.name, flush=True)
        with log.open('w') as stream:
            result = subprocess.run(command, cwd=cwd, env=environment, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f'{log.name} failed with exit {result.returncode}; inspect {log}')

    if args.platform in ('jvm', 'both'):
        project = ROOT/'examples/jvm'
        launcher = reports/'run-offline'
        run(['cc', '-O2', str(ROOT/'learned-beats/tools/run_offline.c'), '-o', str(launcher)],
            ROOT, reports/'offline-launcher-build.log')
        build_log = reports/'published-jvm-consumer-build.log'
        run([*gradle, '-PbeatweaveRoot='+str(ROOT), '-PbeatweaveVersion='+version,
             '-PbeatweaveGroup='+args.group,
             'checkPublishedJars', 'installDist'], project, build_log)
        libraries = project/'build/install/beatweave-jvm-published-consumer/lib'
        jars = sorted(libraries.glob('*.jar'))
        for module in ('analysis', 'rubberband', 'learned-beats'):
            name = f'{module}-jvm-{version}.jar'
            expected = repository/group_path/f'{module}-jvm'/version/name
            if digest(libraries/name) != digest(expected):
                raise RuntimeError('JVM consumer resolved a different artifact: '+name)
        runtime_log = reports/'published-jvm-consumer-run.log'
        runtime_log.unlink(missing_ok=True)
        started = time.monotonic()
        with zipfile.ZipFile(libraries/f'rubberband-jvm-{version}.jar') as archive:
            native_digest = hashlib.sha256(archive.read(
                'META-INF/native/linux-x86_64/libbeatweave_rubberband.so')).hexdigest()
        report = {'version': version, 'result': 'FAIL', 'kind': 'Independent published JVM consumer',
                  'network_during_runtime': 'IPv4 and IPv6 sockets denied by inherited Linux seccomp filter',
                  'android_runtime_executed': False,
                  'resolved_jars': [{'name': p.name, 'sha256': digest(p)} for p in jars],
                  'model_runs': [],
                  'native_sha256': native_digest,
                  'log': runtime_log.name}
        try:
            for variant, model, provenance in models:
                # Native preparation is transient I/O; avoid synchronized source trees.
                cache = Path(tempfile.mkdtemp(prefix=f'published-jvm-{variant}-cache-'))
                variant_log = reports/f'published-jvm-consumer-{variant}-run.log'
                model_run = {'variant': variant, 'model_asset': model.name,
                             'model_sha256': digest(model), 'provenance_sha256': digest(provenance),
                             'result': 'FAIL', 'log': variant_log.name}
                report['model_runs'].append(model_run)
                model_start = time.monotonic()
                try:
                    run([str(launcher), str(java), '-XX:-UsePerfData', '-Xmx256m',
                         '-Djava.library.path='+str(cache/'empty-library-path'), '-Dbeatweave.version='+version,
                         '-cp', os.pathsep.join(map(str,jars)),
                         'ConsumeKt', str(model), str(cache)], ROOT, variant_log)
                    remaining = [p.name for p in cache.iterdir()]
                    model_run['remaining_owned_cache_files'] = remaining
                    if remaining:
                        raise RuntimeError('Owned native cache remains after JVM exit: '+repr(remaining))
                    model_run.update(result='PASS', owned_cache_empty_after_process_exit=True)
                    cache.rmdir()
                finally:
                    model_run['elapsed_seconds'] = round(time.monotonic()-model_start, 3)
                    if variant_log.exists():
                        with runtime_log.open('a' if len(report['model_runs']) > 1 else 'w') as stream:
                            stream.write(f'VARIANT {variant} SHA256 {digest(model)}\n')
                            stream.write(variant_log.read_text())
            report.update(result='PASS', owned_cache_empty_after_process_exit=True)

        finally:
            report['elapsed_seconds'] = round(time.monotonic()-started, 3)
            (reports/'published-jvm-consumer-result.json').write_text(json.dumps(report, indent=2)+'\n')
        print('PASS both published JVM model variants, automatic plans, native prepare/read/render and cleanup', flush=True)

    if args.platform in ('android', 'both'):
        project = ROOT/'examples/android'
        _, model_file, provenance = models[1]
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        build_tools = args.android_build_tools or (Path(sdk)/'build-tools/35.0.0' if sdk else None)
        if build_tools is None or not (build_tools/'aapt2').is_file() or not (build_tools/'zipalign').is_file():
            raise RuntimeError('Set ANDROID_HOME with build-tools35.0.0 or --android-build-tools')
        run([*gradle, '-PbeatweaveRepository='+str(repository), '-PbeatweaveVersion='+version,
             '-PbeatweaveModel='+str(model_file), '-PbeatweaveGroup='+args.group,
             'clean', 'assembleRelease', 'recordResolvedArtifacts'], project, reports/'android-consumer-build.log')
        run(['python3', str(project/'verify_packaging.py'), '--repository', str(repository),
             '--version', version, '--group', args.group, '--model-provenance', str(provenance), '--model-asset', model_file.name, '--aapt2', str(build_tools/'aapt2'), '--zipalign', str(build_tools/'zipalign'),
             '--output', str(reports/'android-consumer-result.json')], ROOT, reports/'android-consumer-packaging.log')
        report = json.loads((reports/'android-consumer-result.json').read_text())
        if report['result'] != 'PASS' or report['version'] != version or report['android_runtime_executed']:
            raise RuntimeError('Unexpected Android packaging result or runtime claim')
        print('PASS clean optimized Android consumer build/package; Android runtime NOT executed', flush=True)


if __name__ == '__main__':
    main()
