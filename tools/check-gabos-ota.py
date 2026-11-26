#!/usr/bin/env python3
"""Rebuild a GabOS wireless update on the host, as GabOS Rescue does on the device.

Takes the public files of outputs/gabos-ota/<version>/ and the images of the
version the projector runs now (an installed-boot candidate directory), restores
the factory files and kernel exactly like work/gabos-rescue/gabos-apply-update.sh,
and checks the results against the manifest. Run in WSL/Linux (needs debugfs).

  python3 tools/check-gabos-ota.py --ota outputs/gabos-ota/1.4 --installed outputs/android12-framework/gabos-1.3
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

BLOCK = 4096


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--ota', type=Path, required=True)
    parser.add_argument('--installed', type=Path, required=True)
    args = parser.parse_args()
    release = json.loads((args.ota/'gabos-ota.json').read_text())
    installed = json.loads((args.installed/'MANIFEST.json').read_text())
    old_system = args.installed/installed['system']['path']
    old_boot = (args.installed/'rayo12-framework-boot.img').read_bytes()
    work = Path(tempfile.mkdtemp(prefix='gabos-ota-check-'))
    new = work/'system.img'
    with gzip.open(args.ota/release['system']['file'], 'rb') as source, open(new, 'wb') as target:
        shutil.copyfileobj(source, target, 1 << 20)
    problems = []
    with open(new, 'r+b') as image:
        for blob in release['system']['blobs']:
            data = subprocess.run(['debugfs', '-R', 'cat /'+blob['path'], str(old_system)],
                                  capture_output=True).stdout
            if sha256(data) != blob['sha256']:
                problems.append('installed file differs: '+blob['path'])
                continue
            offset = 0
            for start, count in blob['blocks']:
                piece = data[offset*BLOCK:(offset+count)*BLOCK]
                image.seek(start*BLOCK)
                image.write(piece)
                offset += count
    h = hashlib.sha256()
    with open(new, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            h.update(block)
    system_ok = h.hexdigest() == release['system']['sha256']
    boot = bytearray((args.ota/release['boot']['file']).read_bytes())
    for name in ('kernel', 'second'):
        part = release['boot'][name]
        piece = old_boot[part['offset']:part['offset']+part['bytes']]
        if sha256(piece) != part['sha256']:
            problems.append('installed boot '+name+' differs')
        boot[part['offset']:part['offset']+part['bytes']] = piece
    boot_ok = sha256(bytes(boot)) == release['boot']['sha256']
    shutil.rmtree(work)
    print(json.dumps({'system_rebuilt': system_ok, 'boot_rebuilt': boot_ok, 'problems': problems}, indent=1))
    raise SystemExit(0 if system_ok and boot_ok and not problems else 1)


if __name__ == '__main__':
    main()
