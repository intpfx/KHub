#!/usr/bin/env python3
"""Extract hash-pinned wrapper inputs from an explicitly selected Gradle 8.13.

No downloads, installation, wrapper execution, or toolchain modification.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-home', type=Path, required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.gradle_home / 'lib/plugins/gradle-wrapper-main-8.13.jar') as archive:
        data = archive.read('gradle-wrapper.jar')
    rows = [row for row in json.loads(Path(__file__).with_name('untracked-resources.json').read_text())['resources'] if row['path'].endswith('/gradle-wrapper.jar')]
    spec = importlib.util.spec_from_file_location('resource_policy', Path(__file__).with_name('prepare-untracked-resources.py'))
    policy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(policy)
    for row in rows:
        if len(data) != row['bytes'] or hashlib.sha256(data).hexdigest() != row['sha256']:
            raise SystemExit('Gradle wrapper SHA-256 mismatch')
        policy.checked_path(ROOT, row['path'])
    for row in rows:
        target = policy.checked_path(ROOT, row['path'])
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print(f'PASS: {len(rows)} wrapper paths prepared from Gradle 8.13 with exact SHA-256 verification.')

if __name__ == '__main__':
    main()
