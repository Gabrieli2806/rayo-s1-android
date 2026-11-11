#!/usr/bin/env python3
"""Reuse only the connected v14 WPA2 profile privately in the isolated GabOS trial."""
import argparse
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT/'local/secrets/gabos-wifi-migration.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--capture', action='store_true')
    group.add_argument('--connect', action='store_true')
    args = parser.parse_args()
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']

    def shell(*parts):
        result = subprocess.run(prefix+['shell', ' '.join(shlex.quote(part) for part in parts)],
                                capture_output=True, timeout=40)
        if result.returncode:
            raise RuntimeError('Device operation failed; credential-bearing output was withheld')
        return result.stdout.decode(errors='replace')

    def unquote(value):
        return value[1:-1] if value.startswith('"') and value.endswith('"') else value

    version = shell('getprop', 'ro.build.version.release').strip()
    if args.capture:
        if version != '8.1.0' or shell('getprop', 'sys.boot_completed').strip() != '1':
            raise SystemExit('Capture requires the completed normal v14 boot')
        status = shell('dumpsys', 'wifi')
        current = re.search(r'mWifiInfo.*?SSID: (.*?), BSSID:', status)
        if not current:
            raise SystemExit('Current Wi-Fi network could not be identified; no profile captured')
        ssid = unquote(current[1].strip())
        config = ET.fromstring(shell('cat', '/data/misc/wifi/WifiConfigStore.xml'))
        matches = []
        for network in config.findall('.//Network'):
            values = {node.get('name'): node.text or '' for node in network.findall('./WifiConfiguration/*')}
            password = values.get('PreSharedKey', '')
            if unquote(values.get('SSID', '')) == ssid and password.startswith('"') and password.endswith('"'):
                password = unquote(password)
                if 8 <= len(password.encode()) <= 63:
                    matches.append({'ssid': ssid, 'passphrase': password, 'source': 'connected-normal-v14'})
        if len(matches) != 1:
            raise SystemExit('No unique supported WPA2 profile; nothing captured')
        PRIVATE.parent.mkdir(parents=True, exist_ok=True)
        PRIVATE.write_text(json.dumps(matches[0]), encoding='utf-8')
        print('Captured one connected WPA2 profile privately; no credentials printed.')
    else:
        if version != '12' or shell('getprop', 'ro.gabos.name').strip() != 'GabOS':
            raise SystemExit('Connect requires the isolated GabOS Android 12 trial')
        if not any(line.startswith('/dev/block/loop6 /data ') for line in shell('cat', '/proc/mounts').splitlines()):
            raise SystemExit('Private GabOS data is not mounted')
        profile = json.loads(PRIVATE.read_text(encoding='utf-8'))
        if profile.get('source') != 'connected-normal-v14':
            raise SystemExit('Unexpected private migration record')
        shell('cmd', 'wifi', 'set-wifi-enabled', 'enabled')
        response = shell('cmd', 'wifi', 'connect-network', profile['ssid'], 'wpa2', profile['passphrase'])
        if any(word in response.lower() for word in ('failed', 'exception', 'unknown command')):
            raise SystemExit('Wi-Fi connection request failed; private response withheld')
        print('Submitted the existing WPA2 profile to GabOS; connection still requires verification.')


if __name__ == '__main__':
    main()
