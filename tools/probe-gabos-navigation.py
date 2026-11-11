#!/usr/bin/env python3
"""Bounded navigation check inside an already booted, isolated GabOS trial."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']

    def shell(*parts):
        return subprocess.check_output(prefix+['shell']+list(parts), timeout=20).decode(errors='replace')

    if shell('getprop', 'ro.gabos.name').strip() != 'GabOS' or shell('getprop', 'ro.build.version.release').strip() != '12':
        raise SystemExit('Requires the booted GabOS Android 12 trial')
    if not any(line.startswith('/dev/block/loop6 /data ') for line in shell('cat', '/proc/mounts').splitlines()):
        raise SystemExit('Private trial data is not mounted')
    if not args.execute:
        print('Plan only: five Home/Settings/Back cycles; no partition changes.')
        return
    before = shell('pidof', 'system_server').strip()
    observations = []

    def focus(package):
        deadline = time.monotonic()+12
        while time.monotonic() < deadline:
            windows = shell('dumpsys', 'window', 'displays')
            match = next((line.strip() for line in windows.splitlines()
                          if 'mCurrentFocus=' in line and package in line and 'FallbackHome' not in line), None)
            if match:
                return match
            time.sleep(.5)
        raise RuntimeError('Expected focused window did not appear: '+package)

    error = None
    try:
        shell('am', 'start', '-W', '-n', 'local.rayo.launcher/.MainActivity')
        focus('local.rayo.launcher')
        for number in range(1, 6):
            # Use the actual Home button, preserving the normal activity stack.
            # Shell am start uses NEW_TASK and can resurrect old diagnostic tasks.
            if '1280x720' not in shell('wm', 'size'):
                raise RuntimeError('Unexpected UI size for the observed Home button')
            shell('input', 'tap', '165', '300')
            settings = focus('com.android.tv.settings')
            shell('input', 'keyevent', '4')
            home = focus('local.rayo.launcher')
            if shell('pidof', 'system_server').strip() != before:
                raise RuntimeError('system_server restarted')
            observations.append({'cycle': number, 'settings': settings, 'home': home})
            print('Navigation cycle passed:', number, flush=True)
    except Exception as exc:
        error = str(exc)
    output = ROOT/'local/google-tv-reference'/('gabos-navigation-'+time.strftime('%Y%m%d-%H%M%S')+'.json')
    output.write_text(json.dumps({'cycles': observations, 'system_server_pid': before,
                                 'passed': len(observations) == 5 and error is None,
                                 'error': error}, indent=2)+'\n', encoding='utf-8')
    if error:
        raise SystemExit(error)


if __name__ == '__main__':
    main()
