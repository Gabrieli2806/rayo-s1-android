#!/usr/bin/env python3
"""Seed only this unit's existing v14 adapter identity in isolated GabOS data."""
import configparser
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']


def command(*parts):
    result = subprocess.run(prefix+list(parts), capture_output=True, timeout=30)
    if result.returncode:
        raise SystemExit('Device operation failed; private adapter data withheld')
    return result.stdout


def main():
    if (command('shell', 'getprop', 'ro.gabos.name').strip() != b'GabOS'
            or command('shell', 'getprop', 'ro.build.version.release').strip() != b'12'):
        raise SystemExit('Requires isolated GabOS Android 12')
    mounts = command('shell', 'cat', '/proc/mounts').decode()
    if not any(line.startswith('/dev/block/loop6 /data ') for line in mounts.splitlines()):
        raise SystemExit('Private trial data is not mounted')
    text = command('exec-out', 'cat', '/realdata/rayo81-trial/data/misc/bluedroid/bt_config.conf').decode()
    config = configparser.ConfigParser(strict=False)
    config.read_string(text)
    address = config.get('Adapter', 'Address', fallback='').strip()
    if not re.fullmatch(r'[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}', address) or address.lower() in ('00:00:00:00:00:00', 'ff:ff:ff:ff:ff:ff'):
        raise SystemExit('No valid existing adapter identity; none generated')
    private = ROOT/'local/secrets/gabos-bt-address.txt'
    private.parent.mkdir(parents=True, exist_ok=True)
    private.write_text(address, encoding='ascii')
    remote = '/data/misc/bluetooth/bdaddr'
    command('push', str(private), remote)
    command('shell', 'chown', 'bluetooth:bluetooth', remote)
    command('shell', 'chmod', '0600', remote)
    command('shell', 'restorecon', remote)
    if command('exec-out', 'cat', remote).decode() != address:
        raise SystemExit('Adapter identity readback differs')
    print('Reused and verified the same unit adapter identity; no address printed or generated.')


if __name__ == '__main__':
    main()
