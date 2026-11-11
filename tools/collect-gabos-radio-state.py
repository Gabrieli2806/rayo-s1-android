#!/usr/bin/env python3
"""Collect a credential/nearby-identity-free status snapshot from isolated GabOS."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--diagnostics', action='store_true', help='Include a fresh explicit helper run')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', args.tag):
        raise SystemExit('Invalid report tag')
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']

    def shell(*parts):
        result = subprocess.run(prefix+['shell']+list(parts), capture_output=True, timeout=30)
        return result.stdout.decode(errors='replace') if result.returncode == 0 else ''

    if shell('getprop', 'ro.gabos.name').strip() != 'GabOS' or shell('getprop', 'ro.build.version.release').strip() != '12':
        raise SystemExit('Requires the live GabOS Android 12 trial')
    if not any(line.startswith('/dev/block/loop6 /data ') for line in shell('cat', '/proc/mounts').splitlines()):
        raise SystemExit('Expected isolated data mount')
    wifi = shell('cmd', 'wifi', 'status')
    connectivity = shell('dumpsys', 'connectivity')
    active = re.search(r'Active default network:\s*(\d+)', connectivity)
    network = next((line for line in connectivity.splitlines()
                    if active and 'NetworkAgentInfo{network{'+active[1]+'}' in line), '')
    bluetooth = shell('dumpsys', 'bluetooth_manager')
    state = re.search(r'^\s*state:\s*(\S+)', bluetooth, re.M)
    crashes = re.search(r'Bluetooth crashed (\d+)', bluetooth)
    data = {'gabos_version': shell('getprop', 'ro.gabos.version').strip(),
            'boot_completed': shell('getprop', 'sys.boot_completed').strip() == '1',
            'system_server_pid': shell('pidof', 'system_server').strip(),
            'netd_pid': shell('pidof', 'netd').strip(),
            'wifi_enabled': 'Wifi is enabled' in wifi, 'wifi_associated': 'COMPLETED' in wifi,
            'wifi_default_validated': 'WIFI' in network and 'VALIDATED' in network,
            'bluetooth_state': state[1] if state else None,
            'bluetooth_crashes': int(crashes[1]) if crashes else None,
            'bluetooth_startup_timeout_ms': shell('getprop', 'bluetooth.enable_timeout_ms').strip(),
            'physical_projection_checked': False, 'pairing_checked': False}
    result = shell('cat', '/data/data/local.gabos.diagnostics/files/radio-result.json') if args.diagnostics else ''
    if result:
        raw = json.loads(result)
        data['diagnostics'] = {name: raw[name] for name in ('bluetooth_state', 'discovery_requested',
            'discovery_started', 'discovery_finished', 'devices_found', 'finished') if name in raw}
    output = ROOT/'local/google-tv-reference'/(args.tag+'-status.json')
    output.write_text(json.dumps(data, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(data, indent=2))


if __name__ == '__main__':
    main()
