#!/usr/bin/env python3
"""Readonly per-attribute display/DLP inventory; never writes sysfs or device state."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess


def capture(prefix):
    result = {}
    paths = ['/proc/sys/kernel/random/boot_id', '/proc/uptime']
    paths += ['/sys/class/graphics/fb0/'+name for name in (
        'disp_info', 'screen_info', 'enable', 'state', 'blank', 'scale',
        'bits_per_pixel', 'stride', 'virtual_size', 'dsp_mode', 'win_property')]
    paths += ['/sys/devices/20072000.i2c/i2c-0/0-001b/'+name for name in (
        'panel_android_hdmi', 'panel_mode', 'panel_mode_select', 'panel_brightness',
        'panel_rotate', 'panel_power_check', 'prj_mode', 'panel_type', 'panel_autorotate_enable')]
    for path in paths:
        try:
            command = subprocess.run(prefix+['shell', '/sbin/busybox', 'cat', path],
                capture_output=True, timeout=5)
            result[path] = {'status': command.returncode,
                'value': command.stdout.decode(errors='replace').replace('\r', '').strip(),
                'error': command.stderr.decode(errors='replace').strip()}
        except subprocess.TimeoutExpired:
            result[path] = {'status': 124, 'error': 'Readonly query timed out'}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']
    args.output.write_text(json.dumps(capture(prefix), indent=2)+'\n')
    print('Readonly display snapshot saved:', args.output)


if __name__ == '__main__':
    main()
