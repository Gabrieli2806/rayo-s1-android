#!/usr/bin/env python3
"""Inspect APK metadata from a verified official ADT-3 reference. Never install or flash."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
URL = ('https://dl.google.com/developers/android/sc/images/factory/'
       'adt3_gtv-stt1.211025.001.z3-factory-91f7efe7.zip')
SHA256 = '91f7efe7cccfc507a80366c0edb32991df931620ecfc7d2aa765e7b233dc01f1'
EXPECTED = {
    'TVLauncherXStablePrebuilt.apk': 'com.google.android.apps.tv.launcherx',
    'PrebuiltGmsCorePano.apk': 'com.google.android.gms',
    'KatnissXPrebuilt.apk': 'com.google.android.katniss',
    'SetupWraithXPrebuilt.apk': 'com.google.android.tungsten.setupwraith',
    'TvSettingsTwoPanel.apk': 'com.android.tv.settings',
    'GoogleServicesFramework.apk': 'com.google.android.gsf',
    'SystemUI.apk': 'com.android.systemui',
}


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(8 * 1024 * 1024):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, default=ROOT/'local/google-tv-reference')
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get(
        'ANDROID_HOME', str(Path(os.environ.get('LOCALAPPDATA', ''))/'Android/Sdk'))))
    parser.add_argument('--build-tools', default='36.0.0')
    parser.add_argument('--output', type=Path, default=ROOT/'local/google-tv-reference/component-audit.json')
    args = parser.parse_args()
    archive = args.reference/'adt3-google-tv-android12.zip'
    if digest(archive) != SHA256:
        raise SystemExit('Official reference archive checksum mismatch')
    tools = args.sdk/'build-tools'/args.build_tools
    aapt = tools/('aapt.exe' if os.name == 'nt' else 'aapt')
    signer = tools/('apksigner.bat' if os.name == 'nt' else 'apksigner')
    permission_file = args.reference/'config/privapp-permissions-atv-amati-product.xml'
    permissions = {}
    for node in ET.parse(permission_file).getroot().findall('privapp-permissions'):
        permissions[node.attrib['package']] = [p.attrib['name'] for p in node.findall('permission')]
    records = []
    for filename, expected in EXPECTED.items():
        apk = args.reference/'apks'/filename
        text = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)],
                                       encoding='utf-8', errors='replace')
        header = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text, re.M)
        if not header or header[1] != expected:
            raise SystemExit('Unexpected reference package: ' + filename)
        cert = subprocess.check_output([str(signer), 'verify', '--print-certs', str(apk)],
                                       encoding='utf-8', errors='replace')
        with zipfile.ZipFile(apk) as package:
            native = [n for n in package.namelist() if n.startswith('lib/') and n.endswith('.so')]
        records.append({
            'file': filename, 'package': header[1], 'version_code': header[2], 'version_name': header[3],
            'bytes': apk.stat().st_size, 'sha256': digest(apk),
            'min_sdk': int(re.search(r"^sdkVersion:'(\d+)'", text, re.M)[1]),
            'target_sdk': int(re.search(r"^targetSdkVersion:'(\d+)'", text, re.M)[1]),
            'native_libraries': native,
            'required_features': re.findall(r"^[ \t]*uses-feature: name='([^']+)'", text, re.M),
            'optional_libraries': re.findall(r"^uses-library-not-required:'([^']+)'", text, re.M),
            'certificate_sha256': re.findall(r'^Signer #\d+ certificate SHA-256 digest: (\w+)', cert, re.M),
            'reference_privileged_allowlist': permissions.get(header[1], []),
        })
    result = {
        'purpose': 'Offline reference inventory only. Not an installation list, working ROM, or redistribution grant.',
        'source_page': 'https://developer.android.com/tv/release/12',
        'source_archive_url': URL, 'source_archive_sha256': SHA256,
        'reference': 'ADT-3 Google TV Android 12 STT1.211025.001.Z3; 2021 reference, not current Home updates',
        'hardware_compatible_with_canon': False,
        'inspected_packages': records,
        'unverified': ['Launcher runtime on Canon', 'Required service closure', 'Platform permission compatibility',
                       'Memory/latency', 'Accounts/recommendations availability', 'Android 12 board port'],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print('Verified APK signatures and inventoried packages:', len(records))
    print('Metadata report:', args.output)
    print('No packages installed or partitions written.')


if __name__ == '__main__':
    main()
