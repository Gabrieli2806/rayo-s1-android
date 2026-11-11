"""Stage the separately audited radio services and factory C ABI dependencies."""
import hashlib
import json
import posixpath
import re
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET


def stage(system, product, root, source, work, readelf):
    audit = json.loads((root/'outputs/android12-radios/SOURCE-BUILD.json').read_text())
    variables = json.loads((product.parents[2]/'soong/soong.variables').read_text())
    if (not audit.get('source_build_verified') or audit.get('hardware_validated') is not False
            or variables.get('DeviceName') != 'rayos1_tv12_radios'
            or variables.get('DeviceCpuVariant') != 'cortex-a7' or variables.get('Binder32bit') is not True):
        raise ValueError('Unexpected source radio build')
    records = []

    def digest(path):
        return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()

    def needed(path):
        text = subprocess.check_output([str(readelf), '-d', str(path)], text=True)
        return re.findall(r'\(NEEDED\).*?\[(.*?)\]', text)

    def copy(origin, target):
        data = origin.read_bytes()
        if data[:6] != b'\x7fELF\x01\x01' or int.from_bytes(data[18:20], 'little') != 40:
            raise ValueError('Expected ARM32 radio ELF: '+str(origin))
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, target)
        target.chmod(0o755 if target.parent.name == 'bin' else 0o644)
        records.append({'path': str(target.relative_to(system)), 'sha256': digest(target)})

    for record in audit['files']:
        if digest(product/'system/vendor'/record['path']) != record['sha256']:
            raise ValueError('Source radio artifact changed after audit')
    services = ('android.hardware.wifi@1.0-service', 'wpa_supplicant',
                'android.hardware.bluetooth@1.0-service')
    pending = []
    for name in services:
        target = system/'bin'/name
        copy(product/'system/vendor/bin/hw'/name, target)
        pending.append(target)
    target = system/'lib/hw/android.hardware.bluetooth@1.0-impl.so'
    copy(product/'system/vendor/lib/hw'/target.name, target)
    pending.append(target)
    core = {'libc.so', 'libm.so', 'libdl.so', 'libdl_android.so'}
    scanned = set()
    while pending:
        path = pending.pop()
        if path in scanned:
            continue
        scanned.add(path)
        for name in needed(path):
            if name in core:
                continue
            target = system/'lib'/name
            if not target.is_file():
                candidates = [p for p in (product/'system/lib'/name,
                    product/'system/vendor/lib'/name,
                    product/'system/apex/com.android.vndk.current/lib'/name) if p.is_file()]
                if not candidates:
                    raise ValueError('Missing source radio dependency: '+name)
                copy(candidates[0], target)
                pending.append(target)
    # The factory vendor interface uses its original C++ graph in sphal.
    archive_path = root/'local/device-kit/runtime/rayo81-live-overlay.tar.gz'
    inventory = json.loads((root/'local/device-kit/MANIFEST.json').read_text())
    expected = next(item['sha256'] for item in inventory['files']
                    if item['path'] == 'runtime/rayo81-live-overlay.tar.gz')
    if digest(archive_path) != expected:
        raise ValueError('Factory radio archive differs')
    with tarfile.open(archive_path) as archive:
        members = {member.name.rstrip('/'): member for member in archive}

        def original(name, seen=None):
            seen = set() if seen is None else seen
            if name in seen or not name.startswith('rayo-live/'):
                raise ValueError('Invalid legacy radio archive link')
            seen.add(name)
            item = members.get(name)
            if item and (item.issym() or item.islnk()):
                link = item.linkname
                if link.startswith('/system/'):
                    link = 'rayo-live/'+link[len('/system/'):]
                elif not item.islnk():
                    link = posixpath.normpath(posixpath.join(posixpath.dirname(name), link))
                return original(link, seen)
            if item and item.isfile():
                return archive.extractfile(item).read()
            relative = name.removeprefix('rayo-live/')
            target = work/('factory-radio-'+relative.replace('/', '_'))
            subprocess.run(['debugfs', '-R', 'dump /'+relative+' '+str(target),
                            str(root/'local/device-kit/original/system.img')],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if not target.is_file():
                raise ValueError('Missing recorded legacy radio dependency: '+name)
            return target.read_bytes()

        target = system/'vendor/lib/libbt-vendor.so'
        target.write_bytes(original('rayo-live/vendor/lib/libbt-vendor.so'))
        target.chmod(0o644)
        queue = needed(target)
        old_scanned = set()
        while queue:
            name = queue.pop()
            if name in core or name in old_scanned:
                continue
            old_scanned.add(name)
            target = system/'vendor/lib/legacy'/name
            if name == 'libtg.so':
                copy(product/'system/vendor/lib/libtg.so', target)
                continue
            data = original('rayo-live/lib/'+name)
            if target.exists() and target.read_bytes() != data:
                raise ValueError('Legacy graphics/radio dependency conflict: '+name)
            target.write_bytes(data)
            target.chmod(0o644)
            queue.extend(needed(target))
            records.append({'path': str(target.relative_to(system)), 'sha256': digest(target)})
        for name, member in members.items():
            if name.startswith('rayo-live/etc/firmware/') and member.isfile():
                target = system/name.removeprefix('rayo-live/')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(member).read())
                target.chmod(0o644)
        (system/'etc/bluetooth').mkdir(parents=True, exist_ok=True)
        (system/'etc/bluetooth/bt_vendor.conf').write_bytes(original('rayo-live/etc/bluetooth/bt_vendor.conf'))
    # Source service manifests, with a separate declaration for the 1.0 BT HAL.
    manifest = system/'vendor/etc/vintf/manifest'
    manifest.mkdir(exist_ok=True)
    for name in ('android.hardware.wifi@1.0-service.xml', 'android.hardware.wifi.supplicant.xml'):
        shutil.copyfile(product/'system/vendor/etc/vintf/manifest'/name, manifest/name)
    (manifest/'rayo-bluetooth.xml').write_text('''<manifest version="1.0" type="device">
<hal format="hidl"><name>android.hardware.bluetooth</name><transport>hwbinder</transport>
<version>1.0</version><interface><name>IBluetoothHci</name><instance>default</instance></interface></hal>
</manifest>
''')
    shutil.copyfile(source/'frameworks/native/data/etc/android.hardware.bluetooth.xml',
                    system/'etc/permissions/android.hardware.bluetooth.xml')
    (system/'vendor/etc/wifi').mkdir(parents=True, exist_ok=True)
    (system/'vendor/etc/wifi/wpa_supplicant.conf').write_text('update_config=1\nap_scan=1\n')
    (system/'etc/init/rayo12.radios.rc').write_text((root/'work/android12/framework/init.rayo12.radios.rc').read_text())
    for filename, service in (('netd.rc', 'netd'), ('wificond.rc', 'wificond')):
        path = system/'etc/init'/filename
        text = path.read_text()
        if not any(line.startswith('service '+service+' ') for line in text.splitlines()):
            raise ValueError('Unexpected network service definition: '+filename)
        lines = [line for line in text.splitlines() if line.strip().startswith('capabilities ')]
        if len(lines) != 1:
            raise ValueError('Unexpected network ambient capability scope: '+filename)
        path.write_text('\n'.join(line for line in text.splitlines() if line not in lines)+'\n')
    return records
