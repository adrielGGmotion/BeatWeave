#!/usr/bin/env python3
"""Inspect a built downstream Android release. This is not a runtime test."""
import argparse
import hashlib
import json
import re
import struct
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--repository', required=True, type=Path)
parser.add_argument('--version', required=True)
parser.add_argument('--group', default='io.github.adrielggmotion.beatweave')
parser.add_argument('--model-provenance', required=True, type=Path)
parser.add_argument('--model-asset', required=True, choices=('beat-this-small0.onnx', 'beat-this-final0.onnx'))
parser.add_argument('--aapt2', required=True, type=Path)
parser.add_argument('--zipalign', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
expected_model_sha256 = json.loads(args.model_provenance.read_text())['onnx_sha256']
assert re.fullmatch(r'[0-9a-f]{64}', expected_model_sha256), 'Invalid pinned model SHA-256'
root = Path(__file__).resolve().parent
build = root / 'build'
sha = lambda data: hashlib.sha256(data).hexdigest()
report = {'version': args.version, 'kind': 'downstream Android build and packaging verification',
          'android_runtime_executed': False, 'checks': [], 'artifacts': [], 'native_libraries': [], 'published_aars': []}
config = (build / 'outputs/mapping/release/configuration.txt').read_text()
assert '-keep class ai.onnxruntime.** { *; }' in config, 'Missing transitive ORT JNI keep rule'
assert '-keep class org.metrolist.beatweave.rubberband.RubberBandBridge { *; }' in config
mapping = (build / 'outputs/mapping/release/mapping.txt').read_text()
ort_classes = re.findall(r'^(ai\.onnxruntime\.[^ ]+) -> ([^:]+):$', mapping, re.M)
# R8-created lambda implementations have no original JNI identity and may be renamed.
ort_classes = [(a, b) for a, b in ort_classes if '$$ExternalSynthetic' not in a]
assert ort_classes and all(a == b for a, b in ort_classes), 'R8 changed an ORT JNI class name'
for name in ['ai.onnxruntime.TensorInfo', 'ai.onnxruntime.NodeInfo', 'ai.onnxruntime.OrtException']:
    assert (name, name) in ort_classes, 'R8 removed JNI-reflected class: ' + name
report['checks'].append('R8 preserves all %s ORT classes, including non-native JNI lookup targets' % len(ort_classes))
report['checks'].append('Library AAR consumer rules preserve RubberBandBridge JNI names')
resolved = {}
for line in (build / 'resolved-artifacts.tsv').read_text().splitlines():
    name, file = line.split('\t')
    resolved[name] = Path(file)
for artifact in ['analysis-android', 'rubberband-android', 'learned-beats-android']:
    name = f'{args.group}:{artifact}:{args.version}'
    published = args.repository / args.group.replace('.', '/') / artifact / args.version / f'{artifact}-{args.version}.aar'
    assert sha(resolved[name].read_bytes()) == sha(published.read_bytes()), 'Stale or wrong Maven artifact: ' + name
    report['published_aars'].append({'path': str(published.relative_to(args.repository)), 'sha256': sha(published.read_bytes())})
assert 'com.microsoft.onnxruntime:onnxruntime-android:1.22.0' in resolved
report['checks'].append('All three Android variants match published AAR SHA-256 values; ORT resolves transitively')
apk = build / 'outputs/apk/release/BeatWeaveAndroidConsumer-release.apk'
with zipfile.ZipFile(apk) as z:
    model_assets = [name for name in z.namelist() if name.startswith('assets/') and name.endswith('.onnx')]
    assert model_assets == ['assets/' + args.model_asset], 'Missing, renamed or duplicate model asset'
    assert sha(z.read('assets/' + args.model_asset)) == expected_model_sha256
    report['model_asset'] = args.model_asset
    report['model_sha256'] = expected_model_sha256
    native_names = [n for n in z.namelist() if n.endswith('.so')]
    expected_names = [f'lib/{abi}/{lib}' for abi in ['arm64-v8a','armeabi-v7a','x86_64']
                      for lib in ['libbeatweave_rubberband.so','libonnxruntime.so','libonnxruntime4j_jni.so']]
    assert sorted(native_names) == sorted(expected_names)
    native_sources = {}
    for dep in [args.group + ':rubberband-android:' + args.version,
                'com.microsoft.onnxruntime:onnxruntime-android:1.22.0']:
        with zipfile.ZipFile(resolved[dep]) as aar:
            for name in aar.namelist():
                if name.startswith('jni/') and name.endswith('.so'):
                    native_sources['lib/' + name[4:]] = sha(aar.read(name))
    for name in native_names:
        data = z.read(name)
        assert sha(data) == native_sources[name], 'APK native payload differs from published dependency'
        endian = '<' if data[5] == 1 else '>'
        bits = data[4]
        if bits == 2:
            phoff = struct.unpack_from(endian+'Q', data, 32)[0]
            size, count = struct.unpack_from(endian+'HH', data, 54)
            rows = [struct.unpack_from(endian+'IIQQQQQQ', data, phoff+i*size) for i in range(count)]
        else:
            phoff = struct.unpack_from(endian+'I', data, 28)[0]
            size, count = struct.unpack_from(endian+'HH', data, 42)
            rows = [struct.unpack_from(endian+'IIIIIIII', data, phoff+i*size) for i in range(count)]
        align = [r[-1] for r in rows if r[0] == 1]
        if bits == 2:
            assert align and min(align) >= 16384, '64-bit ELF is not 16 KiB aligned'
        report['native_libraries'].append({'path': name, 'sha256': sha(data), 'elf_load_alignment': align})
report['checks'].append('APK includes exact local model and all three native libraries for arm64-v8a, armeabi-v7a, x86_64')
report['checks'].append('All 64-bit native ELF segments have at least 16 KiB alignment')
subprocess.run([str(args.zipalign), '-c', '-P', '16', '4', str(apk)], check=True, capture_output=True)
report['checks'].append('Release APK passes zipalign -c -P 16 4')
badging = subprocess.run([str(args.aapt2), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
assert 'android.permission.INTERNET' not in badging
assert "minSdkVersion:'26'" in badging
report['checks'].append('Packaged manifest has minSdk 26 and requests no Internet permission')
for file in sorted((build / 'outputs/apk').rglob('*.apk')):
    report['artifacts'].append({'path': str(file.relative_to(root)), 'bytes': file.stat().st_size,
                                'sha256': sha(file.read_bytes())})
report['result'] = 'PASS'
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + '\n')
print('PASS: downstream Android packaging (device execution not performed)')
