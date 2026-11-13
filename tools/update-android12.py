#!/usr/bin/env python3
"""Update the installed GabOS Android 12 system from the v14 rescue slot.

Installed layout (see promote-android12.py): boot = GabOS image with a
boot-success watchdog, recovery = Android 8.1 v14 rescue system. The Android 12
system image file on userdata cannot be replaced while GabOS runs from it, so:

1. From running GabOS (or an already running rescue), restart into the rescue.
2. In the rescue, verify the layout, replace the system file and verify it.
3. Write the matching boot image (it carries the new system checksum) and read
   it back, then restart normally and wait for a completed GabOS boot.

The private data image is never touched. If the new image does not complete a
boot, the watchdog returns to the rescue; restore with this tool and the
previous candidate, or with promote-android12.py --action rollback.
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
SYSTEM = '/realdata/local/tmp/rayo12-framework-system.raw.img'
DATA = '/realdata/rayo12-framework-data/userdata.ext4'
RESCUE = '/realdata/gabos-rescue'
MIB = 1024*1024


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
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

    candidate = args.candidate.resolve()
    manifest = json.loads((candidate/'MANIFEST.json').read_text())
    boot_image = candidate/'rayo12-framework-boot.img'
    system_image = candidate/manifest['system']['path']
    boot_raw = boot_image.read_bytes()
    if (not manifest.get('boot_success_watchdog') or not manifest.get('boot')
            or hashlib.sha256(boot_raw).hexdigest() != manifest['boot']['sha256']):
        raise SystemExit('Candidate is not an installed-boot image set')
    spec = importlib.util.spec_from_file_location('verify', ROOT/'tools/verify-rayo-boot.py')
    verify = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify)
    verify.inspect(boot_raw, (ROOT/'local/device-kit/original/recovery.img').read_bytes(), True)
    boot_md5 = hashlib.md5(boot_raw).hexdigest()
    system_md5 = hashlib.file_digest(system_image.open('rb'), 'md5').hexdigest()
    if hashlib.file_digest(system_image.open('rb'), 'sha256').hexdigest() != manifest['system']['sha256']:
        raise SystemExit('Candidate system image differs from its manifest')
    known_boots = set()
    for record in (ROOT/'outputs/android12-framework').glob('*/MANIFEST.json'):
        prior = json.loads(record.read_text())
        image = record.parent/'rayo12-framework-boot.img'
        if prior.get('boot_success_watchdog') and image.is_file():
            known_boots.add(hashlib.md5(image.read_bytes()).hexdigest())
    for record in (ROOT/'local/android12-promotion').glob('*/*.json'):
        value = json.loads(record.read_text()).get('boot_image_md5')
        if value:
            known_boots.add(value)

    release = prop('ro.build.version.release')
    if release == '12':
        if prop('ro.rayo.trial') != '12-framework' or prop('sys.boot_completed') != '1':
            raise SystemExit('Unidentified Android 12 state')
        print('GabOS is running; the update is applied from the rescue system.', flush=True)
        if not args.execute:
            print('Plan only; add --execute to restart into the rescue and update.')
            return
        if md5(BASE+'recovery') != V14:
            raise SystemExit('Recovery slot does not hold the v14 rescue')
        command('shell', 'sync')
        command('shell', 'setprop', 'sys.powerctl', 'reboot,recovery')
        started = time.monotonic()
        while time.monotonic()-started < 300:
            time.sleep(4)
            if prop('ro.build.version.release') == '8.1.0' and prop('sys.boot_completed') == '1':
                break
        else:
            raise SystemExit('The rescue system did not start; check the device')
    elif release != '8.1.0' or prop('sys.boot_completed') != '1':
        raise SystemExit('Requires running GabOS or the completed v14 rescue')

    if checked('shell', 'id', '-u') != '0' or checked('shell', 'uname', '-r') != '3.10.104':
        raise SystemExit('Requires root ADB on this unit')
    current_boot, current_recovery = md5(BASE+'boot'), md5(BASE+'recovery')
    if current_recovery != V14 or current_boot not in known_boots:
        raise SystemExit('Not the installed layout (boot %s, recovery %s)' % (current_boot, current_recovery))
    mapped = command('shell', '/sbin/busybox cat /sys/block/loop*/loop/backing_file 2>/dev/null; true').stdout
    if b'rayo12-framework' in mapped:
        raise SystemExit('GabOS images are mapped; this is not the rescue system')
    superblock = command('exec-out', '/sbin/busybox dd if='+DATA+' bs=1024 skip=1 count=1 2>/dev/null')
    if (checked('shell', '/system/bin/toybox', 'stat', '-c', '%s', DATA) != str(manifest['data']['bytes'])
            or len(superblock.stdout) != 1024
            or superblock.stdout[104:120] != uuid.UUID(manifest['data']['uuid']).bytes):
        raise SystemExit('Device private data does not match the candidate')
    old_bytes = int(checked('shell', '/system/bin/toybox', 'stat', '-c', '%s', SYSTEM))
    available = int(checked('shell', 'df', '-k', '/realdata').splitlines()[-1].split()[3])*1024
    needed = system_image.stat().st_size+768*MIB
    print(json.dumps({'boot_now': current_boot, 'boot_new': boot_md5, 'system_new_md5': system_md5,
                      'available_mib': available//MIB, 'freed_mib': old_bytes//MIB, 'needed_mib': needed//MIB,
                      'execute': args.execute}, indent=2), flush=True)
    if available+old_bytes < needed:
        raise SystemExit('Insufficient physical space with the reserve')
    if not args.execute:
        return
    logs = ROOT/'local/android12-promotion'/time.strftime('%Y%m%d-%H%M%S')
    logs.mkdir(parents=True)
    result = {'action': 'update', 'candidate': str(candidate), 'previous_boot_md5': current_boot,
              'boot_image_md5': boot_md5, 'system_md5': system_md5}
    remote_boot = RESCUE+'/gabos-boot.img'
    checked('push', str(boot_image), remote_boot, timeout=180)
    if md5(remote_boot) != boot_md5:
        raise RuntimeError('Boot image transfer differs')
    checked('shell', '/sbin/busybox', 'rm', SYSTEM)
    slow = 120+system_image.stat().st_size//(3*MIB)
    checked('push', str(system_image), SYSTEM, timeout=slow)
    checked('shell', '/sbin/busybox', 'chmod', '0444', SYSTEM)
    if md5(SYSTEM, timeout=slow) != system_md5:
        raise RuntimeError('System image transfer differs; boot not changed')
    checked('shell', '/sbin/busybox', 'sync')
    command('shell', 'stop', 'flash_recovery')
    checked('shell', '/sbin/busybox', 'dd', 'if='+remote_boot, 'of='+BASE+'boot', 'bs=4096', timeout=120)
    checked('shell', '/sbin/busybox', 'sync')
    if md5(BASE+'boot') != boot_md5:
        raise RuntimeError('Boot readback differs; the rescue remains available')
    print('System and boot updated and verified; restarting into GabOS.', flush=True)
    command('shell', 'setprop', 'sys.powerctl', 'reboot')
    started = time.monotonic()
    outcome = 'timeout'
    while time.monotonic()-started < manifest['watchdog_seconds']+300:
        time.sleep(4)
        release = prop('ro.build.version.release')
        if release == '12' and prop('sys.boot_completed') == '1':
            if command('shell', '/sbin/busybox', 'test', '-e', '/dev/.rayo12-boot-ok', timeout=8).returncode == 0:
                outcome = 'android12-primary'
                break
        elif release == '8.1.0' and prop('sys.boot_completed') == '1' and time.monotonic()-started > 60:
            outcome = 'fell-back-to-v14-rescue'
            break
    result['outcome'] = outcome
    if outcome == 'android12-primary':
        result.update({'boot_md5': md5(BASE+'boot'), 'recovery_md5': md5(BASE+'recovery'),
                       'bootmode': prop('ro.bootmode'), 'system_server': checked('shell', 'pidof', 'system_server')})
    (logs/'UPDATE.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    if outcome != 'android12-primary':
        raise SystemExit(2)


if __name__ == '__main__':
    main()
