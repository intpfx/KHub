#!/usr/bin/env python3
"""Verify or restore explicit local build inputs without tracking binary bodies.

--source-dir is a user-selected directory mirroring repository paths. All inputs
are validated before any copy. No network, archive execution, or source deletion.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def checked_path(base, relative):
    path = base / relative
    if Path(relative).is_absolute() or '..' in Path(relative).parts:
        raise ValueError('Unsafe resource path')
    for component in [path, *path.parents]:
        if component == base:
            break
        if component.is_symlink():
            raise ValueError('Symlink resource paths are not accepted: ' + relative)
    if not path.resolve().is_relative_to(base.resolve()):
        raise ValueError('Resource escapes selected root: ' + relative)
    return path

def matches(path, row):
    return path.is_file() and path.stat().st_size == row['bytes'] and hashlib.sha256(path.read_bytes()).hexdigest() == row['sha256']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    rows = json.loads(Path(__file__).with_name('untracked-resources.json').read_text())['resources']
    missing = [row for row in rows if not matches(checked_path(ROOT, row['path']), row)]
    if args.check or not args.source_dir:
        if missing:
            raise SystemExit('Missing or mismatched local resources (supply --source-dir explicitly):\n' + '\n'.join(row['path'] for row in missing))
        print('PASS resource digests:', len(rows))
        return
    source = args.source_dir.resolve(strict=True)
    for row in missing:
        path = checked_path(source, row['path'])
        if not matches(path, row):
            raise SystemExit('Source digest mismatch or missing: ' + row['path'])
    for row in missing:
        target = checked_path(ROOT, row['path'])
        target.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix='.khub-resource-', dir=target.parent)
        try:
            with os.fdopen(fd, 'wb') as output:
                output.write(checked_path(source, row['path']).read_bytes())
            if not matches(Path(temporary), row):
                raise ValueError('Resource changed during copy: ' + row['path'])
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    print('PASS restored local resources:', len(missing))

if __name__ == '__main__':
    main()
