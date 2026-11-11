#!/usr/bin/env python3
"""Record the separate source-built radio candidate; no device access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--product-out', type=Path, required=True)
    args = parser.parse_args()
    product = args.product_out
    variables = json.loads((product.parents[2]/'soong/soong.variables').read_text())
    for key, value in {'DeviceName': 'rayos1_tv12_radios', 'DeviceArch': 'arm',
                       'DeviceCpuVariant': 'cortex-a7', 'Binder32bit': True,
                       'Platform_sdk_version': 31}.items():
        if variables.get(key) != value:
            raise SystemExit('Unexpected radio build configuration: '+key)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'AppData/Local/Android/Sdk')))
    readelf = sdk/'ndk/25.2.9519653/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
    paths = ['bin/hw/android.hardware.wifi@1.0-service', 'bin/hw/wpa_supplicant',
             'bin/hw/android.hardware.bluetooth@1.0-service',
             'lib/hw/android.hardware.bluetooth@1.0-impl.so', 'lib/libwifi-hal.so', 'lib/libtg.so']
    records = []
    for relative in paths:
        path = product/'system/vendor'/relative
        data = path.read_bytes()
        if data[:6] != b'\x7fELF\x01\x01' or int.from_bytes(data[18:20], 'little') != 40:
            raise SystemExit('Expected ARM32 ELF: '+relative)
        if relative.endswith('bluetooth@1.0-impl.so') and b'ro.rayo.bluetooth.legacy_vendor_sphal' not in data:
            raise SystemExit('Missing opt-in Bluetooth namespace adapter')
        if relative.endswith('libwifi-hal.so') and b'/system/etc/firmware/fw_RK903_ag.bin' not in data:
            raise SystemExit('Unexpected station firmware selection')
        if relative.endswith('libtg.so'):
            symbols = subprocess.check_output([str(readelf), '--dyn-syms', str(path)], text=True)
            if not re.search(r'GLOBAL\s+DEFAULT\s+\d+\s+rayo_timerset\b', symbols):
                raise SystemExit('Missing exported Bluetooth timer helper')
        dynamic = subprocess.check_output([str(readelf), '-d', str(path)], text=True)
        records.append({'path': relative, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                        'needed': re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)})
    output = ROOT/'outputs/android12-radios/SOURCE-BUILD.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'device_written': False, 'source_build_verified': True,
        'hardware_validated': False, 'files': records}, indent=2)+'\n', encoding='utf-8')
    print('Verified ARM32 source radio artifacts:', len(records))
    print('Hardware and final namespace/init/firmware integration remain unvalidated.')


if __name__ == '__main__':
    main()
