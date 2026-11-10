#!/usr/bin/env python3
"""Guarded recovery-slot init diagnostic, then restore factory recovery; never change boot."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = '/dev/block/platform/1021c000.rksdmmc/by-name/'
BOOT_MD5 = '7a3ac489b05f0f2e9cde16280551511a'
RECOVERY_MD5 = 'b32f971e7dfce13c708d264c6221844d'
SYSTEM_REMOTE = '/realdata/local/tmp/rayo12-stage1-system.raw.img'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--replace-known-system', action='store_true', help='Replace only a checksum-known, unmapped earlier diagnostic file')
    args = parser.parse_args()
    candidate = args.candidate.resolve()
    manifest = json.loads((candidate/'MANIFEST.json').read_text())
    system = candidate/'rayo12-stage1-system.raw.img'
    recovery = candidate/'rayo12-stage1-recovery.img'
    original = ROOT/'local/device-kit/original/recovery.img'
    system_bytes, recovery_bytes, original_bytes = system.read_bytes(), recovery.read_bytes(), original.read_bytes()
    if len(system_bytes) != 128*1024*1024 or hashlib.sha256(system_bytes).hexdigest() != manifest['system']['sha256']:
        raise SystemExit('Unexpected diagnostic system file')
    if hashlib.sha256(recovery_bytes).hexdigest() != manifest['recovery']['sha256']:
        raise SystemExit('Unexpected diagnostic recovery file')
    spec = importlib.util.spec_from_file_location('verify', ROOT/'tools/verify-rayo-boot.py')
    verify = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify)
    checked_image = verify.inspect(recovery_bytes, original_bytes, True)
    expected_cmdline = ('buildvariant=eng androidboot.selinux=permissive '
        'androidboot.init_rc=/init.rayo12.stage1.rc androidboot.init_fatal_reboot_target=normal')
    if checked_image['cmdline'] != expected_cmdline:
        raise SystemExit('Not the reviewed stage-one init/normal-return boot profile')
    subprocess.run([sys.executable, str(ROOT/'tools/run-android12-native-smoke.py')], check=True)
    adb = shutil.which('adb')
    prefix = [adb, '-s', '0123456789ABCDEF']

    def command(*parts, timeout=30):
        try:
            return subprocess.run(prefix+list(parts), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            return subprocess.CompletedProcess(prefix+list(parts), 124, error.stdout or b'', error.stderr or b'ADB timeout')

    def checked(*parts, timeout=30):
        result = command(*parts, timeout=timeout)
        if result.returncode:
            raise RuntimeError('ADB command failed: '+result.stderr.decode(errors='replace'))
        return result.stdout.decode(errors='replace').strip()

    bcb = command('exec-out', '/sbin/busybox', 'head', '-c', '32', BASE+'misc')
    if bcb.returncode or len(bcb.stdout) != 32 or any(bcb.stdout):
        raise SystemExit('BCB command is not empty; stop before changing recovery or boot selection')
    before_boot = checked('shell', 'cat', '/proc/sys/kernel/random/boot_id')
    free = checked('shell', '/system/bin/df', '-k', '/realdata')
    available = int(free.splitlines()[-1].split()[3])*1024
    if available < len(system_bytes)+1024*1024*1024:
        raise SystemExit('Insufficient reserved userdata headroom')
    print('Verified readonly diagnostic candidate, v14 boot, factory recovery, empty BCB and storage headroom.', flush=True)
    if not args.execute:
        print('Plan only. No uploads, partition writes or reboot.')
        return
    stamp = time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
    logs = ROOT/'local/android12-stage1-runs'/stamp
    logs.mkdir(parents=True)
    # Fresh independent recovery read, not merely a copy of the write input.
    backup = command('exec-out', 'cat', BASE+'recovery', timeout=60)
    if backup.returncode or len(backup.stdout) != 33554432 or hashlib.md5(backup.stdout).hexdigest() != RECOVERY_MD5:
        raise SystemExit('Fresh recovery backup failed')
    (logs/'original-recovery.img').write_bytes(backup.stdout)
    private = '/data/local/tmp/rayo12-stage1-'+uuid.uuid4().hex[:12]
    checked('shell', '/sbin/busybox', 'mkdir', '-p', private)
    exists = command('shell', '/sbin/busybox', 'test', '-e', SYSTEM_REMOTE)
    system_md5 = hashlib.md5(system_bytes).hexdigest()
    upload = exists.returncode != 0
    if exists.returncode == 0:
        current_md5 = checked('shell', 'md5sum', SYSTEM_REMOTE).split()[0]
        if current_md5 != system_md5:
            known = False
            for prior in (ROOT/'outputs/android12-stage1').glob('*/MANIFEST.json'):
                record = json.loads(prior.read_text())
                raw = prior.parent/'rayo12-stage1-system.raw.img'
                if raw.is_file():
                    data = raw.read_bytes()
                    if hashlib.sha256(data).hexdigest() == record['system']['sha256'] and hashlib.md5(data).hexdigest() == current_md5:
                        known = True
                        break
            backing = command('shell', '/sbin/busybox cat /sys/block/loop*/loop/backing_file')
            if not args.replace_known_system or not known or backing.returncode != 0 or b'rayo12-stage1-system.raw.img' in backing.stdout:
                raise SystemExit('Existing diagnostic file is not explicitly known and unmapped; refusing replacement')
            upload = True
    if upload:
        staging = SYSTEM_REMOTE+'.upload-'+uuid.uuid4().hex[:8]
        checked('push', str(system), staging, timeout=120)
        if checked('shell', 'md5sum', staging).split()[0] != system_md5:
            raise SystemExit('System upload checksum mismatch')
        checked('shell', '/sbin/busybox', 'chmod', '0444', staging)
        checked('shell', '/sbin/busybox', 'mv', staging, SYSTEM_REMOTE)
        if checked('shell', 'md5sum', SYSTEM_REMOTE).split()[0] != system_md5:
            raise SystemExit('System upload checksum mismatch')
    checked('shell', '/sbin/busybox', 'chmod', '0444', SYSTEM_REMOTE)
    trial_remote, original_remote = private+'/trial.img', private+'/original.img'
    for local, remote, md5 in [(recovery, trial_remote, hashlib.md5(recovery_bytes).hexdigest()),
                               (logs/'original-recovery.img', original_remote, RECOVERY_MD5)]:
        checked('push', str(local), remote, timeout=60)
        if checked('shell', 'md5sum', remote).split()[0] != md5:
            raise SystemExit('Recovery transfer checksum mismatch')
    if checked('shell', 'md5sum', BASE+'boot').split()[0] != BOOT_MD5 or checked('shell', 'md5sum', BASE+'recovery').split()[0] != RECOVERY_MD5:
        raise SystemExit('Baseline changed during staging')
    if checked('shell', 'getprop', 'init.svc.flash_recovery') == 'running':
        checked('shell', 'stop', 'flash_recovery')
    written = False
    passed = False
    returned = False
    restored = False
    observations = []
    try:
        written = True
        checked('shell', '/sbin/busybox', 'dd', 'if='+trial_remote, 'of='+BASE+'recovery', 'bs=4096', timeout=60)
        checked('shell', '/sbin/busybox', 'sync')
        if checked('shell', 'md5sum', BASE+'recovery').split()[0] != hashlib.md5(recovery_bytes).hexdigest():
            raise RuntimeError('Trial recovery readback differs')
        print('Trial recovery readback verified. Requesting the one-shot diagnostic boot.', flush=True)
        command('shell', 'setprop', 'sys.powerctl', 'reboot,recovery')
        deadline = time.monotonic()+300
        seen_trial = False
        while time.monotonic() < deadline:
            time.sleep(3)
            version = command('shell', '/system/bin/getprop', 'ro.build.version.release', timeout=5)
            # Stage one intentionally uses only a static rescue shell/probe.
            probe = command('shell', '/sbin/rayo12-props', timeout=5)
            if probe.returncode == 0 and b'ro.rayo.trial=12-stage1' in probe.stdout:
                text = probe.stdout.decode(errors='replace')
                if not seen_trial:
                    print('Android 12 stage-one ADB is responding.', flush=True)
                    print(text, flush=True)
                    (logs/'stage1-properties.txt').write_text(text)
                    kernel_log = command('shell', '/sbin/busybox', 'dmesg')
                    (logs/'stage1-dmesg.txt').write_bytes(kernel_log.stdout+kernel_log.stderr)
                    mounts = checked('shell', '/sbin/busybox', 'cat', '/proc/mounts')
                    (logs/'stage1-mounts.txt').write_text(mounts)
                    rows = [line.split() for line in mounts.splitlines()]
                    storage_ok = all(any(row[1] == path and 'ro' in row[3].split(',') for row in rows) for path in ('/realdata', '/system'))
                    data_ok = any(row[1] == '/data' and row[2] == 'tmpfs' for row in rows)
                    init_md5 = checked('shell', '/sbin/busybox', 'md5sum', '/system/bin/init').split()[0]
                    passed = (all(field in text for field in ('ro.build.version.release=12\n', 'ro.build.version.sdk=31\n',
                              'sys.rayo12.stage=init-ready\n', 'init_exe=/system/bin/init\n', 'uid=0\n'))
                              and storage_ok and data_ok and init_md5 == manifest['android12_init']['md5'])
                    seen_trial = True
                    observations.append({'stage1_passed': passed})
                    print('Readonly/init checks recorded. Waiting for bounded normal-boot return.', flush=True)
            if version.returncode == 0 and version.stdout.strip() == b'8.1.0':
                boot_id = command('shell', 'cat', '/proc/sys/kernel/random/boot_id', timeout=5)
                done = command('shell', 'getprop', 'sys.boot_completed', timeout=5)
                if boot_id.returncode == 0 and boot_id.stdout.decode().strip() != before_boot and done.stdout.strip() == b'1':
                    returned = True
                    previous = command('exec-out', '/sbin/busybox', 'cat', '/proc/last_kmsg', timeout=20)
                    if previous.returncode == 0:
                        (logs/'last-kmsg.txt').write_bytes(previous.stdout)
                    print('Normal Android 8.1 v14 returned.', flush=True)
                    break
        if not returned:
            raise RuntimeError('Normal v14 did not return within the bounded observation period; retain logs and recover before further tests')
    finally:
        # Restore only with a verified normal v14 session; never guess an
        # unidentified boot/recovery environment's write paths.
        version = command('shell', 'getprop', 'ro.build.version.release', timeout=5)
        if written and version.returncode == 0 and version.stdout.strip() == b'8.1.0':
            if checked('shell', 'md5sum', BASE+'boot').split()[0] != BOOT_MD5:
                raise RuntimeError('Unexpected normal boot hash; stop before restoration')
            checked('shell', '/sbin/busybox', 'dd', 'if='+original_remote, 'of='+BASE+'recovery', 'bs=4096', timeout=60)
            checked('shell', '/sbin/busybox', 'sync')
            if checked('shell', 'md5sum', BASE+'recovery').split()[0] != RECOVERY_MD5:
                raise RuntimeError('Original recovery restoration readback failed')
            restored = True
            print('Original independent recovery restored and verified.', flush=True)
        (logs/'RESULT.json').write_text(json.dumps({'candidate': manifest, 'stage1_passed': passed,
            'normal_v14_returned': returned, 'original_recovery_restored': restored,
            'observations': observations}, indent=2)+'\n')
    print('Diagnostic result:', 'PASS' if passed and returned else 'NOT PASSED', 'Logs:', logs)
    if not passed:
        raise SystemExit(2)


if __name__ == '__main__':
    main()
