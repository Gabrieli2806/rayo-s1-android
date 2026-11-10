#!/usr/bin/env python3
"""Reproduce the offline ADT-3 runtime audit; its ARMv8 core cannot run on Rayo's Cortex-A7."""
from pathlib import Path
from importlib.util import module_from_spec, spec_from_file_location
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = spec_from_file_location(name.replace('-', '_'), ROOT/'tools'/f'{name}.py')
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', '')))
    parser.add_argument('--wsl-distro', default='Ubuntu-24.04')
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('This recorded builder uses the Windows NDK and WSL image reader')
    reference = load('prepare-google-tv-reference')
    inspector = load('inspect-google-tv-reference')
    cache = ROOT/'local/google-tv-reference'
    if inspector.digest(cache/'adt3-google-tv-android12.zip') != inspector.SHA256:
        raise SystemExit('Official reference archive checksum mismatch')
    runtime = cache/'runtime'
    runtime.mkdir(exist_ok=True)
    apex = runtime/'com.android.runtime.apex'
    prefix = ['wsl', '-d', args.wsl_distro, '--']
    subprocess.run(prefix+['debugfs', '-R',
                          f'dump /system/apex/com.android.runtime.apex "{reference.linux_path(apex)}"',
                          reference.linux_path(cache/'partitions/system.raw.img')], check=True)
    payload = runtime/'apex_payload.img'
    with zipfile.ZipFile(apex) as package:
        with package.open('apex_payload.img') as src, payload.open('wb') as dst:
            shutil.copyfileobj(src, dst)
    bundle = ROOT/'outputs/android12-native-reference'
    for subdir in ('bin', 'lib'):
        (bundle/subdir).mkdir(parents=True, exist_ok=True)
    paths = ['bin/linker', 'lib/bionic/libc.so', 'lib/bionic/libm.so',
             'lib/bionic/libdl.so', 'lib/bionic/libdl_android.so']
    for source in paths:
        relative = 'bin/linker' if source == 'bin/linker' else 'lib/'+Path(source).name
        destination = bundle/relative
        subprocess.run(prefix+['debugfs', '-R',
                              f'dump /{source} "{reference.linux_path(destination)}"',
                              reference.linux_path(payload)], check=True)
    ndk = args.sdk/'ndk/25.2.9519653/toolchains/llvm/prebuilt/windows-x86_64'
    compiler = ndk/'bin/clang.exe'
    source = ROOT/'work/android12/device/canon/rayos1_12_lab/bootstrap_probe.c'
    executable = bundle/'bin/rayo12-bootstrap-probe'
    subprocess.run([str(compiler), '--target=armv7a-linux-androideabi31',
                    '--sysroot='+str(ndk/'sysroot'), '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-fPIE', '-pie', str(source), '-ldl', '-o', str(executable)], check=True)
    records = []
    for file in sorted([p for sub in ('bin', 'lib') for p in (bundle/sub).iterdir() if p.is_file()]):
        data = file.read_bytes()
        if data[:6] != b'\x7fELF\x01\x01' or struct.unpack_from('<H', data, 18)[0] != 40:
            raise SystemExit('Expected little-endian ARM32 ELF: '+str(file))
        records.append({'path': file.relative_to(bundle).as_posix(), 'bytes': len(data),
                        'sha256': hashlib.sha256(data).hexdigest(), 'md5': hashlib.md5(data).hexdigest()})
    descriptor = {
        'purpose': 'Isolated native process only. Not an OS installation, APEX update, or boot image.',
        'runtime_origin': 'Official ADT-3 Google TV Android 12 runtime APEX, not a custom platform build',
        'reference_archive_sha256': inspector.SHA256,
        'reference_apex_sha256': inspector.digest(apex),
        'probe_source_sha256': inspector.digest(source),
        'probe_compiler': 'NDK 25.2.9519653 ARMv7/API31',
        'files': records,
        'device_writes_allowed': 'Fresh /data/local/tmp/rayo12-native-* directory only',
        'partition_writes': False,
    }
    (bundle/'MANIFEST.json').write_text(json.dumps(descriptor, indent=2)+'\n', encoding='utf-8')
    print('Prepared and validated six ARM32 ELF files:', bundle)
    print('Not installed or executed on the device.')


if __name__ == '__main__':
    main()
