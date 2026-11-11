#!/usr/bin/env python3
"""Test an isolated upstream non-FIPS crypto process on v14 without rebooting."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import re

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--product-out', type=Path, required=True)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--sid', action='store_true', help='Run the exact pinned-proc caller-context helper instead')
    args = parser.parse_args()
    product = args.product_out
    variables = json.loads((product.parents[2]/'soong/soong.variables').read_text())
    if variables.get('DeviceCpuVariant') != 'cortex-a7' or variables.get('DeviceArch') != 'arm':
        raise SystemExit('Unexpected crypto build CPU')
    bundle = ROOT/'outputs/android12-native-built'
    manifest = json.loads((bundle/'MANIFEST.json').read_text())
    files = {}
    for record in manifest['files']:
        if record['path'] == 'bin/rayo12-bootstrap-probe':
            continue
        path = bundle/record['path']
        if hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256']:
            raise SystemExit('Core runtime checksum mismatch')
        files[record['path']] = path
    if set(files) != {'bin/linker', 'lib/libc.so', 'lib/libm.so', 'lib/libdl.so', 'lib/libdl_android.so'}:
        raise SystemExit('Unexpected core runtime layout')
    mode = 'sid' if args.sid else 'crypto'
    executable = 'rayo12-'+mode+'-probe'
    files['bin/'+executable] = product/('system/bin/rayo12_'+mode+'_probe')
    if args.sid:
        readelf = Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/ndk/25.2.9519653/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
        pending = [files['bin/'+executable]]
        while pending:
            current = pending.pop()
            dynamic = subprocess.check_output([str(readelf), '-d', str(current)], text=True)
            for name in re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic):
                if not re.fullmatch(r'[A-Za-z0-9_.+-]+', name):
                    raise SystemExit('Unexpected probe dependency name')
                relative = 'lib/'+name
                if relative not in files:
                    source = product/'system'/relative
                    if not source.is_file():
                        raise SystemExit('Missing source-built probe dependency: '+name)
                    files[relative] = source
                    pending.append(source)
    else:
        files['lib/librayo12_crypto_legacy.so'] = product/'system/lib/librayo12_crypto_legacy.so'
    records = []
    for relative, path in files.items():
        raw = path.read_bytes()
        if raw[:6] != b'\x7fELF\x01\x01' or int.from_bytes(raw[18:20], 'little') != 40:
            raise SystemExit('Expected actual ARM32 ELF: '+relative)
        records.append({'path': relative, 'sha256': hashlib.sha256(raw).hexdigest(), 'md5': hashlib.md5(raw).hexdigest()})
    subprocess.run([sys.executable, str(ROOT/'tools/run-android12-native-smoke.py')], check=True)
    if not args.execute:
        print('Crypto plan only; no uploads or execution.')
        return
    adb = shutil.which('adb')
    prefix = [adb, '-s', '0123456789ABCDEF']

    def checked(*parts):
        result = subprocess.run(prefix+list(parts), capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace'))
        return result.stdout.decode(errors='replace').strip()

    remote = '/data/local/tmp/rayo12-'+mode+'-'+uuid.uuid4().hex[:12]
    output = ROOT/('outputs/android12-'+mode+'-probe')
    output.mkdir(parents=True, exist_ok=True)
    checked('shell', '/sbin/busybox', 'mkdir', '-p', remote+'/lib', remote+'/bin')
    checked('shell', 'chmod', '700', remote)
    for record in records:
        checked('push', str(files[record['path']]), remote+'/'+record['path'])
        if checked('shell', 'md5sum', remote+'/'+record['path']).split()[0] != record['md5']:
            raise RuntimeError('Crypto probe transfer differs')
    checked('shell', 'chmod', '755', remote+'/bin/linker', remote+'/bin/'+executable)
    result = subprocess.run(prefix+['shell', 'LD_LIBRARY_PATH='+remote+'/lib', remote+'/bin/linker',
                                   remote+'/bin/'+executable], capture_output=True, timeout=30)
    (output/'stdout.txt').write_bytes(result.stdout)
    (output/'stderr.txt').write_bytes(result.stderr)
    marker = b'RAYO12_PINNED_SID_PASS' if args.sid else b'RAYO12_CRYPTO_NONFIPS_PASS'
    if result.returncode or marker not in result.stdout:
        raise SystemExit('Crypto acceptance failed; inspect local stdout/stderr')
    subprocess.run([sys.executable, str(ROOT/'tools/run-android12-native-smoke.py')], check=True)
    record = {'kernel': '3.10.104', 'fips': False, 'passed': True, 'files': records,
              'scope': 'Isolated process only; no service, boot, mount or partition changes'}
    if args.sid:
        record['helper_sha256'] = hashlib.sha256((ROOT/'work/android12/device/canon/rayos1_12_lab/legacy_sid.rs').read_bytes()).hexdigest()
    (output/'VALIDATED.json').write_text(json.dumps(record, indent=2)+'\n')
    print(result.stdout.decode().strip())


if __name__ == '__main__':
    main()
