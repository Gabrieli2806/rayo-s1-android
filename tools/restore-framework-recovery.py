#!/usr/bin/env python3
"""Restore factory recovery after an interrupted trial, only from verified normal v14."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = '/dev/block/platform/1021c000.rksdmmc/by-name/'
BOOT_MD5 = '7a3ac489b05f0f2e9cde16280551511a'
RECOVERY_MD5 = 'b32f971e7dfce13c708d264c6221844d'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    run = args.run.resolve()
    if not run.is_relative_to((ROOT/'local/android12-framework-runs').resolve()):
        raise SystemExit('Select a recorded local framework run')
    record = json.loads((run/'RESULT.json').read_text(encoding='utf-8'))
    original = run/'original-recovery.img'
    raw = original.read_bytes()
    if len(raw) != 33554432 or hashlib.md5(raw).hexdigest() != RECOVERY_MD5:
        raise SystemExit('Independent factory recovery backup differs')
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']

    def command(*parts, timeout=30):
        result = subprocess.run(prefix+list(parts), capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('ADB command failed: '+result.stderr.decode(errors='replace'))
        return result.stdout

    def baseline():
        if (command('shell', 'getprop', 'ro.build.version.release').strip() != b'8.1.0'
                or command('shell', 'getprop', 'sys.boot_completed').strip() != b'1'
                or command('shell', 'id', '-u').strip() != b'0'
                or command('shell', 'md5sum', BASE+'boot').split()[0].decode() != BOOT_MD5):
            raise RuntimeError('Requires completed root normal v14 with its verified boot partition')

    baseline()
    bcb = command('exec-out', '/sbin/busybox', 'head', '-c', '32', BASE+'misc')
    if len(bcb) != 32 or any(bcb):
        raise SystemExit('Unexpected BCB; inspect before restoring')
    current = command('exec-out', 'cat', BASE+'recovery', timeout=60)
    if len(current) != len(raw):
        raise SystemExit('Incomplete recovery readback')
    if hashlib.md5(current).hexdigest() == RECOVERY_MD5:
        print('Factory recovery already matches; normal v14 verified. No write needed.')
        return
    if hashlib.sha256(current).hexdigest() != record['candidate']['recovery']['sha256']:
        raise SystemExit('Recovery does not match the selected trial; stop and inspect')
    print('Verified normal v14, empty BCB, independent backup and exact trial recovery.')
    if not args.execute:
        print('Plan only; add --execute to restore factory recovery.')
        return
    (run/'trial-recovery-before-repair.img').write_bytes(current)
    private = '/data/local/tmp/rayo12-recovery-repair-'+uuid.uuid4().hex[:12]
    command('shell', '/sbin/busybox', 'mkdir', '-m', '0700', private)
    remote = private+'/original.img'
    command('push', str(original), remote, timeout=60)
    command('shell', '/sbin/busybox', 'chmod', '0600', remote)
    if command('shell', 'md5sum', remote).split()[0].decode() != RECOVERY_MD5:
        raise RuntimeError('Factory recovery transfer checksum differs')
    baseline()
    command('shell', 'stop', 'flash_recovery')
    command('shell', '/sbin/busybox', 'dd', 'if='+remote, 'of='+BASE+'recovery', 'bs=4096', timeout=60)
    command('shell', 'sync')
    restored = command('exec-out', 'cat', BASE+'recovery', timeout=60)
    if restored != raw:
        raise RuntimeError('Recovery restoration readback differs; do not start another trial')
    baseline()
    (run/'RECOVERY-RESTORATION.json').write_text(json.dumps({
        'factory_recovery_restored': True, 'normal_v14_verified': True,
        'recovery_sha256': hashlib.sha256(restored).hexdigest(),
        'device_backup': remote}, indent=2)+'\n', encoding='utf-8')
    print('Factory recovery restored and complete readback matched; normal v14 unchanged.')


if __name__ == '__main__':
    main()
