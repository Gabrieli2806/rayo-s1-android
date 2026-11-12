#!/usr/bin/env python3
"""Grow a host copy of the retained private Android 12 data image offline.

The 256 MiB trial data cannot hold Google services. This tool never accesses a
device: it copies the data image pulled at the end of a completed, restored run,
checks it, and enlarges the copy while keeping its UUID and feature set, which
the bootstrap and the trial controller both verify. The controller replaces the
device file only if the device copy is still byte-identical to the source run.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]


def superblock(path):
    with path.open('rb') as stream:
        stream.seek(1024)
        raw = stream.read(1024)
    if raw[56:58] != bytes((0x53, 0xef)):
        raise SystemExit('Not an ext4 image: '+str(path))
    return str(uuid.UUID(bytes=raw[104:120])), struct.unpack_from('<III', raw, 92)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True, help='Completed local framework run directory')
    parser.add_argument('--mib', type=int, required=True, help='New size, 256-3072 MiB')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 256 < args.mib <= 3072:
        raise SystemExit('Size is outside the bootstrap limits')
    run = args.run.resolve()
    if not run.is_relative_to((ROOT/'local/android12-framework-runs').resolve()):
        raise SystemExit('Select a recorded local framework run')
    record = json.loads((run/'RESULT.json').read_text(encoding='utf-8'))
    if not (record.get('normal_v14_returned') and record.get('original_recovery_restored')):
        raise SystemExit('The source run did not return to normal with factory recovery restored')
    source = run/'private-data-after.ext4'
    expected_uuid = record['candidate']['data']['uuid']
    source_uuid, source_features = superblock(source)
    if source_uuid != expected_uuid or source.stat().st_size != record['candidate']['data']['bytes']:
        raise SystemExit('Pulled data image does not match its run record')
    source_md5 = hashlib.file_digest(source.open('rb'), 'md5').hexdigest()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, output)
    # The image was pulled after an unclean loop detach; e2fsck exit 1 means
    # corrected errors, anything above is refused.
    check = subprocess.run(['e2fsck', '-fy', str(output)], capture_output=True, text=True)
    if check.returncode not in (0, 1):
        raise SystemExit('Filesystem check failed:\n'+check.stdout[-2000:])
    subprocess.run(['resize2fs', str(output), str(args.mib)+'M'], check=True, capture_output=True)
    if subprocess.run(['e2fsck', '-fn', str(output)], capture_output=True).returncode != 0:
        raise SystemExit('Grown filesystem is not clean')
    grown_uuid, grown_features = superblock(output)
    if (grown_uuid != expected_uuid or grown_features != source_features
            or output.stat().st_size != args.mib*1024*1024):
        raise SystemExit('Grown image identity, features or size differ')
    record_path = Path(str(output)+'.json')
    record_path.write_text(json.dumps({
        'source_run': run.name, 'source_md5': source_md5, 'source_bytes': source.stat().st_size,
        'uuid': grown_uuid, 'bytes': output.stat().st_size,
        'sha256': hashlib.file_digest(output.open('rb'), 'sha256').hexdigest(),
        'device_written': False}, indent=2)+'\n', encoding='utf-8')
    print('Grown private data image:', output)


if __name__ == '__main__':
    main()
