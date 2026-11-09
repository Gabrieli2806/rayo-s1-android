#!/usr/bin/env python3
"""Package the specific verified v14 checkpoint from local inputs; never upload secrets."""
from pathlib import Path, PurePosixPath
import hashlib
import json
import re
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT/'local/device-kit'
OUTPUT = ROOT/'local/portable/rayo-s1-device-kit-v0.2.zip'
if OUTPUT.exists():
    raise SystemExit('Refusing to overwrite an existing device kit')
base_manifest = json.loads((BASE/'MANIFEST.json').read_text(encoding='utf-8'))
files = {record['path']: BASE/record['path'] for record in base_manifest['files']}
files.update({
    'boot/trial-recovery-v14.img': ROOT/'outputs/firmware/trial-recovery-v14.img',
    'runtime/rayo81-live-overlay.tar.gz': ROOT/'local/portable/rayo81-v14-live-overlay.tar.gz',
    'runtime/trial-root-v14.tar.gz': ROOT/'local/portable/trial-root-v14.tar.gz',
    'apps/Rayo-Hardware-Check.apk': ROOT/'outputs/Rayo-Hardware-Check.apk',
})
image = files['boot/trial-recovery-v14.img'].read_bytes()
if hashlib.sha256(image).hexdigest() != '9e7c48594fae621e74a37ea4bd427fa3284f0ab578cbf8ff94187bb654278c2d':
    raise SystemExit('Unexpected v14 recovery')
with tarfile.open(files['runtime/rayo81-live-overlay.tar.gz'], 'r:gz') as archive:
    for member in archive:
        name = PurePosixPath(member.name)
        if name.is_absolute() or '..' in name.parts or name.parts[0] != 'rayo-live':
            raise SystemExit('Unexpected overlay member: '+member.name)
        if member.isfile() and member.size < 2_000_000 and name.suffix in ('.conf', '.json', '.xml', '.prop'):
            data = archive.extractfile(member).read()
            if re.search(rb'(?m)^\s*(?:psk|password|ssid)\s*=', data):
                raise SystemExit('Review possible credentials locally in: '+member.name)
manifest = {'checkpoint': 'rayo-s1-android81-v14', 'date': '2026-11-09',
            'device': 'Canon Rayo S1; specific original unit', 'files': []}
with zipfile.ZipFile(OUTPUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as archive:
    for target, source in sorted(files.items()):
        path = PurePosixPath(target)
        if path.is_absolute() or '..' in path.parts:
            raise SystemExit('Invalid kit path')
        sha = hashlib.sha256(); md5 = hashlib.md5()
        with source.open('rb') as stream:
            while data := stream.read(8*1024*1024):
                sha.update(data); md5.update(data)
        old = next((r for r in base_manifest['files'] if r['path'] == target), None)
        if old and source == BASE/target and sha.hexdigest() != old['sha256']:
            raise SystemExit('Base kit file changed: '+target)
        manifest['files'].append({'path': target, 'bytes': source.stat().st_size,
                                  'sha256': sha.hexdigest(), 'md5': md5.hexdigest()})
        archive.write(source, target)
        print('Packed:', target, flush=True)
    archive.writestr('MANIFEST.json', json.dumps(manifest, indent=2)+'\n')
with zipfile.ZipFile(OUTPUT) as archive:
    broken = archive.testzip()
    if broken:
        raise SystemExit('CRC failure: '+broken)
digest = hashlib.sha256()
with OUTPUT.open('rb') as stream:
    while data := stream.read(8*1024*1024):
        digest.update(data)
if OUTPUT.stat().st_size >= 2*1024**3:
    raise SystemExit('Release asset exceeds 2 GiB')
descriptor = {'release': 'device-kit-v0.2', 'asset': OUTPUT.name,
              'bytes': OUTPUT.stat().st_size, 'sha256': digest.hexdigest()}
(ROOT/'manifests/device-kit.json').write_text(json.dumps(descriptor, indent=2)+'\n', encoding='utf-8', newline='\n')
(ROOT/'local/portable/device-kit-v0.2-MANIFEST.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8', newline='\n')
print('Verified device kit:', json.dumps(descriptor), flush=True)
