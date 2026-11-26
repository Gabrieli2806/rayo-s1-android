#!/usr/bin/env python3
"""Build a public, signed GabOS wireless update from an installed-boot candidate.

Run in WSL/Linux (needs debugfs, gzip, openssl). Output in outputs/gabos-ota/<version>/:

  gabos-ota.json          manifest (what the projector downloads first)
  gabos-ota.json.sig      ECDSA P-256 / SHA-256 signature (DER) of the manifest bytes
  gabos-system.img.gz     the system image with every proprietary file's data
                          blocks zeroed (same layout and size otherwise)
  gabos-boot.img          the boot image with the factory kernel and second
                          stage zeroed

Nothing proprietary is published. The projector rebuilds the exact images:
the zeroed blocks are refilled from the same files in its currently installed
system image, and the kernel/second stage from its current boot partition. Each
restored file and both final images are checked against SHA-256 values in the
manifest, so a projector whose installed files differ refuses the update and
keeps its system.

A file counts as proprietary when its content matches a file of the factory
system image or the device kit overlay, or when it sits in a vendor blob
directory and differs from the Android 12 build output (patched factory files).

  python3 tools/make-gabos-ota.py --candidate outputs/android12-framework/gabos-1.4 --version 1.4 [--dry-run]
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT/'local/ota-cache'
BLOCK = 4096
KEY = ROOT/'local/secrets/gabos-ota-signing.pem'
PUBLIC_KEY = ROOT/'manifests/gabos-ota-signing.pub.pem'
REPOSITORY = 'Gabrieli2806/rayo-s1-android'
VENDOR_DIRS = ('vendor/lib/legacy/', 'vendor/lib/hw/', 'vendor/lib/egl/', 'vendor/lib/mediadrm/', 'vendor/firmware/',
               'vendor/etc/firmware/', 'etc/firmware/', 'lib/modules/')


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def dump(image, target):
    """Extract an ext4 image with debugfs (no mount, no root)."""
    if target.exists():
        return target
    target.mkdir(parents=True)
    subprocess.run(['debugfs', '-R', 'rdump / '+str(target), str(image)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return target


def files(base):
    for path in sorted(base.rglob('*')):
        if path.is_file() and not path.is_symlink():
            yield path.relative_to(base).as_posix(), path


def known_proprietary_hashes():
    hashes = set()
    factory = dump(ROOT/'local/device-kit/original/system.img', CACHE/'factory-system')
    for _, path in files(factory):
        hashes.add(sha256(path))
    overlay = CACHE/'overlay'
    if not overlay.exists():
        overlay.mkdir(parents=True)
        with tarfile.open(ROOT/'local/device-kit/runtime/rayo81-live-overlay.tar.gz') as archive:
            archive.extractall(overlay)
    for _, path in files(overlay):
        hashes.add(sha256(path))
    return hashes


def blocks_of(image, path):
    """Physical 4 KiB blocks holding a file's data, as [start, count] runs."""
    out = subprocess.run(['debugfs', '-R', 'blocks /'+path, str(image)], capture_output=True, text=True).stdout
    numbers = [int(word) for word in out.split() if word.isdigit()]
    runs = []
    for number in numbers:
        if runs and runs[-1][0]+runs[-1][1] == number:
            runs[-1][1] += 1
        else:
            runs.append([number, 1])
    return runs


