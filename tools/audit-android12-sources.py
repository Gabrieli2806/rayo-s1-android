#!/usr/bin/env python3
"""Fetch a bounded, pinned set of official AOSP 12 TV sources; never flash a device."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse
import base64
import hashlib
import json
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROJECTS = {
    'device/google/atv': [
        'products/aosp_tv_arm.mk', 'products/atv_base.mk', 'products/AndroidProducts.mk',
        'products/atv_lowram_defaults.mk', 'products/atv_system.mk', 'products/atv_vendor.mk'],
    'platform/frameworks/native': [
        'services/surfaceflinger/Android.bp',
        'services/surfaceflinger/DisplayHardware/HWComposer.cpp',
        'libs/binder/Android.bp'],
    'platform/system/bpf': ['bpfloader/BpfLoader.cpp'],
    'platform/system/core': ['init/first_stage_init.cpp'],
}


def fetch(url):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                raise
            if attempt == 2:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(attempt + 1)


def gitiles_json(url):
    data = fetch(url).decode('utf-8')
    return json.loads(data.split('\n', 1)[1] if data.startswith(")]}'") else data)


def download(record):
    url = ('https://android.googlesource.com/' + record['project'] + '/+/'
           + record['commit'] + '/' + record['path'] + '?format=TEXT')
    data = base64.b64decode(fetch(url), validate=False)
    digest = hashlib.sha256(data).hexdigest()
    if record.get('sha256') and digest != record['sha256']:
        raise RuntimeError('Source checksum mismatch: ' + record['path'])
    destination = ROOT/'local/android12-source-audit'/record['project']/record['path']
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    return dict(record, sha256=digest, bytes=len(data), url=url.removesuffix('?format=TEXT'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initialize-lock', action='store_true',
                        help='Resolve android12-release once if no lock exists')
    parser.add_argument('--lock', type=Path, default=ROOT/'manifests/android12-tv-source-audit.json',
                        help='Existing bounded source lock to reproduce, including supplemental audits')
    args = parser.parse_args()
    lock = args.lock
    if lock.exists():
        records = json.loads(lock.read_text(encoding='utf-8'))['files']
    else:
        if not args.initialize_lock:
            parser.error('No source lock; explicitly use --initialize-lock for initial research')
        records = []
        for project, files in PROJECTS.items():
            commit = gitiles_json('https://android.googlesource.com/' + project
                                 + '/+/refs/heads/android12-release?format=JSON')['commit']
            records.extend({'project': project, 'commit': commit, 'path': path} for path in files)
    with ThreadPoolExecutor(max_workers=4) as pool:
        result = list(pool.map(download, records))
    descriptor = {
        'purpose': 'Bounded source audit only; not a complete platform manifest or a working board port',
        'initial_ref': 'refs/heads/android12-release',
        'files': result,
    }
    if not lock.exists():
        lock.write_text(json.dumps(descriptor, indent=2)+'\n', encoding='utf-8')
    print('Verified official source snapshots:', len(result))
    print('Local source directory:', ROOT/'local/android12-source-audit')
    print('No build, device write, or boot requested.')


if __name__ == '__main__':
    main()
