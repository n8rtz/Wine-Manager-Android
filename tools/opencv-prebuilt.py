#!/usr/bin/env python3
"""Seal/verify the real CI-built minimal OpenCV files; no download or native tools needed to verify."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
RECIPES = ('tools/build-opencv-minimal.py', 'tools/android_native_toolchain.py')

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def verify_native(path):
    data = path.read_bytes()
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('Expected genuine little-endian ELF64 AArch64 library')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    loads = [struct.unpack_from('<IIQQQQQQ', data, offset + i * size) for i in range(count)]
    if not any(p[0] == 1 for p in loads) or any(p[7] < 16384 for p in loads if p[0] == 1):
        raise ValueError('ELF LOAD segments must support 16 KiB pages')

def manifest_key(path, directory):
    # The CI manifest uses repository-relative POSIX keys on every build host.
    return path.relative_to(directory).as_posix()

def verify_manifest(record, files, recipes):
    changed_files = sorted(k for k in set(record['files']) | set(files)
                           if record['files'].get(k) != files.get(k))
    changed_recipes = sorted(k for k in set(record['recipes']) | set(recipes)
                             if record['recipes'].get(k) != recipes.get(k))
    if changed_files or changed_recipes:
        details = ', '.join((changed_files + changed_recipes)[:8])
        raise ValueError(
            f'OpenCV artifact/recipe hash mismatch: {details}. '
            'On Windows, update .gitattributes and restore the unmodified sealed prebuilt files/recipes '
            'from Git to undo CRLF conversion. For intentional content changes, rebuild in CI; '
            'do not bypass verification or reseal locally.')

def inspect(directory):
    files = {manifest_key(p, directory): digest(p) for p in sorted(directory.rglob('*'))
             if p.is_file() and p.name != 'manifest.json'}
    required = ('java/org/opencv/core/Mat.java', 'java/org/opencv/imgproc/Imgproc.java',
                'java/org/opencv/android/Utils.java', 'resources/META-INF/licenses/opencv-LICENSE.txt',
                'jniLibs/arm64-v8a/libopencv_java4.so')
    if not all(p in files for p in required):
        raise ValueError('Prebuilt OpenCV is missing. Pull the CI artifact commit; do not bypass verification.')
    verify_native(directory / required[-1])
    if sum(p.stat().st_size for p in directory.rglob('*') if p.is_file()) > 32 * 1024 * 1024:
        raise ValueError('Minimal OpenCV artifact unexpectedly exceeds 32 MiB')
    return files

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seal', action='store_true', help='CI producer only: record build provenance')
    parser.add_argument('--directory', type=Path, default=ROOT / 'opencv-minimal/prebuilt')
    args = parser.parse_args()
    files = inspect(args.directory)
    recipes = {p: digest(ROOT / p) for p in RECIPES}
    manifest = args.directory / 'manifest.json'
    if args.seal:
        manifest.write_text(json.dumps(dict(opencv='4.12.0', ndk='28.2.13676358', cmake='3.22.1',
            abi='arm64-v8a', source_commit=os.environ['GITHUB_SHA'],
            build_run=f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
            recipes=recipes, files=files), indent=2) + '\n')
    record = json.loads(manifest.read_text(encoding='utf-8'))
    verify_manifest(record, files, recipes)
    print(f'Verified minimal OpenCV: {len(files)} files, arm64 ELF, 16 KiB LOAD alignment, recipe hashes and license')

if __name__ == '__main__':
    main()
