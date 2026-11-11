#!/usr/bin/env python3
"""Remove only checksum-known duplicate trial/original staging pairs in normal v14."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = '/dev/block/platform/1021c000.rksdmmc/by-name/'
ORIGINAL = 'b32f971e7dfce13c708d264c6221844d'
BOOT = '7a3ac489b05f0f2e9cde16280551511a'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--framework', action='store_true', help='Check bounded full-framework staging pairs instead of native profiles')
    args = parser.parse_args()
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']

    def checked(*command):
        result = subprocess.run(prefix+list(command), capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace'))
        return result.stdout.decode(errors='replace').strip()

    def baseline():
        if checked('shell', 'getprop', 'ro.build.version.release') != '8.1.0' or checked('shell', 'getprop', 'sys.boot_completed') != '1':
            raise RuntimeError('Requires completed normal v14 boot')
        for name, digest in (('boot', BOOT), ('recovery', ORIGINAL)):
            if checked('shell', 'md5sum', BASE+name).split()[0] != digest:
                raise RuntimeError('Unexpected '+name+' partition; no cleanup allowed')

    baseline()
    host_original = ROOT/'local/device-kit/original/recovery.img'
    if hashlib.md5(host_original.read_bytes()).hexdigest() != ORIGINAL:
        raise RuntimeError('Original recovery host backup mismatch')
    known = {}
    profile = 'framework' if args.framework else 'stage1'
    for manifest in (ROOT/('outputs/android12-'+profile)).glob('*/MANIFEST.json'):
        record = json.loads(manifest.read_text())
        path = manifest.parent/('rayo12-'+profile+'-recovery.img')
        if path.is_file():
            raw = path.read_bytes()
            if len(raw) == 33554432 and hashlib.sha256(raw).hexdigest() == record['recovery']['sha256']:
                known[hashlib.md5(raw).hexdigest()] = str(path.relative_to(ROOT))
    staging_prefix = '/data/local/tmp/rayo12-'+profile+'-'
    listing = checked('shell', '/sbin/busybox', 'ls', '-1td', staging_prefix+'*')
    directories = [path for path in listing.splitlines()
                   if re.fullmatch(re.escape(staging_prefix)+r'[0-9a-f]{12}', path)]
    records = []
    for directory in directories[2:]:  # Retain the two newest independent device copies.
        if not checked('shell', '/sbin/busybox', 'ls', '-ld', directory).startswith('d'):
            continue
        names = set(checked('shell', '/sbin/busybox', 'ls', '-1', directory).splitlines())
        if names != {'original.img', 'trial.img'}:
            continue
        original_md5 = checked('shell', 'md5sum', directory+'/original.img').split()[0]
        trial_md5 = checked('shell', 'md5sum', directory+'/trial.img').split()[0]
        if original_md5 != ORIGINAL or trial_md5 not in known:
            continue
        records.append({'directory': directory, 'trial_md5': trial_md5, 'host_trial': known[trial_md5]})
    print(json.dumps({'known_pairs': len(records), 'reclaim_bytes': len(records)*67108864,
                      'retained_newest_directories': directories[:2], 'execute': args.execute}, indent=2), flush=True)
    if not args.execute:
        return
    baseline()
    for record in records:
        directory = record['directory']
        # Exact files only; no recursive deletion, wildcard or partition write.
        checked('shell', '/sbin/busybox', 'rm', directory+'/trial.img', directory+'/original.img')
        checked('shell', '/sbin/busybox', 'rmdir', directory)
    baseline()
    print('Removed checksum-known duplicate staging pairs; normal boot and factory recovery verified.')


if __name__ == '__main__':
    main()
