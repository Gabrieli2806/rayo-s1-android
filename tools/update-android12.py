#!/usr/bin/env python3
"""Update the installed GabOS Android 12 system from the v14 rescue slot.

Installed layout (see promote-android12.py): boot = GabOS image with a
boot-success watchdog, recovery = Android 8.1 v14 rescue system. The Android 12
system image file on userdata cannot be replaced while GabOS runs from it, so:

1. From running GabOS (or an already running rescue), restart into the rescue.
2. In the rescue, verify the layout, replace the system file and verify it.
3. Write the matching boot image (it carries the new system checksum) and read
   it back, then restart normally and wait for a completed GabOS boot.

The private data image is never replaced. With --grow-data, a candidate built
for a larger data image (same UUID) is accepted, and the device file is grown in
place (e2fsck, truncate, resize2fs) before the new boot image is written.

--retire-old-systems (owner decision, November 2026) deletes, from the rescue,
the factory Android 7.1.2 user data on userdata, leftover trial staging files
and old trial copies inside the rescue's own data. It keeps both system images
in use, the rescue's runtime overlay, gabos-rescue/ and the GabOS data. The 7.1.2
system partition and the factory images (host copies, gabos-rescue/) remain, so
7.1.2 can still be restored, but it would start as after a factory reset.

If the new image does not complete a boot, the watchdog returns to the rescue;
restore with this tool and the previous candidate, or with
promote-android12.py --action rollback.
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
# Factory 7.1.2 user data and trial leftovers removed by --retire-old-systems.
# Everything else on userdata is kept.
RETIRE = ['/realdata/app', '/realdata/dalvik-cache', '/realdata/data', '/realdata/app-lib',
          '/realdata/local/tmp/dalvik-cache', '/realdata/local/tmp/rayo81-abi-lab',
          '/realdata/local/tmp/rayo81-abi-lab.tar.gz', '/realdata/local/tmp/rayo81-mount-check',
          '/realdata/local/tmp/rayo-graphics-delta', '/realdata/local/tmp/rayo-keylayout-candidate',
          '/realdata/local/tmp/RayoDark.apk', '/realdata/local/tmp/bootanimation-gabrieli-v03.zip',
          '/realdata/local/tmp/bootanimation-gabrieli.zip', '/realdata/local/tmp/rayo-bootanimation-oreo.zip',
          '/realdata/local/tmp/hwcomposer.rayo_adapter.so', '/realdata/local/tmp/hwcomposer.rk30board.factory.so',
          '/realdata/local/tmp/rayo-fb-read', '/realdata/local/tmp/rayo-kernel-capabilities',
          '/realdata/local/tmp/rayo-kernel-probe', '/realdata/local/tmp/rayo-stock-return.png',
          '/realdata/local/tmp/rayo-visible-test.png', '/realdata/local/tmp/rayo81-gpu.strace',
          '/realdata/local/tmp/rayo_hwbinder-test.ko', '/realdata/local/tmp/rayo_hwbinder_probe',
          '/realdata/local/tmp/trial-vendor-manifest.xml', '/realdata/local/tmp/prepare-live-overlay-stock.sh']
# Old trial copies inside the rescue's data (its /data/local/tmp); rayo-live is its overlay and stays.
RETIRE_GLOBS = ['/realdata/rayo81-trial/data/local/tmp/rayo12-framework-[0-9a-f]*',
                '/realdata/rayo81-trial/data/local/tmp/rayo12-recovery-repair-*']
KEEP = ('/realdata/local/tmp/rayo12-framework-system.raw.img', '/realdata/local/tmp/rayo81-system.raw.img',
        '/realdata/rayo81-trial/data/local/tmp/rayo-live', RESCUE, DATA)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--grow-data', action='store_true',
                        help='Accept a candidate built for a larger data image and grow the device file')
    parser.add_argument('--retire-old-systems', action='store_true',
                        help='Delete the factory 7.1.2 user data and trial leftovers (see above)')
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
        # Android 12 init writes the recovery command to misc before rebooting
        # and cancels the reboot when its fstab has no misc entry (lite-03 and
        # earlier). Provide one for this boot only; the rescue clears it.
        fstab = checked('shell', 'cat', '/vendor/etc/fstab.rk30board')
        if ' /misc ' not in fstab:
            temporary = '/data/local/tmp/fstab.rk30board'
            checked('shell', 'printf "%s\n%s\n" "$(cat /vendor/etc/fstab.rk30board)" "'+BASE+'misc /misc emmc defaults defaults" > '+temporary)
            checked('shell', 'chmod 644 '+temporary+'; chcon u:object_r:vendor_configs_file:s0 '+temporary+'; mount -o bind '+temporary+' /vendor/etc/fstab.rk30board')
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
    # Clear the one-shot recovery command so later normal reboots start GabOS.
    bcb = command('exec-out', '/sbin/busybox', 'head', '-c', '32', BASE+'misc').stdout
    if bcb.rstrip(bytes(1)) == b'boot-recovery':
        checked('shell', '/sbin/busybox', 'dd', 'if=/dev/zero', 'of='+BASE+'misc', 'bs=2048', 'count=1', 'conv=notrunc')
        checked('shell', '/sbin/busybox', 'sync')
        bcb = command('exec-out', '/sbin/busybox', 'head', '-c', '32', BASE+'misc').stdout
    if len(bcb) != 32 or any(bcb):
        raise SystemExit('Unexpected bootloader message in misc; inspect before updating')
    current_boot, current_recovery = md5(BASE+'boot'), md5(BASE+'recovery')
    if current_recovery != V14 or current_boot not in known_boots:
        raise SystemExit('Not the installed layout (boot %s, recovery %s)' % (current_boot, current_recovery))
    mapped = command('shell', '/sbin/busybox cat /sys/block/loop*/loop/backing_file 2>/dev/null; true').stdout
    if b'rayo12-framework' in mapped:
        raise SystemExit('GabOS images are mapped; this is not the rescue system')
    superblock = command('exec-out', '/sbin/busybox dd if='+DATA+' bs=1024 skip=1 count=1 2>/dev/null')
    data_now = int(checked('shell', '/system/bin/toybox', 'stat', '-c', '%s', DATA))
    data_new = manifest['data']['bytes']
    if (len(superblock.stdout) != 1024 or superblock.stdout[104:120] != uuid.UUID(manifest['data']['uuid']).bytes
            or not (data_now == data_new or (args.grow_data and data_now < data_new))):
        raise SystemExit('Device private data does not match the candidate (device %d, candidate %d bytes)'
                         % (data_now, data_new))
    for keep in KEEP:
        if any(keep == path or keep.startswith(path+'/') for path in RETIRE):
            raise SystemExit('Retire list would remove '+keep)
    retire = []
    if args.retire_old_systems:
        for pattern in RETIRE+RETIRE_GLOBS:
            listing = command('shell', '/sbin/busybox sh -c \'for p in '+pattern+'; do /sbin/busybox test -e "$p" && '
                              '/sbin/busybox du -sk "$p"; done; true\'', timeout=300)
            for line in listing.stdout.decode(errors='replace').splitlines():
                parts = line.split(None, 1)
                if len(parts) == 2 and parts[0].isdigit() and parts[1].startswith('/realdata/'):
                    if any(keep == parts[1] or keep.startswith(parts[1]+'/') for keep in KEEP):
                        raise SystemExit('Retire match would remove '+parts[1])
                    retire.append((parts[1], int(parts[0])*1024))
    old_bytes = int(checked('shell', '/system/bin/toybox', 'stat', '-c', '%s', SYSTEM))
    available = int(checked('shell', 'df', '-k', '/realdata').splitlines()[-1].split()[3])*1024
    retired = sum(size for _, size in retire)
    # The 768 MiB userdata reserve is kept after the system file and the data growth.
    needed = system_image.stat().st_size+(data_new-data_now)+768*MIB
    print(json.dumps({'boot_now': current_boot, 'boot_new': boot_md5, 'system_new_md5': system_md5,
                      'available_mib': available//MIB, 'freed_mib': old_bytes//MIB, 'retired_mib': retired//MIB,
                      'data_now_mib': data_now//MIB, 'data_new_mib': data_new//MIB, 'needed_mib': needed//MIB,
                      'retire': [path for path, _ in retire], 'execute': args.execute}, indent=2), flush=True)
    if available+old_bytes+retired < needed:
        raise SystemExit('Insufficient physical space with the reserve')
    if not args.execute:
        return
    for path, _ in retire:
        checked('shell', '/sbin/busybox', 'rm', '-rf', path, timeout=300)
    if retire:
        checked('shell', '/sbin/busybox', 'sync')
        print('Removed %d MiB of old system data.' % (retired//MIB), flush=True)
    if data_new > data_now:
        # GabOS is not running, so its data file is not mounted. Grow and check it in place.
        fsck = command('shell', '/system/bin/e2fsck', '-fy', DATA, timeout=900)
        if fsck.returncode not in (0, 1):
            raise RuntimeError('Data check failed before growth: '+fsck.stdout.decode(errors='replace')[-400:])
        checked('shell', '/system/bin/truncate', '-s', str(data_new), DATA)
        checked('shell', '/system/bin/resize2fs', DATA, timeout=900)
        checked('shell', '/system/bin/e2fsck', '-fn', DATA, timeout=900)
        grown = command('exec-out', '/sbin/busybox dd if='+DATA+' bs=1024 skip=1 count=1 2>/dev/null').stdout
        if (int(checked('shell', '/system/bin/toybox', 'stat', '-c', '%s', DATA)) != data_new
                or grown[104:120] != uuid.UUID(manifest['data']['uuid']).bytes):
            raise RuntimeError('Data growth check failed; boot not changed')
        print('Private data grown to %d MiB.' % (data_new//MIB), flush=True)
    logs = ROOT/'local/android12-promotion'/time.strftime('%Y%m%d-%H%M%S')
    logs.mkdir(parents=True)
    result = {'action': 'update', 'candidate': str(candidate), 'previous_boot_md5': current_boot,
              'boot_image_md5': boot_md5, 'system_md5': system_md5, 'data_bytes': data_new,
              'retired': [path for path, _ in retire]}
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
