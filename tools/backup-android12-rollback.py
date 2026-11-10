#!/usr/bin/env python3
"""Read and checksum v14 boot/original recovery for the future 12 trial; never flash."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = '/dev/block/platform/1021c000.rksdmmc/by-name/'
EXPECTED = {'boot': '7a3ac489b05f0f2e9cde16280551511a',
            'recovery': 'b32f971e7dfce13c708d264c6221844d'}


def main():
    subprocess.run([sys.executable, str(ROOT/'tools/run-android12-native-smoke.py')], check=True)
    prefix = [shutil.which('adb'), '-s', '0123456789ABCDEF']
    stamp = time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
    directory = ROOT/'local/android12-rollback'/stamp
    directory.mkdir(parents=True)
    records = []
    for partition, expected in EXPECTED.items():
        destination = directory/(partition+'.img')
        with destination.open('xb') as output:
            subprocess.run(prefix+['exec-out', 'cat', BASE+partition], stdout=output,
                           stderr=subprocess.PIPE, timeout=60, check=True)
        data = destination.read_bytes()
        after = subprocess.check_output(prefix+['shell', 'md5sum', BASE+partition], text=True, timeout=30).split()[0]
        if len(data) != 33554432 or hashlib.md5(data).hexdigest() != expected or after != expected:
            raise SystemExit('Backup did not match the known checkpoint: '+partition)
        records.append({'partition': partition, 'path': destination.name, 'bytes': len(data),
                        'md5': expected, 'sha256': hashlib.sha256(data).hexdigest()})
    (directory/'MANIFEST.json').write_text(json.dumps({'serial': '0123456789ABCDEF',
        'purpose': 'Fresh read-only backup before preparing an Android 12 recovery-slot trial',
        'files': records}, indent=2)+'\n', encoding='utf-8')
    print('Verified local rollback copies:', directory)
    print('No partition, userdata, boot selection, or device configuration was modified.')


if __name__ == '__main__':
    main()
