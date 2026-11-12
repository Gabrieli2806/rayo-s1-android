#!/usr/bin/env python3
"""Make a rehearsed GabOS Android 12 image the normal boot, keeping v14 as the rescue slot.

Layout after promotion:
  boot     = Android 12 image whose boot-success watchdog restarts into recovery
  recovery = the verified Android 8.1 v14 image (rescue system with root ADB)
Nothing else is written: no system/kernel/uboot/trust/resource partition, no
format, no deletion. The Android 12 system and data stay the file-backed images
already verified on userdata. `--action rollback` restores boot = v14 and
recovery = factory from either system.
"""
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
V14 = '7a3ac489b05f0f2e9cde16280551511a'
FACTORY = 'b32f971e7dfce13c708d264c6221844d'
SYSTEM = '/realdata/local/tmp/rayo12-framework-system.raw.img'
DATA = '/realdata/rayo12-framework-data/userdata.ext4'
RESCUE = '/realdata/gabos-rescue'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--action', choices=['promote', 'rollback'], default='promote')
    parser.add_argument('--candidate', type=Path)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']

    def command(*parts, timeout=30):
        try:
            return subprocess.run(prefix+list(parts), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return subprocess.CompletedProcess(parts, 124, b'', b'ADB timeout')

    def checked(*parts, timeout=60):
        result = command(*parts, timeout=timeout)
        if result.returncode:
            raise RuntimeError('ADB command failed: '+result.stderr.decode(errors='replace'))
        return result.stdout.decode(errors='replace').strip()

    def prop(name):
        return command('shell', 'getprop', name, timeout=8).stdout.decode(errors='replace').strip()

    def md5(path, timeout=300):
        return checked('shell', 'md5sum', path, timeout=timeout).split()[0]

    def write_partition(source, name, expected):
        checked('shell', '/sbin/busybox', 'dd', 'if='+source, 'of='+BASE+name, 'bs=4096', timeout=120)
        checked('shell', '/sbin/busybox', 'sync')
        if md5(BASE+name) != expected:
            raise RuntimeError('Partition readback differs: '+name)

    v14_image = ROOT/'local/device-kit/boot/trial-recovery-v14.img'
    factory_image = ROOT/'local/device-kit/original/recovery.img'
    for path, expected in ((v14_image, V14), (factory_image, FACTORY)):
        raw = path.read_bytes()
        if len(raw) != 33554432 or hashlib.md5(raw).hexdigest() != expected:
            raise SystemExit('Host rescue image differs: '+path.name)
    release = prop('ro.build.version.release')
    if checked('shell', 'id', '-u') != '0' or checked('shell', 'uname', '-r') != '3.10.104':
        raise SystemExit('Requires root ADB on this unit')

    def stage_rescue():
        """Keep both rescue images on userdata, reachable from either system."""
        checked('shell', '/sbin/busybox', 'mkdir', '-p', '-m', '0700', RESCUE)
        for path, name, expected in ((v14_image, 'v14.img', V14), (factory_image, 'factory-recovery.img', FACTORY)):
            remote = RESCUE+'/'+name
            if command('shell', 'md5sum', remote, timeout=60).stdout.decode(errors='replace').split()[:1] != [expected]:
                checked('push', str(path), remote, timeout=180)
                if md5(remote) != expected:
                    raise RuntimeError('Rescue image transfer differs: '+name)

    if args.action == 'rollback':
        if release not in ('8.1.0', '12'):
            raise SystemExit('Unidentified system')
        state = {'boot': md5(BASE+'boot'), 'recovery': md5(BASE+'recovery')}
        print(json.dumps({'running': release, **state, 'execute': args.execute}, indent=2), flush=True)
        if not args.execute:
            return
        stage_rescue()
        if release == '8.1.0':
            command('shell', 'stop', 'flash_recovery')
        if state['boot'] != V14:
            write_partition(RESCUE+'/v14.img', 'boot', V14)
        if state['recovery'] != FACTORY:
            write_partition(RESCUE+'/factory-recovery.img', 'recovery', FACTORY)
        print('Rollback written and read back: boot = v14, recovery = factory. Reboot when ready.')
        return

    if not args.candidate:
        parser.error('--candidate is required for promotion')
    candidate = args.candidate.resolve()
    manifest = json.loads((candidate/'MANIFEST.json').read_text())
    boot_image = candidate/'rayo12-framework-boot.img'
    boot_raw = boot_image.read_bytes()
    if (not manifest.get('boot_success_watchdog') or manifest.get('framework_requires_trigger')
            or not manifest.get('boot') or hashlib.sha256(boot_raw).hexdigest() != manifest['boot']['sha256']):
        raise SystemExit('Candidate is not an installed-boot image set')
    spec = importlib.util.spec_from_file_location('verify', ROOT/'tools/verify-rayo-boot.py')
    verify = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify)
    verify.inspect(boot_raw, factory_image.read_bytes(), True)
    boot_md5 = hashlib.md5(boot_raw).hexdigest()
    rehearsed = [path.parent.name for path in (ROOT/'local/android12-framework-runs').glob('*/RESULT.json')
                 if (lambda r: r['candidate']['system']['sha256'] == manifest['system']['sha256']
                     and r.get('framework_boot_completed') and r.get('normal_v14_returned')
                     and r.get('original_recovery_restored'))(json.loads(path.read_text()))]
    if not rehearsed:
        raise SystemExit('This exact system image has no completed, restored recovery-slot rehearsal')
    if release != '8.1.0' or prop('sys.boot_completed') != '1':
        raise SystemExit('Promotion starts from completed normal v14')
    if md5(BASE+'boot') != V14 or md5(BASE+'recovery') != FACTORY:
        raise SystemExit('Unexpected boot/recovery baseline')
    bcb = command('exec-out', '/sbin/busybox', 'head', '-c', '32', BASE+'misc')
    if bcb.returncode or len(bcb.stdout) != 32 or any(bcb.stdout):
        raise SystemExit('BCB is not empty')
    system_md5 = hashlib.file_digest((candidate/manifest['system']['path']).open('rb'), 'md5').hexdigest()
    superblock = command('exec-out', '/sbin/busybox dd if='+DATA+' bs=1024 skip=1 count=1 2>/dev/null')
    if (md5(SYSTEM) != system_md5
            or checked('shell', '/system/bin/toybox', 'stat', '-c', '%s', DATA) != str(manifest['data']['bytes'])
            or len(superblock.stdout) != 1024
            or superblock.stdout[104:120] != uuid.UUID(manifest['data']['uuid']).bytes):
        raise SystemExit('Device system/data files are not the rehearsed ones')
    print(json.dumps({'rehearsal_runs': rehearsed, 'boot_image_md5': boot_md5,
                      'writes': ['recovery: Android 8.1 v14 rescue image', 'boot: GabOS Android 12 image'],
                      'unchanged': ['system', 'kernel', 'uboot', 'trust', 'resource', 'userdata contents'],
                      'rollback': 'python tools/promote-android12.py --action rollback --execute',
                      'execute': args.execute}, indent=2), flush=True)
    if not args.execute:
        return
    stamp = time.strftime('%Y%m%d-%H%M%S')
    logs = ROOT/'local/android12-promotion'/stamp
    logs.mkdir(parents=True)
    stage_rescue()
    remote_boot = RESCUE+'/gabos-boot.img'
    checked('push', str(boot_image), remote_boot, timeout=180)
    if md5(remote_boot) != boot_md5:
        raise RuntimeError('Boot image transfer differs')
    result = {'candidate': str(candidate), 'system_sha256': manifest['system']['sha256'],
              'boot_image_md5': boot_md5, 'rehearsal_runs': rehearsed}
    checked('shell', 'stop', 'flash_recovery')
    write_partition(RESCUE+'/v14.img', 'recovery', V14)
    try:
        write_partition(remote_boot, 'boot', boot_md5)
    except Exception:
        print('Boot write failed; restoring the previous normal boot and factory recovery.', flush=True)
        write_partition(RESCUE+'/v14.img', 'boot', V14)
        write_partition(RESCUE+'/factory-recovery.img', 'recovery', FACTORY)
        raise
    print('Recovery = v14 rescue and boot = GabOS written and read back; requesting normal boot.', flush=True)
    command('shell', 'setprop', 'sys.powerctl', 'reboot')
    limit = manifest['watchdog_seconds']+300
    started = time.monotonic()
    outcome = 'timeout'
    while time.monotonic()-started < limit:
        time.sleep(4)
        release = prop('ro.build.version.release')
        if release == '12' and prop('ro.rayo.trial') == '12-framework' and prop('sys.boot_completed') == '1':
            marker = command('shell', '/sbin/busybox', 'test', '-e', '/dev/.rayo12-boot-ok', timeout=8)
            if marker.returncode == 0 and prop('ro.bootmode') != 'recovery':
                outcome = 'android12-primary'
                break
        elif release == '8.1.0' and prop('sys.boot_completed') == '1' and time.monotonic()-started > 60:
            outcome = 'fell-back-to-v14-rescue'
            break
    result['outcome'] = outcome
    if outcome == 'fell-back-to-v14-rescue':
        # The rescue system is running from the recovery slot: undo the promotion.
        command('shell', 'stop', 'flash_recovery')
        write_partition(RESCUE+'/v14.img', 'boot', V14)
        write_partition(RESCUE+'/factory-recovery.img', 'recovery', FACTORY)
        result['rolled_back'] = True
    if outcome == 'android12-primary':
        result.update({'boot_md5': md5(BASE+'boot'), 'recovery_md5': md5(BASE+'recovery'),
                       'bootmode': prop('ro.bootmode'), 'system_server': checked('shell', 'pidof', 'system_server')})
    (logs/'PROMOTION.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    if outcome != 'android12-primary':
        raise SystemExit(2)


if __name__ == '__main__':
    main()
