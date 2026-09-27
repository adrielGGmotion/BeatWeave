#!/usr/bin/env python3
"""Check staged Maven publications without credentials or model files."""
import argparse
import hashlib
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
properties = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines()
                  if '=' in line and not line.lstrip().startswith('#'))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repository', type=Path, default=ROOT / 'dist/maven')
parser.add_argument('--group', default=properties['beatweaveGroup'])
parser.add_argument('--version', default=properties['beatweaveVersion'])
parser.add_argument('--central', action='store_true', help='Require public repository metadata')
parser.add_argument('--signed', action='store_true', help='Require detached signatures for every artifact')
parser.add_argument('--native-library', type=Path, help='Require this exact bundled JVM native library')
args = parser.parse_args()
namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}

for module in ('analysis', 'learned-beats', 'rubberband'):
    for target in ('', '-jvm', '-android'):
        artifact = module + target
        directory = args.repository / args.group.replace('.', '/') / artifact / args.version
        stem = f'{artifact}-{args.version}'
        pom = ET.parse(directory / f'{stem}.pom').getroot()

        def text(path):
            return pom.findtext('/'.join('m:' + part for part in path.split('/')), namespaces=namespace)

        assert text('groupId') == args.group, artifact
        assert text('artifactId') == artifact, artifact
        assert text('version') == args.version, artifact
        for field in ('name', 'description', 'licenses/license/url', 'developers/developer/name'):
            assert text(field), f'{artifact}: missing POM {field}'
        license_name = 'GPL-2.0-or-later' if module == 'rubberband' else 'MIT'
        assert text('licenses/license/name') == license_name, artifact
        if args.central:
            assert (text('url') or '').startswith('https://'), f'{artifact}: missing public URL'
            assert text('scm/url') == text('url'), f'{artifact}: missing SCM URL'
            assert (text('scm/connection') or '').startswith('scm:git:https://'), artifact
            assert text('scm/developerConnection') == text('scm/connection'), artifact
        if module != 'analysis':
            dependencies = pom.findall('m:dependencies/m:dependency', namespace)
            assert any(dep.findtext('m:groupId', namespaces=namespace) == args.group and
                       dep.findtext('m:artifactId', namespaces=namespace).startswith('analysis') and
                       dep.findtext('m:version', namespaces=namespace) == args.version
                       for dep in dependencies), f'{artifact}: missing transitive analysis dependency'
        suffixes = ('.pom', '.module', '.aar' if target == '-android' else '.jar',
                    '-sources.jar', '-javadoc.jar')
        if not target:
            suffixes += ('-kotlin-tooling-metadata.json',)
        for suffix in suffixes:
            file = directory / (stem + suffix)
            assert file.is_file() and file.stat().st_size, f'Missing {file}'
            for algorithm in ('md5', 'sha1'):
                checksum = file.with_name(file.name + '.' + algorithm).read_text().strip()
                assert checksum == hashlib.new(algorithm, file.read_bytes()).hexdigest(), str(file)
            if args.signed:
                signature = file.with_name(file.name + '.asc')
                assert signature.is_file() and signature.stat().st_size, f'Missing signature for {file}'
        with zipfile.ZipFile(directory / f'{stem}-sources.jar') as archive:
            assert any(name.endswith('.kt') for name in archive.namelist()), f'{artifact}: empty sources'
            if module == 'rubberband':
                for name in ('native/CMakeLists.txt', 'native/beatweave_jni.cpp',
                             'native/vendor/rubberband/COPYING', 'META-INF/COPYING'):
                    assert name in archive.namelist(), f'{artifact}: missing GPL source {name}'
        with zipfile.ZipFile(directory / f'{stem}-javadoc.jar') as archive:
            assert 'docs/usage.md' in archive.namelist(), f'{artifact}: missing documentation'
        with zipfile.ZipFile(directory / (stem + suffixes[2])) as archive:
            license_file = 'COPYING' if module == 'rubberband' else 'LICENSE'
            assert f'META-INF/{license_file}' in archive.namelist(), f'{artifact}: missing license'
            assert not any(name.endswith('.onnx') for name in archive.namelist()), artifact
            if module == 'rubberband' and target == '-jvm':
                binary = archive.read('META-INF/native/linux-x86_64/libbeatweave_rubberband.so')
                assert binary[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', binary, 18)[0] == 62
                if args.native_library:
                    assert binary == args.native_library.read_bytes(), f'{artifact}: native library differs'
            if module == 'rubberband' and target == '-android':
                for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
                    assert f'jni/{abi}/libbeatweave_rubberband.so' in archive.namelist(), abi
        print(f'PASS {args.group}:{artifact}:{args.version}')
print('PASS all 9 Maven publications; no upload performed')
