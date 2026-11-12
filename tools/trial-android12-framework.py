#!/usr/bin/env python3
"""Guarded recovery-only framework trial with independent normal-boot restoration."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = '/dev/block/platform/1021c000.rksdmmc/by-name/'
BOOT = '7a3ac489b05f0f2e9cde16280551511a'
RECOVERY = 'b32f971e7dfce13c708d264c6221844d'
SYSTEM = '/realdata/local/tmp/rayo12-framework-system.raw.img'
MIB = 1024*1024
DATA_DIR = '/realdata/rayo12-framework-data'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--reuse-known-artifacts', action='store_true',
                        help='Replace a verified unmapped prior system and retain its isolated data UUID')
    parser.add_argument('--pull-data', action='store_true',
                        help='Also copy a private data image larger than 512 MiB to the host run record')
    parser.add_argument('--replace-data', action='store_true',
                        help='Replace the retained private data with the candidate image grown from it')
    args = parser.parse_args()
    candidate = args.candidate.resolve()
    manifest = json.loads((candidate/'MANIFEST.json').read_text())
    watchdog = manifest.get('watchdog_seconds')
    system_bytes, data_bytes = manifest['system']['bytes'], manifest['data']['bytes']
    # The same bounds are enforced by the PID 1 bootstrap inside the ramdisk.
    if (not isinstance(watchdog, int) or not 600 <= watchdog <= 14400
            or manifest.get('device_written') is not False
            or system_bytes % (16*MIB) or not 800*MIB <= system_bytes <= 2047*MIB
            or data_bytes % 4096 or not 256*MIB <= data_bytes <= 3072*MIB):
        raise SystemExit('Unexpected framework trial manifest')
    original = ROOT/'local/device-kit/original/recovery.img'
    payloads = [(candidate/manifest['system']['path'], manifest['system'], system_bytes),
                (candidate/manifest['data']['path'], manifest['data'], data_bytes),
                (candidate/'rayo12-framework-recovery.img', manifest['recovery'], 33554432)]
    md5s = {}
    for path, record, size in payloads:
        raw = path.read_bytes()
        if len(raw) != size or hashlib.sha256(raw).hexdigest() != record['sha256']:
            raise SystemExit('Candidate checksum/size mismatch: '+str(path))
        md5s[path.name] = hashlib.md5(raw).hexdigest()
    if hashlib.md5(original.read_bytes()).hexdigest() != RECOVERY:
        raise SystemExit('Factory recovery backup differs')
    spec = importlib.util.spec_from_file_location('verify', ROOT/'tools/verify-rayo-boot.py')
    verify = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify)
    verified = verify.inspect(payloads[2][0].read_bytes(), original.read_bytes(), True)
    if verified['cmdline'] != 'buildvariant=eng androidboot.selinux=permissive androidboot.init_fatal_reboot_target=normal':
        raise SystemExit('Unexpected recovery boot profile')
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']

    def command(*parts, timeout=20):
        try:
            return subprocess.run(prefix+list(parts), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            return subprocess.CompletedProcess(prefix+list(parts), 124, error.stdout or b'', error.stderr or b'ADB timeout')

    def checked(*parts, timeout=30):
        result = command(*parts, timeout=timeout)
        if result.returncode:
            raise RuntimeError('ADB command failed: '+result.stderr.decode(errors='replace'))
        return result.stdout.decode(errors='replace').strip()

    def boot_id():
        # USB can drop between two reads while the device is rebooting; a failed
        # read is "unknown", never an error that aborts the controller.
        result = command('shell', 'cat', '/proc/sys/kernel/random/boot_id', timeout=10)
        value = result.stdout.decode(errors='replace').strip()
        return value if result.returncode == 0 and len(value) == 36 else None

    def normal():
        return (command('shell', 'getprop', 'ro.build.version.release', timeout=5).stdout.strip() == b'8.1.0'
                and command('shell', 'getprop', 'sys.boot_completed', timeout=5).stdout.strip() == b'1')

    if not normal() or checked('shell', 'id', '-u') != '0':
        raise SystemExit('Requires completed root Android 8.1 baseline')
    for name, expected in (('boot', BOOT), ('recovery', RECOVERY)):
        if checked('shell', 'md5sum', BASE+name).split()[0] != expected:
            raise SystemExit('Unexpected '+name+' partition')
    bcb = command('exec-out', '/sbin/busybox', 'head', '-c', '32', BASE+'misc')
    if bcb.returncode or len(bcb.stdout) != 32 or any(bcb.stdout):
        raise SystemExit('BCB is not empty')
    reuse = False
    replace_data = False
    old_system_bytes = old_data_bytes = 0
    exists = [command('shell', '/sbin/busybox', 'test', '-e', path).returncode for path in (SYSTEM, DATA_DIR)]
    if exists != [1, 1]:
        if not args.reuse_known_artifacts or exists != [0, 0]:
            raise SystemExit('Existing framework artifacts require explicit verified reuse')
        if not checked('shell', '/sbin/busybox', 'ls', '-ld', SYSTEM).startswith('-'):
            raise SystemExit('Existing system is not a regular file')
        old_system_bytes = int(checked('shell', '/system/bin/toybox', 'stat', '-c', '%s', SYSTEM))
        old_data_bytes = int(checked('shell', '/system/bin/toybox', 'stat', '-c', '%s', DATA_DIR+'/userdata.ext4'))
        current_md5 = checked('shell', 'md5sum', SYSTEM, timeout=240).split()[0]
        known = False
        for record in (ROOT/'outputs/android12-framework').glob('*/MANIFEST.json'):
            prior = json.loads(record.read_text())
            path = record.parent/prior['system']['path']
            if path.is_file():
                raw = path.read_bytes()
                if hashlib.sha256(raw).hexdigest() == prior['system']['sha256'] and hashlib.md5(raw).hexdigest() == current_md5:
                    known = True
                    break
        completed = any(record.get('normal_v14_returned') and record.get('original_recovery_restored')
                        and record['candidate']['data']['uuid'] == manifest['data']['uuid']
                        for record in (json.loads(path.read_text()) for path in
                                       (ROOT/'local/android12-framework-runs').glob('*/RESULT.json')))
        backing = command('shell', '/sbin/busybox cat /sys/block/loop*/loop/backing_file')
        superblock = command('exec-out', '/sbin/busybox dd if='+DATA_DIR+'/userdata.ext4 bs=1024 skip=1 count=1 2>/dev/null')
        if (not known or not completed or backing.returncode or b'rayo12-framework' in backing.stdout
                or superblock.returncode or len(superblock.stdout) != 1024
                or superblock.stdout[56:58] != b'\x53\xef'
                or superblock.stdout[104:120] != uuid.UUID(manifest['data']['uuid']).bytes):
            raise SystemExit('Prior framework artifacts are not verified, isolated, and unmapped')
        if old_data_bytes != data_bytes:
            # A grown image may replace the device file only while that file is
            # still byte-identical to the host copy it was grown from.
            replaces = manifest['data'].get('replaces') or {}
            if (not args.replace_data or replaces.get('bytes') != old_data_bytes
                    or checked('shell', 'md5sum', DATA_DIR+'/userdata.ext4', timeout=240).split()[0]
                    != replaces.get('md5')):
                raise SystemExit('Device private data differs from the image this candidate grows; '
                                 'no replacement without --replace-data and an exact match')
            replace_data = True
        reuse = True
    same_system = reuse and current_md5 == md5s[payloads[0][0].name]
    available = int(checked('shell', 'df', '-k', '/realdata').splitlines()[-1].split()[3])*1024
    # A replaced data image is staged beside the old one and renamed after its
    # checksum is verified when that fits. Otherwise the old file, whose exact
    # contents were just matched to the retained host copy, is removed first.
    freed = 0 if same_system else old_system_bytes
    staged_data = True
    needed = ((0 if same_system else system_bytes)+(data_bytes if replace_data or not reuse else 0)
              +(64+768)*MIB)
    print('Physical data space: available %d MiB, reclaimed by replacement %d MiB, required with reserve %d MiB'
          % (available//MIB, freed//MIB, needed//MIB), flush=True)
    if replace_data and available+freed < needed:
        staged_data = False
        freed += old_data_bytes
        print('Staging beside the old data does not fit; it will be removed before upload.', flush=True)
    if available+freed < needed:
        raise SystemExit('Insufficient reserved physical data space')
    print('Verified baseline, independent recovery, empty BCB, new isolated data path, and space reserve.', flush=True)
    if not args.execute:
        print('Plan only; no device changes.')
        return
    stamp = time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
    logs = ROOT/'local/android12-framework-runs'/stamp
    logs.mkdir(parents=True)
    backup = command('exec-out', 'cat', BASE+'recovery', timeout=60)
    if backup.returncode or len(backup.stdout) != 33554432 or hashlib.md5(backup.stdout).hexdigest() != RECOVERY:
        raise SystemExit('Fresh independent recovery backup failed')
    (logs/'original-recovery.img').write_bytes(backup.stdout)
    before_boot = boot_id()
    if before_boot is None:
        raise SystemExit('Could not read the baseline boot identifier')
    private = '/data/local/tmp/rayo12-framework-'+uuid.uuid4().hex[:12]
    checked('shell', '/sbin/busybox', 'mkdir', '-m', '0700', private)
    if reuse and not same_system:
        checked('shell', '/sbin/busybox', 'rm', SYSTEM)
    elif not reuse:
        checked('shell', '/sbin/busybox', 'mkdir', '-m', '0700', DATA_DIR)
    uploads = [(payloads[2][0], private+'/trial.img'),
               (logs/'original-recovery.img', private+'/original.img')]
    if not same_system:
        uploads.insert(0, (payloads[0][0], SYSTEM))
    else:
        print('Reusing checksum-identical, verified and unmapped system file.', flush=True)
    if not reuse or replace_data:
        uploads.insert(0 if same_system else 1,
                       (payloads[1][0], DATA_DIR+'/userdata.ext4'+('.new' if replace_data and staged_data else '')))
    if replace_data and not staged_data:
        checked('shell', '/sbin/busybox', 'rm', DATA_DIR+'/userdata.ext4')
    for path, remote in uploads:
        expected = RECOVERY if path.name == 'original-recovery.img' else md5s[path.name]
        print('Uploading and verifying '+path.name, flush=True)
        slow = 120+path.stat().st_size//(3*MIB)  # seconds at a pessimistic 3 MiB/s
        checked('push', str(path), remote, timeout=slow)
        checked('shell', '/sbin/busybox', 'chmod', '0600', remote)
        if checked('shell', 'md5sum', remote, timeout=slow).split()[0] != expected:
            raise RuntimeError('Upload checksum mismatch: '+remote)
    if replace_data and staged_data:
        # The identical prior contents remain in the host run they were pulled into.
        checked('shell', '/sbin/busybox', 'mv', '-f', DATA_DIR+'/userdata.ext4.new', DATA_DIR+'/userdata.ext4')
        checked('shell', '/sbin/busybox', 'sync')
    checked('shell', '/sbin/busybox', 'chmod', '0444', SYSTEM)
    for name, expected in (('boot', BOOT), ('recovery', RECOVERY)):
        if checked('shell', 'md5sum', BASE+name).split()[0] != expected:
            raise RuntimeError('Baseline changed during staging')
    written, returned, restored, seen, boot_complete = False, False, False, False, False
    started = time.monotonic()
    next_capture = 0
    count = 0
    framework_started = False
    boot_reported = False
    settings_opened = False
    stable_pid = None
    stable_since = 0
    error = None
    try:
        checked('shell', 'stop', 'flash_recovery')
        written = True
        checked('shell', '/sbin/busybox', 'dd', 'if='+private+'/trial.img', 'of='+BASE+'recovery', 'bs=4096', timeout=60)
        checked('shell', '/sbin/busybox', 'sync')
        if checked('shell', 'md5sum', BASE+'recovery').split()[0] != md5s[payloads[2][0].name]:
            raise RuntimeError('Recovery trial readback differs')
        print('Recovery trial readback verified; requesting isolated framework boot. Logs: '+str(logs), flush=True)
        command('shell', 'setprop', 'sys.powerctl', 'reboot,recovery')
        started = time.monotonic()
        while time.monotonic()-started < watchdog+150:
            time.sleep(3)
            version = command('shell', 'getprop', 'ro.build.version.release', timeout=5).stdout.strip()
            if version == b'12':
                marker = command('shell', 'getprop', 'ro.rayo.trial', timeout=5)
                if marker.returncode:
                    continue  # USB may disappear between independent reads.
                if marker.stdout.strip() != b'12-framework':
                    raise RuntimeError('Unidentified Android 12 environment')
                if not seen:
                    seen = True
                    print('Android 12 framework trial USB is responding.', flush=True)
                if time.monotonic() >= next_capture:
                    count += 1
                    for name, parts in (('properties', ('shell', 'getprop')),
                                        ('processes', ('shell', 'ps', '-A')),
                                        ('mounts', ('shell', 'cat', '/proc/mounts')),
                                        ('kernel', ('shell', '/sbin/busybox', 'dmesg')),
                                        ('logcat', ('shell', 'logcat', '-b', 'all', '-d', '-t', '6000'))):
                        result = command(*parts, timeout=15)
                        (logs/(str(count)+'-'+name+'.txt')).write_bytes(result.stdout+result.stderr)
                    next_capture = time.monotonic()+35
                if manifest.get('framework_requires_trigger') and not framework_started:
                    identity = command('shell', '/sbin/busybox', 'readlink', '/proc/1/exe', timeout=5)
                    owner = command('shell', 'id', '-u', timeout=5)
                    mounts = command('shell', 'cat', '/proc/mounts', timeout=5)
                    init_hash = command('shell', '/sbin/busybox', 'md5sum', '/system/bin/init', timeout=10)
                    if any(result.returncode for result in (identity, owner, mounts, init_hash)):
                        continue
                    rows = [line.split() for line in mounts.stdout.decode().splitlines()]
                    isolated = (any(row[0] == '/dev/block/loop6' and row[1] == '/data' for row in rows)
                                and any(row[1] == '/system' and 'ro' in row[3].split(',') for row in rows))
                    if (identity.stdout.strip() != b'/system/bin/init' or owner.stdout.strip() != b'0'
                            or not isolated or init_hash.stdout.split()[0].decode() != manifest['android12_init_md5']):
                        raise RuntimeError('Framework preflight identity or storage isolation failed')
                    result = command('shell', 'setprop', 'sys.rayo12.framework', 'start', timeout=5)
                    if result.returncode == 0:
                        framework_started = True
                        print('Root init/storage preflight passed; explicitly starting framework.', flush=True)
                if not boot_complete and command('shell', 'getprop', 'sys.boot_completed', timeout=5).stdout.strip() == b'1':
                    if not boot_reported:
                        print('Boot-complete flag reported; checking a stable interactive Settings window.', flush=True)
                        boot_reported = True
                    pid = command('shell', 'pidof', 'system_server', timeout=5).stdout.strip()
                    if not pid.isdigit():
                        continue
                    if pid != stable_pid:
                        stable_pid, stable_since, settings_opened = pid, time.monotonic(), False
                    if not settings_opened:
                        pre_window = command('shell', 'dumpsys', 'window', 'displays', timeout=15)
                        if pre_window.returncode == 0 and b'FallbackHome' not in pre_window.stdout:
                            result = command('shell', 'am', 'start', '-a', 'android.settings.SETTINGS', timeout=15)
                            (logs/'open-settings.txt').write_bytes(result.stdout+result.stderr)
                            combined = result.stdout+result.stderr
                            settings_opened = (result.returncode == 0 and b'Starting:' in combined
                                               and b'Error:' not in combined and b"Can't find service" not in combined)
                    if settings_opened and time.monotonic()-stable_since >= 45:
                        window = command('shell', 'dumpsys', 'window', 'displays', timeout=15)
                        (logs/'settings-window.txt').write_bytes(window.stdout+window.stderr)
                        focused = any(b'mCurrentFocus' in line and b'com.android.tv.settings' in line
                                      and b'FallbackHome' not in line
                                      for line in window.stdout.splitlines())
                        if window.returncode == 0 and focused:
                            boot_complete = True
                            print('Settings focused with the same system_server for at least 45 seconds; physical inspection still required.', flush=True)
                        elif window.returncode == 0 and b'FallbackHome' not in window.stdout:
                            settings_opened = False
            elif version == b'8.1.0' and normal():
                current = boot_id()
                if current is not None and current != before_boot:
                    returned = True
                    break
        if not returned:
            raise RuntimeError('Normal baseline has not returned; stop further trials and inspect rescue state')
    except Exception as exc:
        error = str(exc)
        print('Trial diagnostic interrupted: '+error, flush=True)
    finally:
        # Remain available to the independent watchdog even after a diagnostic
        # failure. Never write restoration blocks in an unidentified environment.
        while written and not returned and time.monotonic()-started < watchdog+250:
            if normal() and boot_id() not in (None, before_boot):
                returned = True
                break
            time.sleep(3)
        if written and returned and normal():
            previous = command('exec-out', '/sbin/busybox', 'cat', '/proc/last_kmsg', timeout=30)
            (logs/'last-kmsg.txt').write_bytes(previous.stdout+previous.stderr)
            if checked('shell', 'md5sum', BASE+'boot').split()[0] != BOOT:
                raise RuntimeError('Normal boot hash differs; no recovery restoration attempted')
            checked('shell', '/sbin/busybox', 'dd', 'if='+private+'/original.img', 'of='+BASE+'recovery', 'bs=4096', timeout=60)
            checked('shell', '/sbin/busybox', 'sync')
            if checked('shell', 'md5sum', BASE+'recovery').split()[0] != RECOVERY:
                raise RuntimeError('Factory recovery restoration readback differs')
            restored = True
            print('Normal Android 8.1 returned; factory recovery restored and hash verified.', flush=True)
            if data_bytes <= 512*MIB or args.pull_data:
                data = command('pull', DATA_DIR+'/userdata.ext4', str(logs/'private-data-after.ext4'),
                               timeout=120+data_bytes//(3*MIB))
                (logs/'private-data-pull.txt').write_bytes(data.stdout+data.stderr)
        (logs/'RESULT.json').write_text(json.dumps({'candidate': manifest, 'android12_usb_seen': seen,
            'boot_completed_reported': boot_reported,
            'framework_boot_completed': boot_complete, 'normal_v14_returned': returned,
            'original_recovery_restored': restored, 'error': error}, indent=2)+'\n')
    print('Framework boot:', boot_complete, 'normal return:', returned, 'recovery restored:', restored, flush=True)
    if not (boot_complete and returned and restored):
        raise SystemExit(2)


if __name__ == '__main__':
    main()
