#!/usr/bin/env python3
"""Read the preserved previous-main partitions while the isolated 8.1 trial is running."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ADB = shutil.which('adb')
if not ADB:
    raise SystemExit('Install Android Platform Tools')
PREFIX = [ADB, '-s', '0123456789ABCDEF']


def shell(*args):
    return subprocess.check_output(PREFIX+['shell']+list(args), text=True, timeout=180).strip()


if shell('getprop', 'ro.build.version.release') != '8.1.0' or shell('getprop', 'ro.rayo.trial') != '1':
    raise SystemExit('Requires the identified isolated 8.1 trial; no reads started')
if shell('id', '-u') != '0':
    raise SystemExit('Requires trial root')
stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
directory = ROOT/'local/previous-main'/stamp
directory.mkdir(parents=True, exist_ok=False)
partitions = {'boot': 33554432, 'system': 2147483648, 'kernel': 33554432,
              'resource': 16777216, 'uboot': 4194304, 'trust': 4194304, 'misc': 4194304}
manifest = {'date_utc': stamp, 'kind': 'Previous Android 7.1.2 main-system partitions',
            'scope': 'Unit-specific firmware, including prior launcher/logo changes; no userdata/accounts',
            'read_context': 'Isolated Android 8.1; original system not mounted by this trial',
            'files': []}
for name, expected_size in partitions.items():
    block = '/dev/block/platform/1021c000.rksdmmc/by-name/'+name
    size = int(shell('/sbin/busybox', 'blockdev', '--getsize64', block))
    if size != expected_size:
        raise SystemExit('Unexpected partition size: '+name)
    output = directory/(name+'.img')
    process = subprocess.Popen(PREFIX+['exec-out', '/sbin/busybox', 'cat', block],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    sha = hashlib.sha256(); md5 = hashlib.md5(); count = 0; next_progress = 256*1024*1024
    with output.open('xb') as stream:
        while chunk := process.stdout.read(1024*1024):
            stream.write(chunk); sha.update(chunk); md5.update(chunk); count += len(chunk)
            if count >= next_progress:
                print(name, count//(1024*1024), 'MiB read', flush=True)
                next_progress += 256*1024*1024
    error = process.stderr.read()
    result = process.wait()
    if result or count != expected_size:
        raise SystemExit('Incomplete raw read: '+name+' '+error.decode(errors='replace'))
    if shell('md5sum', block).split()[0] != md5.hexdigest():
        raise SystemExit('Device/readback MD5 mismatch: '+name)
    manifest['files'].append({'path': output.name, 'bytes': count,
                              'sha256': sha.hexdigest(), 'md5': md5.hexdigest(),
                              'source': 'Read directly from this unit'})
    print('Verified:', name, md5.hexdigest(), flush=True)

recovery = ROOT/'local/device-kit/original/recovery.img'
data = recovery.read_bytes()
if hashlib.md5(data).hexdigest() != 'b32f971e7dfce13c708d264c6221844d':
    raise SystemExit('Unexpected original recovery backup')
shutil.copyfile(recovery, directory/'recovery.img')
manifest['files'].append({'path': 'recovery.img', 'bytes': len(data),
                          'sha256': hashlib.sha256(data).hexdigest(),
                          'md5': hashlib.md5(data).hexdigest(),
                          'source': 'Previously verified original recovery from private device kit'})
manifest['restore_note'] = ('Restore boot for previous normal startup if system is preserved. '
                            'System image is a fallback if needed. Never restore misc by default; '
                            'its snapshot was taken during a recovery-slot trial. No complete eMMC/MaskROM guarantee.')
(directory/'MANIFEST.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8', newline='\n')
archive_path = directory/'rayo-s1-previous-main-7.1.2.zip'
with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as archive:
    for record in manifest['files']:
        archive.write(directory/record['path'], record['path'])
    archive.write(directory/'MANIFEST.json', 'MANIFEST.json')
with zipfile.ZipFile(archive_path) as archive:
    if archive.testzip():
        raise SystemExit('ZIP CRC validation failed')
sha = hashlib.sha256()
with archive_path.open('rb') as stream:
    while data := stream.read(8*1024*1024):
        sha.update(data)
descriptor = {'release': 'previous-main-7.1.2', 'asset': archive_path.name,
              'bytes': archive_path.stat().st_size, 'sha256': sha.hexdigest()}
(ROOT/'manifests/previous-main-backup.json').write_text(json.dumps(descriptor, indent=2)+'\n', encoding='utf-8', newline='\n')
(ROOT/'local/previous-main/latest.json').write_text(json.dumps({'directory': str(directory), **descriptor}, indent=2)+'\n', encoding='utf-8', newline='\n')
print('Previous-main backup verified:', directory, flush=True)
print('No device files, settings, partitions, or boot state changed.', flush=True)
