#!/usr/bin/env python3
"""Prepare an official Google TV 12 image for offline inspection only. No device access."""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

from importlib.util import module_from_spec, spec_from_file_location

ROOT = Path(__file__).resolve().parents[1]
spec = spec_from_file_location('reference_inspector', ROOT/'tools/inspect-google-tv-reference.py')
inspector = module_from_spec(spec)
spec.loader.exec_module(inspector)
APKS = {
    'TVLauncherXStablePrebuilt.apk': ('product', '/priv-app/TVLauncherXStablePrebuilt/TVLauncherXStablePrebuilt.apk'),
    'PrebuiltGmsCorePano.apk': ('product', '/priv-app/PrebuiltGmsCore/PrebuiltGmsCorePano.apk'),
    'KatnissXPrebuilt.apk': ('product', '/priv-app/KatnissXPrebuilt/KatnissXPrebuilt.apk'),
    'SetupWraithXPrebuilt.apk': ('product', '/priv-app/SetupWraithXPrebuilt/SetupWraithXPrebuilt.apk'),
    'TvSettingsTwoPanel.apk': ('system', '/system/system_ext/priv-app/TvSettingsTwoPanel/TvSettingsTwoPanel.apk'),
    'GoogleServicesFramework.apk': ('system', '/system/system_ext/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk'),
    'SystemUI.apk': ('system', '/system/system_ext/priv-app/SystemUI/SystemUI.apk'),
}


def linux_path(path):
    absolute = path.resolve()
    if os.name != 'nt':
        return str(absolute)
    return '/mnt/' + absolute.drive[0].lower() + absolute.as_posix()[2:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true', help='Download the official 784 MB reference if absent')
    parser.add_argument('--wsl-distro', default='Ubuntu-24.04')
    args = parser.parse_args()
    directory = ROOT/'local/google-tv-reference'
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory/'adt3-google-tv-android12.zip'
    if not archive.exists():
        if not args.download:
            parser.error('Reference absent; use --download or copy the verified archive into local/')
        partial = archive.with_suffix('.partial')
        with urllib.request.urlopen(inspector.URL, timeout=60) as src, partial.open('wb') as dst:
            shutil.copyfileobj(src, dst, 8 * 1024 * 1024)
        if inspector.digest(partial) != inspector.SHA256:
            raise SystemExit('Official image hash mismatch; download was not accepted')
        partial.replace(archive)
    if inspector.digest(archive) != inspector.SHA256:
        raise SystemExit('Official reference archive hash mismatch')
    inner = directory/'reference-images.zip'
    if not inner.exists():
        with zipfile.ZipFile(archive) as outer:
            name = next(n for n in outer.namelist() if Path(n).name.startswith('image-') and n.endswith('.zip'))
            with outer.open(name) as src, inner.open('wb') as dst:
                shutil.copyfileobj(src, dst, 8 * 1024 * 1024)
    partitions = directory/'partitions'
    partitions.mkdir(exist_ok=True)
    prefix = ['wsl', '-d', args.wsl_distro, '--'] if os.name == 'nt' else []
    with zipfile.ZipFile(inner) as images:
        for name in ('system', 'product', 'vendor'):
            sparse = partitions/(name+'.img')
            if not sparse.exists():
                with images.open(name+'.img') as src, sparse.open('wb') as dst:
                    shutil.copyfileobj(src, dst, 8 * 1024 * 1024)
            raw = partitions/(name+'.raw.img')
            if not raw.exists():
                subprocess.run(prefix+['simg2img', linux_path(sparse), linux_path(raw)], check=True)
    for filename, (partition, source) in APKS.items():
        destination = directory/'apks'/filename
        destination.parent.mkdir(exist_ok=True)
        subprocess.run(prefix+['debugfs', '-R', f'dump {source} "{linux_path(destination)}"',
                              linux_path(partitions/(partition+'.raw.img'))], check=True)
        if not destination.is_file() or destination.stat().st_size < 1024:
            raise SystemExit('Expected APK was not extracted: '+filename)
    for source in ('/etc/permissions/privapp-permissions-atv-amati-product.xml',
                   '/etc/permissions/privapp-permissions-atv-product.xml',
                   '/etc/sysconfig/amati_experience.xml'):
        destination = directory/'config'/Path(source).name
        destination.parent.mkdir(exist_ok=True)
        subprocess.run(prefix+['debugfs', '-R', f'dump {source} "{linux_path(destination)}"',
                              linux_path(partitions/'product.raw.img')], check=True)
    properties = subprocess.check_output(prefix+['debugfs', '-R', 'cat /build.prop',
                                                linux_path(partitions/'vendor.raw.img')], text=True)
    values = dict(line.split('=', 1) for line in properties.splitlines()
                  if '=' in line and not line.lstrip().startswith('#'))
    descriptor = {'reference_only': True, 'archive_sha256': inspector.SHA256,
                  'vendor_cpu_abis': values.get('ro.vendor.product.cpu.abilist'),
                  'vendor_sdk': values.get('ro.vendor.build.version.sdk'),
                  'vendor_fingerprint': values.get('ro.vendor.build.fingerprint'),
                  'vendor_board': values.get('ro.board.platform')}
    if descriptor['vendor_sdk'] != '31' or descriptor['vendor_cpu_abis'] != 'armeabi-v7a,armeabi':
        raise SystemExit('Unexpected reference vendor properties')
    (directory/'reference-properties.json').write_text(json.dumps(descriptor, indent=2)+'\n', encoding='utf-8')
    print('Reference verified and prepared; no boot images/scripts were extracted or executed.')
    print('Run tools/inspect-google-tv-reference.py for package/signature metadata.')


if __name__ == '__main__':
    main()
