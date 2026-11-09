#!/usr/bin/env python3
"""Collect read-only diagnostics from the known Rayo unit into ignored local storage."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
import argparse
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
KNOWN = {'3GKL2KGCA400000000': '7.1.2', '0123456789ABCDEF': '8.1.0'}

QUERIES = {
    'identity': ['id'],
    'kernel': ['uname', '-a'],
    'cpu': ['cat', '/proc/cpuinfo'],
    'memory': ['cat', '/proc/meminfo'],
    'memory-summary': ['dumpsys', 'meminfo'],
    'processes': ['ps', '-A'],
    'features': ['pm', 'list', 'features'],
    'services': ['service', 'list'],
    'bluetooth-manager': ['dumpsys', 'bluetooth_manager'],
    'bluetooth-package': ['dumpsys', 'package', 'com.android.bluetooth'],
    'audio': ['dumpsys', 'audio'],
    'audio-flinger': ['dumpsys', 'media.audio_flinger'],
    'audio-policy': ['dumpsys', 'media.audio_policy'],
    'media-codec': ['dumpsys', 'media.codec'],
    'media-player': ['dumpsys', 'media.player'],
    'battery': ['dumpsys', 'battery'],
    'power': ['dumpsys', 'power'],
    'input': ['dumpsys', 'input'],
    'display': ['dumpsys', 'display'],
    'surfaceflinger': ['dumpsys', 'SurfaceFlinger'],
    'mounts': ['cat', '/proc/mounts'],
    'filesystems': ['cat', '/proc/filesystems'],
    'kernel-config': ['ls', '-l', '/proc/config.gz'],
    'bluetooth-uart': ['ls', '-l', '/dev/ttyS0', '/dev/ttyS1'],
    'bluetooth-vendor-config': ['cat', '/system/etc/bluetooth/bt_vendor.conf'],
    'media-codecs-config': ['cat', '/system/etc/media_codecs.xml'],
}
PROPERTIES = [
    'ro.build.version.release', 'ro.build.version.sdk', 'ro.rayo.trial',
    'sys.boot_completed', 'ro.config.low_ram', 'ro.opengles.version',
    'ro.product.cpu.abi', 'ro.bt.bdaddr_path', 'config.disable_bluetooth',
    'init.svc.bluetooth', 'init.svc.audioserver', 'init.svc.media',
    'init.svc.vendor.bluetooth-1-0', 'debug.sf.no_hw_vsync',
]


def run(adb, serial, command, timeout=20):
    try:
        result = subprocess.run([adb, '-s', serial, 'shell']+command,
                                capture_output=True, timeout=timeout)
        return result.returncode, result.stdout+result.stderr
    except subprocess.TimeoutExpired as error:
        return 124, (error.stdout or b'')+(error.stderr or b'')+b'\nQUERY_TIMEOUT\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--label', choices=['baseline', 'trial', 'after-change'], default='baseline')
    args = parser.parse_args()
    if not args.adb:
        parser.error('Install Android Platform Tools or supply --adb')
    devices = subprocess.check_output([args.adb, 'devices'], text=True)
    connected = [line.split()[0] for line in devices.splitlines()[1:]
                 if len(line.split()) >= 2 and line.split()[0] in KNOWN
                 and line.split()[1] in ('device', 'recovery')]
    if len(connected) != 1:
        raise SystemExit('Exactly one known Rayo session must be connected and authorized')
    serial = connected[0]
    code, data = run(args.adb, serial, ['getprop', 'ro.build.version.release'])
    version = data.decode(errors='replace').strip()
    if code or version != KNOWN[serial]:
        raise SystemExit('Unexpected Android/serial combination; no diagnostics collected')
    if version == '8.1.0':
        code, trial = run(args.adb, serial, ['getprop', 'ro.rayo.trial'])
        if code or trial.strip() != b'1':
            raise SystemExit('Missing expected trial identity')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    destination = ROOT/'local/device-validation'/(stamp+'-'+args.label+'-'+version)
    destination.mkdir(parents=True, exist_ok=False)
    results = {}

    def collect(item):
        name, command = item
        code, output = run(args.adb, serial, command)
        (destination/(name+'.txt')).write_bytes(output)
        return name, {'exit_code': code, 'bytes': len(output)}

    with ThreadPoolExecutor(max_workers=3) as pool:
        for name, result in pool.map(collect, QUERIES.items()):
            results[name] = result
    properties = {}
    for name in PROPERTIES:
        code, data = run(args.adb, serial, ['getprop', name])
        properties[name] = {'exit_code': code, 'value': data.decode(errors='replace').strip()}
    metadata = {'date_utc': stamp, 'android': version, 'label': args.label,
                'queries': results, 'properties': properties,
                'scope': 'Read-only snapshots; physical functionality not established',
                'privacy': 'Raw dumps can contain device addresses, app names, and account metadata. Keep local.'}
    (destination/'summary.json').write_text(json.dumps(metadata, indent=2)+'\n', encoding='utf-8')
    print('Android:', version)
    print('Diagnostic directory:', destination)
    print('Queries:', len(results), 'Timeouts:', sum(v['exit_code'] == 124 for v in results.values()))
    print('No device settings, partitions, or boot state changed.')


if __name__ == '__main__':
    main()
