#!/usr/bin/env python3
"""Audit and stage private v14 radio assets offline; never contact a device."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    kit = ROOT/'local/device-kit'
    relative = 'runtime/rayo81-live-overlay.tar.gz'
    inventory = json.loads((kit/'MANIFEST.json').read_text(encoding='utf-8'))
    expected = next(record for record in inventory['files'] if record['path'] == relative)
    archive = kit/relative
    if hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() != expected['sha256']:
        raise SystemExit('Private v14 archive checksum differs')
    output = ROOT/'outputs/android12-radios/oem'
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'AppData/Local/Android/Sdk')))
    readelf = sdk/'ndk/25.2.9519653/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
    records = []
    required = {'rayo-live/vendor/lib/libbt-vendor.so',
                'rayo-live/vendor/lib/libbt-vendor.factory.so',
                'rayo-live/vendor/lib/libtg.so'}
    with tarfile.open(archive) as source:
        members = {member.name: member for member in source}
        missing = required-members.keys()
        if missing:
            raise SystemExit('Missing recorded Bluetooth assets: '+str(sorted(missing)))
        selected = sorted(required | {name for name, member in members.items()
            if name.startswith('rayo-live/etc/firmware/') and member.isfile()})
        for name in selected:
            member = members[name]
            if not member.isfile() or member.size > 16*1024*1024:
                raise SystemExit('Expected a bounded regular archive member: '+name)
            data = source.extractfile(member).read()
            relative_path = Path(name.removeprefix('rayo-live/'))
            if '..' in relative_path.parts:
                raise SystemExit('Invalid asset path')
            target = output/relative_path
            target.parent.mkdir(parents=True, exist_ok=True)
            checksum = hashlib.sha256(data).hexdigest()
            if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != checksum:
                raise SystemExit('Refusing to replace a different staged asset: '+str(target))
            target.write_bytes(data)
            record = {'source': name, 'bytes': len(data), 'sha256': checksum}
            if name in required:
                if data[:6] != b'\x7fELF\x01\x01' or int.from_bytes(data[18:20], 'little') != 40:
                    raise SystemExit('Bluetooth library is not ARM32')
                dynamic = subprocess.check_output([str(readelf), '-d', str(target)], text=True)
                record['needed'] = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
            records.append(record)
    report = {'purpose': 'Offline inventory; no radio operation or Android 12 compatibility proven',
              'archive_sha256': expected['sha256'], 'device_written': False, 'files': records}
    (output.parent/'OEM-AUDIT.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print('Staged and checksummed private radio assets:', len(records))
    for record in records:
        if 'needed' in record:
            print(record['source']+': '+', '.join(record['needed']))


if __name__ == '__main__':
    main()
