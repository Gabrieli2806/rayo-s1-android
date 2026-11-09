#!/usr/bin/env python3
"""Inspect or explicitly promote the known v14 runtime, preserving the previous system partition."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
KIT = ROOT/'local/device-kit'
BOOT = '/dev/block/platform/1021c000.rksdmmc/by-name/boot'
RECOVERY = '/dev/block/platform/1021c000.rksdmmc/by-name/recovery'
OLD_BOOT = 'a42af2dbc934f2fc77bff14de619f116'
OLD_RECOVERY = 'b32f971e7dfce13c708d264c6221844d'
V14 = '7a3ac489b05f0f2e9cde16280551511a'
V14_SHA = '9e7c48594fae621e74a37ea4bd427fa3284f0ab578cbf8ff94187bb654278c2d'


def checked_file(relative, md5):
    path = KIT/relative
    data = path.read_bytes()
    if len(data) != 33554432 or hashlib.md5(data).hexdigest() != md5:
        raise SystemExit('Unexpected local image: '+relative)
    return path, data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--action', choices=['plan', 'install-experimental', 'restore-previous-boot'], default='plan')
    parser.add_argument('--accept-experimental', action='store_true',
                        help='Explicitly accept the documented incomplete stability validation')
    parser.add_argument('--execute', action='store_true', help='Explicitly execute a previous-boot restoration')
    args = parser.parse_args()
    adb = shutil.which('adb')
    if not adb:
        raise SystemExit('Install Android Platform Tools')
    devices = subprocess.check_output([adb, 'devices'], text=True)
    connected = [line.split()[0] for line in devices.splitlines()[1:]
                 if len(line.split()) >= 2 and line.split()[0] in ('0123456789ABCDEF', '3GKL2KGCA400000000')
                 and line.split()[1] in ('device', 'recovery')]
    if len(connected) != 1:
        raise SystemExit('Exactly one known, authorized Rayo session must be connected')
    prefix = [adb, '-s', connected[0]]

    def shell(*command):
        return subprocess.check_output(prefix+['shell']+list(command), text=True, timeout=180).strip()

    version = shell('getprop', 'ro.build.version.release')
    trial = version == '8.1.0' and shell('getprop', 'ro.rayo.trial') == '1'
    rescue = (args.action == 'restore-previous-boot' and connected[0] == '0123456789ABCDEF'
              and version in ('', '7.1.2') and shell('uname', '-r') == '3.10.104')
    if not trial and not rescue:
        raise SystemExit('Requires the identified 8.1 runtime or original recovery for restoration')
    if shell('id', '-u') != '0':
        raise SystemExit('Requires trial root')
    previous, old_data = checked_file('original/boot.img', OLD_BOOT)
    recovery, recovery_data = checked_file('original/recovery.img', OLD_RECOVERY)
    candidate, candidate_data = checked_file('boot/trial-recovery-v14.img', V14)
    if hashlib.sha256(candidate_data).hexdigest() != V14_SHA:
        raise SystemExit('Unexpected candidate SHA-256')
    before = struct.unpack_from('<10I', old_data, 8)
    after = struct.unpack_from('<10I', candidate_data, 8)
    if not all(before[i] == after[i] for i in (0, 1, 3, 5, 6, 7)):
        raise SystemExit('Kernel size, load addresses, or page size differ')
    page = before[7]
    if old_data[page:page+before[0]] != candidate_data[page:page+after[0]]:
        raise SystemExit('Factory kernel payload changed')
    boot_md5 = shell('md5sum', BOOT).split()[0]
    recovery_md5 = shell('md5sum', RECOVERY).split()[0]
    if rescue and recovery_md5 != OLD_RECOVERY:
        raise SystemExit('Rescue session does not have the verified original recovery')
    if boot_md5 not in (OLD_BOOT, V14) or recovery_md5 not in (OLD_RECOVERY, V14):
        raise SystemExit('Unexpected installed boot/recovery state')
    plan = {
        'status': 'Experimental; not a final stable release',
        'primary_boot_configured': boot_md5 == V14 and recovery_md5 == OLD_RECOVERY,
        'current_boot_md5': boot_md5, 'current_recovery_md5': recovery_md5,
        'candidate_sha256': V14_SHA,
        'install_writes': ['recovery: original independent recovery', 'boot: v14 normal-boot candidate'],
        'preserved': ['previous 7.1.2 system partition', 'kernel partition', 'bootloader', 'resource', 'userdata'],
        'runtime': 'Existing v14 file-backed system and private trial data; no wipe',
        'rollback': 'Restore the verified previous boot image; system remains on device',
        'pending': ['independent physical rescue entry',
                    'extended use', 'Bluetooth pairing', 'power/suspend/thermal validation'],
    }
    print(json.dumps(plan, indent=2), flush=True)
    if args.action == 'plan':
        return
    if args.action == 'install-experimental' and not args.accept_experimental:
        parser.error('Installation requires --accept-experimental after reviewing the plan')
    if args.action == 'restore-previous-boot' and not args.execute:
        parser.error('Restoration requires explicit --execute')

    def write_image(path, target, expected):
        remote = '/data/local/tmp/rayo-main-boot-operation.img'
        subprocess.run(prefix+['push', str(path), remote], check=True)
        if shell('md5sum', remote).split()[0] != expected:
            raise RuntimeError('Transfer MD5 mismatch')
        subprocess.run(prefix+['shell', '/sbin/busybox', 'dd', 'if='+remote,
                               'of='+target, 'bs=1048576', 'count=32'], check=True)
        shell('sync')
        if shell('md5sum', target).split()[0] != expected:
            raise RuntimeError('Partition readback mismatch')

    if args.action == 'restore-previous-boot':
        if boot_md5 != OLD_BOOT:
            write_image(previous, BOOT, OLD_BOOT)
        print('Previous boot restored and verified. No reboot requested.')
        return

    descriptor = json.loads((ROOT/'manifests/previous-main-backup.json').read_text(encoding='utf-8'))
    release = json.loads(subprocess.check_output([
        'gh', 'release', 'view', descriptor['release'], '--repo', 'Gabrieli2806/rayo-s1-android',
        '--json', 'isDraft,assets'], text=True))
    asset = next((a for a in release['assets'] if a['name'] == descriptor['asset']), None)
    if release['isDraft'] or not asset or asset['digest'] != 'sha256:'+descriptor['sha256']:
        raise SystemExit('Previous-main backup is not verified as published')
    for path, expected in {
        '/system/lib/libsurfaceflinger.so': 'ab536d6a6826341488f51b65ee0e31c0',
        '/system/lib/hw/hwcomposer.rk30board.so': '5b414cb2fa1666325aa1a6d10f2f0015',
        '/vendor/lib/libbt-vendor.so': 'a098a2c5f41758f00198e25fe6550c1a',
        '/system/etc/media_codecs.xml': '0014166bd070664d7df9da0f4016c61a',
    }.items():
        if shell('md5sum', path).split()[0] != expected:
            raise SystemExit('Runtime checkpoint mismatch: '+path)
    if recovery_md5 != OLD_RECOVERY:
        write_image(recovery, RECOVERY, OLD_RECOVERY)
    try:
        if boot_md5 != V14:
            write_image(candidate, BOOT, V14)
    except Exception:
        print('Boot write failed; attempting immediate previous-boot restoration.', flush=True)
        write_image(previous, BOOT, OLD_BOOT)
        raise
    print('Boot and independent recovery written/read back. Requesting normal boot.', flush=True)
    subprocess.run(prefix+['shell', 'setprop', 'sys.powerctl', 'reboot'], check=True)


if __name__ == '__main__':
    main()
