#!/usr/bin/env python3
"""Inventory APK metadata from the verified private factory system, without a device."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    kit = ROOT/'local/device-kit'
    image = kit/'original/system.img'
    inventory = json.loads((kit/'MANIFEST.json').read_text(encoding='utf-8'))
    expected = next(record for record in inventory['files'] if record['path'] == 'original/system.img')
    if hashlib.file_digest(image.open('rb'), 'sha256').hexdigest() != expected['sha256']:
        raise SystemExit('Factory system image checksum differs')
    output = ROOT/'local/stock-app-audit/apks'
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'AppData/Local/Android/Sdk')))
    aapt = sdk/'build-tools/36.0.0/aapt.exe'

    def linux(path):
        value = path.resolve().as_posix()
        return '/mnt/'+value[0].lower()+value[2:] if os.name == 'nt' else value

    prefix = ['wsl', '-d', 'Ubuntu-24.04', '--'] if os.name == 'nt' else []

    def debug(command):
        return subprocess.check_output(prefix+['debugfs', '-R', command, linux(image)],
                                       stderr=subprocess.DEVNULL, text=True, encoding='utf-8')

    pending = [('/app', 0), ('/priv-app', 0), ('/vendor/bundled_persist-app', 0), ('/ecloud', 0)]
    paths = []
    while pending:
        directory, depth = pending.pop()
        for line in debug('ls -p '+directory).splitlines():
            fields = line.split('/')
            if len(fields) < 7:
                continue
            mode, name = fields[2], fields[5]
            if name in ('.', '..', 'lib', 'lib64', 'oat') or not re.fullmatch(r'[A-Za-z0-9_.-]+', name):
                continue
            path = directory+'/'+name
            if mode.startswith('04') and depth < 4:
                pending.append((path, depth+1))
            elif name.endswith('.apk'):
                paths.append(path)
    records = []
    for path in sorted(paths):
        apk = output/(hashlib.sha256(path.encode()).hexdigest()[:12]+'-'+Path(path).name)
        debug('dump '+path+' '+linux(apk))
        result = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], capture_output=True,
                                text=True, encoding='utf-8', errors='replace')
        metadata = result.stdout
        package = re.search(r"^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", metadata, re.M)
        label = re.search(r"^application-label:'(.*)'$", metadata, re.M)
        if package is None:
            raise SystemExit('Missing APK package metadata: '+path)
        records.append({'path': path, 'package': package[1], 'version_code': package[2],
                        'version_name': package[3], 'label': label[1] if label else None,
                        'bytes': apk.stat().st_size,
                        'sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
                        'metadata_warning': result.stderr.strip() if result.returncode else None})
    result = {'source_system_sha256': expected['sha256'], 'scope': 'Factory system APKs; excludes later user-installed apps',
              'device_accessed': False, 'applications': records}
    report = ROOT/'manifests/factory-app-inventory.json'
    report.write_text(json.dumps(result, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    for record in records:
        print(record['package']+' | '+str(record['label'])+' | '+record['path'])
    print('Inventoried factory APKs:', len(records))


if __name__ == '__main__':
    main()
