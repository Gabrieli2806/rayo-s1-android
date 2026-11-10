#!/usr/bin/env python3
"""Package real AOSP12 Cortex-A7 runtime outputs, never interface stubs or a ROM."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out-dir', required=True, type=Path, help='Native laboratory OUT_DIR')
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', '')))
    args = parser.parse_args()
    variables = json.loads((args.out_dir/'soong/soong.variables').read_text(encoding='utf-8'))
    expected = {'DeviceName': 'rayos1_12_lab', 'DeviceArch': 'arm',
                'DeviceArchVariant': 'armv7-a-neon', 'DeviceCpuVariant': 'cortex-a7',
                'Platform_sdk_version': 31, 'Binder32bit': True}
    for name, value in expected.items():
        if variables.get(name) != value:
            raise SystemExit('Unexpected build configuration: '+name)
    product = args.out_dir/'target/product/rayos1_12_lab'
    runtime = product/'system/apex/com.android.runtime'
    sources = {'bin/linker': runtime/'bin/linker',
               'bin/rayo12-bootstrap-probe': product/'system/bin/rayo12_bootstrap_probe'}
    for name in ('libc.so', 'libm.so', 'libdl.so', 'libdl_android.so'):
        sources['lib/'+name] = runtime/'lib/bionic'/name
    bundle = ROOT/'outputs/android12-native-built'
    readelf = args.sdk/'ndk/25.2.9519653/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
    if not readelf.is_file():
        raise SystemExit('Recorded Windows NDK readelf is required')
    records = []
    for relative, source in sources.items():
        data = source.read_bytes()
        if data[:6] != b'\x7fELF\x01\x01' or struct.unpack_from('<H', data, 18)[0] != 40:
            raise SystemExit('Expected little-endian ARM32 output: '+str(source))
        if relative == 'lib/libc.so' and len(data) < 500000:
            raise SystemExit('libc appears to be an interface stub, not the real APEX runtime')
        attributes = subprocess.check_output([str(readelf), '--arch-specific', str(source)], text=True)
        if 'Description: ARM v8' in attributes:
            raise SystemExit('Unexpected ARMv8 baseline in Cortex-A7 output: '+relative)
        destination = bundle/relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        records.append({'path': relative, 'bytes': len(data),
                        'sha256': hashlib.sha256(data).hexdigest(), 'md5': hashlib.md5(data).hexdigest(),
                        'cpu_arch': re.findall(r'Description: (ARM v\d+)', attributes)})
    manifest = {
        'purpose': 'Isolated native process only; not a ROM, APEX update, or boot image',
        'runtime_origin': 'Source-built AOSP android-12.0.0_r34 for Cortex-A7',
        'build_variables': expected,
        'probe_source_sha256': hashlib.sha256((ROOT/'work/android12/device/canon/rayos1_12_lab/bootstrap_probe.c').read_bytes()).hexdigest(),
        'files': records, 'partition_writes': False,
        'device_writes_allowed': 'Fresh /data/local/tmp/rayo12-native-* directory only',
    }
    (bundle/'MANIFEST.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
    print('Packaged real Android 12 Cortex-A7 runtime and probe:', bundle)
    print('Not uploaded or executed on the device.')


if __name__ == '__main__':
    main()