def sign(data):
    with tempfile.NamedTemporaryFile(delete=False) as payload:
        payload.write(data)
    try:
        return subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(KEY), payload.name],
                              check=True, capture_output=True).stdout.hex()
    finally:
        os.unlink(payload.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--tv-out', type=Path, default=Path.home()/'android/rayo12-tv-out/target/product/rayos1_tv12')
    parser.add_argument('--dry-run', action='store_true', help='List proprietary files only')
    args = parser.parse_args()
    candidate = args.candidate.resolve()
    manifest = json.loads((candidate/'MANIFEST.json').read_text())
    system_image = candidate/manifest['system']['path']
    boot_image = candidate/'rayo12-framework-boot.img'
    if not manifest.get('boot_success_watchdog'):
        raise SystemExit('Not an installed-boot candidate')
    known = known_proprietary_hashes()
    work = Path(tempfile.mkdtemp(prefix='gabos-ota-'))
    extracted = dump(system_image, work/'system')
    blobs = []
    for rel, path in files(extracted):
        size = path.stat().st_size
        if size == 0:
            continue
        digest = sha256(path)
        # Our own Android 12 build output is open source; only files it does
        # not produce can be factory blobs.
        built = [args.tv_out/'system'/rel]
        if rel.startswith('vendor/'):
            built.append(args.tv_out/rel)
        if any(b.is_file() and b.stat().st_size == size and sha256(b) == digest for b in built):
            continue
        vendor = any(rel.startswith(d) for d in VENDOR_DIRS)
        if digest in known or vendor:
            blobs.append({'path': rel, 'bytes': size, 'sha256': digest})
    total = sum(b['bytes'] for b in blobs)
    print('%d proprietary files, %.1f MiB' % (len(blobs), total/1048576))
    if args.dry_run:
        for b in blobs:
            print('  %-70s %9d' % (b['path'], b['bytes']))
        shutil.rmtree(work)
        return
    out = ROOT/'outputs/gabos-ota'/args.version
    if out.exists():
        raise SystemExit('Refusing to overwrite '+str(out))
    out.mkdir(parents=True)

    # System: zero the data blocks of every proprietary file.
    public = work/'system-public.img'
    shutil.copyfile(system_image, public)
    with open(public, 'r+b') as image:
        for blob in blobs:
            blob['blocks'] = blocks_of(system_image, blob['path'])
            if sum(count for _, count in blob['blocks'])*BLOCK < blob['bytes']:
                raise SystemExit('Could not map the blocks of '+blob['path'])
            # Zero exactly the file's bytes; the slack after its end keeps the
            # image's own content, so the restored image is byte-identical.
            left = blob['bytes']
            for start, count in blob['blocks']:
                image.seek(start*BLOCK)
                length = min(left, count*BLOCK)
                image.write(bytes(length))
                left -= length
    with open(public, 'rb') as source, gzip.open(out/'gabos-system.img.gz', 'wb', compresslevel=6) as target:
        shutil.copyfileobj(source, target, 1 << 20)

    # Boot: zero the factory kernel and second stage (Android boot header layout).
    raw = bytearray(boot_image.read_bytes())
    kernel_size, _, ramdisk_size, _, second_size = struct.unpack_from('<5I', raw, 8)
    page = struct.unpack_from('<I', raw, 36)[0]
    pages = lambda n: (n+page-1)//page*page
    kernel = {'offset': page, 'bytes': kernel_size}
    second = {'offset': page+pages(kernel_size)+pages(ramdisk_size), 'bytes': second_size}
    if page % 512 or kernel['offset'] % page or second['offset'] % page:
        raise SystemExit('Unexpected boot image layout')
    for part in (kernel, second):
        part['sha256'] = hashlib.sha256(raw[part['offset']:part['offset']+part['bytes']]).hexdigest()
        raw[part['offset']:part['offset']+part['bytes']] = bytes(part['bytes'])
    (out/'gabos-boot.img').write_bytes(bytes(raw))

    release = {
        'format': 1,
        'version': args.version,
        'repository': REPOSITORY,
        'tag': 'gabos-'+args.version,
        'system': {'file': 'gabos-system.img.gz', 'gz_sha256': sha256(out/'gabos-system.img.gz'),
                   'gz_bytes': (out/'gabos-system.img.gz').stat().st_size, 'public_sha256': sha256(public),
                   'bytes': system_image.stat().st_size, 'sha256': manifest['system']['sha256'],
                   'md5': hashlib.md5(system_image.read_bytes()).hexdigest(), 'blobs': blobs},
        'boot': {'file': 'gabos-boot.img', 'public_sha256': sha256(out/'gabos-boot.img'),
                 'bytes': boot_image.stat().st_size, 'sha256': manifest['boot']['sha256'],
                 'md5': hashlib.md5(boot_image.read_bytes()).hexdigest(), 'page': page, 'kernel': kernel, 'second': second},
        'data': {'uuid': manifest['data']['uuid'], 'bytes': manifest['data']['bytes']},
    }
    body = (json.dumps(release, indent=1)+'\n').encode()
    (out/'gabos-ota.json').write_bytes(body)
    # Detached ECDSA P-256 / SHA-256 signature (DER) over the exact manifest bytes.
    (out/'gabos-ota.json.sig').write_bytes(bytes.fromhex(sign(body)))
    shutil.rmtree(work)
    print(json.dumps({k: v for k, v in release.items() if k not in ('system', 'boot')}, indent=1))
    print('system.gz %.0f MiB' % (release['system']['gz_bytes']/1048576))


if __name__ == '__main__':
    main()
