#!/usr/bin/env python3
"""Move obsolete trial staging files from physical userdata to a verified host archive.

Only an explicit allowlist of superseded lab artifacts is touched: early
recovery/resource candidates, the stage-one system image and the per-run
graphics/native/stage-one staging directories. Nothing belonging to an installed
system, its data, or the on-device copies of the current normal boot image is
selected. Each item is pulled, compared checksum by checksum with the device,
and only then removed by its exact path.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
BASE = '/dev/block/platform/1021c000.rksdmmc/by-name/'
BOOT = '7a3ac489b05f0f2e9cde16280551511a'
RECOVERY = 'b32f971e7dfce13c708d264c6221844d'
FILES = [
    '/data/local/tmp/rayo-recovery-v08.img', '/data/local/tmp/rayo-recovery-v09.img',
    '/data/local/tmp/rayo-recovery-v11.img', '/data/local/tmp/rayo-recovery-v12.img',
    '/data/local/tmp/trial-recovery-v13.img',
    '/data/local/tmp/rayo-resource-v04.img', '/data/local/tmp/rayo-resource-v05.img',
    '/realdata/local/tmp/rayo12-stage1-system.raw.img',
    '/realdata/local/tmp/rayo-bootstrap-check.img', '/realdata/local/tmp/rayo-recovery-candidate.img',
    '/realdata/local/tmp/rayo81-recovery-v01.img', '/realdata/local/tmp/rayo81-recovery-v02.img',
    '/realdata/local/tmp/resource-gabrieli-v03.img', '/realdata/local/tmp/resource-gabrieli.img',
]
DIRECTORY = re.compile(r'/data/local/tmp/rayo12-(graphics|native|stage1)-[0-9A-Za-z-]+')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']

    def checked(*parts, timeout=120):
        result = subprocess.run(prefix+list(parts), capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('ADB command failed: '+result.stderr.decode(errors='replace'))
        return result.stdout.decode(errors='replace').strip()

    def baseline():
        if (checked('shell', 'getprop', 'ro.build.version.release') != '8.1.0'
                or checked('shell', 'getprop', 'sys.boot_completed') != '1'
                or checked('shell', 'id', '-u') != '0'
                or checked('shell', 'md5sum', BASE+'boot').split()[0] != BOOT
                or checked('shell', 'md5sum', BASE+'recovery').split()[0] != RECOVERY):
            raise SystemExit('Requires completed root normal v14 with factory recovery')

    baseline()
    mapped = checked('shell', '/sbin/busybox cat /sys/block/loop*/loop/backing_file 2>/dev/null; true')
    listing = checked('shell', '/sbin/busybox ls -1d /data/local/tmp/rayo12-* 2>/dev/null; true').splitlines()
    directories = [path for path in listing if DIRECTORY.fullmatch(path)
                   and checked('shell', '/sbin/busybox', 'ls', '-ld', path).startswith('d')]
    files = [path for path in FILES
             if subprocess.run(prefix+['shell', '/sbin/busybox', 'test', '-f', path]).returncode == 0]
    if any(path in mapped for path in files):
        raise SystemExit('A selected file backs a mapped loop device')
    before = int(checked('shell', 'df', '-k', '/realdata').splitlines()[-1].split()[3])
    print(json.dumps({'files': files, 'directories': directories, 'available_kib': before,
                      'execute': args.execute}, indent=2), flush=True)
    if not args.execute:
        return
    archive = ROOT/'local/device-staging-archive'/time.strftime('%Y%m%d-%H%M%S')
    archive.mkdir(parents=True)
    records = []
    for path in files:
        target = archive/path.strip('/').replace('/', '__')
        checked('pull', path, str(target), timeout=300)
        device_md5 = checked('shell', 'md5sum', path, timeout=120).split()[0]
        if hashlib.file_digest(target.open('rb'), 'md5').hexdigest() != device_md5:
            raise RuntimeError('Archive differs; nothing removed: '+path)
        checked('shell', '/sbin/busybox', 'rm', path)
        records.append({'path': path, 'md5': device_md5, 'archive': target.name})
    for path in directories:
        # Directories may contain dangling symlinks that adb pull cannot copy;
        # archive a tar stream and compare it with a second device-side pass.
        target = archive/(path.strip('/').replace('/', '__')+'.tar')
        stream = subprocess.run(prefix+['exec-out', '/sbin/busybox', 'tar', '-cf', '-', '-C',
                                        path.rsplit('/', 1)[0], path.rsplit('/', 1)[1]],
                                capture_output=True, timeout=600)
        device_md5 = checked('shell', '/sbin/busybox tar -cf - -C '+path.rsplit('/', 1)[0]+' '
                             +path.rsplit('/', 1)[1]+' | /sbin/busybox md5sum', timeout=600).split()[0]
        if stream.returncode or hashlib.md5(stream.stdout).hexdigest() != device_md5:
            raise RuntimeError('Directory archive differs; not removed: '+path)
        target.write_bytes(stream.stdout)
        if not DIRECTORY.fullmatch(path):
            raise RuntimeError('Unexpected directory name')
        checked('shell', '/sbin/busybox', 'rm', '-r', path)
        records.append({'path': path, 'tar_md5': device_md5, 'archive': target.name})
    checked('shell', '/sbin/busybox', 'sync')
    baseline()
    after = int(checked('shell', 'df', '-k', '/realdata').splitlines()[-1].split()[3])
    (archive/'ARCHIVE.json').write_text(json.dumps({
        'records': records, 'available_kib_before': before, 'available_kib_after': after,
        'normal_boot_md5': BOOT, 'factory_recovery_md5': RECOVERY}, indent=2)+'\n', encoding='utf-8')
    print('Archived to', archive, '- available MiB:', before//1024, '->', after//1024)


if __name__ == '__main__':
    main()
