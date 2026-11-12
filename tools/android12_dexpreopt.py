#!/usr/bin/env python3
"""Host ahead-of-time compilation for applications in an offline GabOS system tree.

The eng product only preoptimizes the boot image and system server. The board
also runs with the JIT disabled, so every other application would otherwise be
interpreted. This module compiles APKs with the source-built host dex2oat
against the exact boot image that is packaged, never on or for a live device.
"""
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

# Compatibility libraries the package manager adds for system applications
# below each target SDK, in the order used by the platform build
# (build/soong/dexpreopt/class_loader_context.go, descending SDK after "any").
IMPLICIT = ((30, ('android.test.base',)),
            (29, ('android.hidl.manager-V1.0-java', 'android.hidl.base-V1.0-java')),
            (28, ('org.apache.http.legacy',)))
# Removed from every package by PackageBackwardCompatibility on Android 12.
REMOVED = {'android.net.ipsec.ike', 'com.google.android.maps'}


def _digest(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def _global_inputs(out, product_name, system):
    configs = sorted((out/'target/product'/product_name/'dexpreopt_config').glob('*_dexpreopt.config'))
    if not configs:
        raise SystemExit('Missing module dexpreopt configuration; build the TV product first')
    config = json.loads(configs[0].read_text())
    if config.get('Archs') != ['arm']:
        raise SystemExit('Unexpected dexpreopt architecture')
    host_images = config['DexPreoptImageLocationsOnHost']
    device_images = config['DexPreoptImageLocationsOnDevice']
    boot_files = config['PreoptBootClassPathDexFiles']
    boot_locations = config['PreoptBootClassPathDexLocations']
    if len(host_images) != len(device_images) or len(boot_files) != len(boot_locations):
        raise SystemExit('Inconsistent dexpreopt boot class path record')
    # The compiled code embeds boot image checksums. Require the packaged boot
    # image files to be byte-identical to the host files used for compilation.
    for host, device in zip(host_images, device_images):
        host_path = Path(host)
        if device.startswith('/apex/com.android.art/'):
            matches = [system/'apex'/name/device[len('/apex/com.android.art/'):]
                       for name in ('com.android.art', 'com.android.art.debug')]
        elif device.startswith('/system/'):
            matches = [system/device[len('/system/'):]]
        else:
            raise SystemExit('Unexpected boot image location: '+device)
        packaged = [path.parent/'arm'/path.name for path in matches
                    if (path.parent/'arm'/path.name).is_file()]
        compiled = host_path.parent/'arm'/host_path.name
        if len(packaged) != 1 or not compiled.is_file() or _digest(packaged[0]) != _digest(compiled):
            raise SystemExit('Packaged boot image differs from the host compilation image: '+device)
    # Compile against the exact class path archives that are packaged. The
    # recorded host intermediates for updatable modules are not built when the
    # product only preoptimizes its boot image.
    packaged_files = []
    for location in boot_locations:
        parts = location.strip('/').split('/')
        if parts[0] == 'apex':
            matches = [system/'apex'/name/Path(*parts[2:]) for name in (parts[1], parts[1]+'.debug')]
        elif parts[0] == 'system':
            matches = [system/Path(*parts[1:])]
        else:
            raise SystemExit('Unexpected boot class path location: '+location)
        matches = [path for path in matches if path.is_file()]
        if len(matches) != 1 or not has_dex(matches[0]):
            raise SystemExit('Missing packaged boot class path archive: '+location)
        packaged_files.append(str(matches[0]))
    return host_images, packaged_files, boot_locations


def _device_path(system, location):
    """Map a device path of the merged /system tree to its packaged file."""
    parts = location.strip('/').split('/')
    if parts[0] in ('product', 'system_ext', 'vendor'):
        return system/Path(*parts)
    if parts[0] == 'system':
        return system/Path(*parts[1:])
    raise SystemExit('Unexpected shared library location: '+location)


def shared_libraries(system):
    """Read the declared shared Java libraries of the packaged tree."""
    libraries = {}
    for directory in ('etc/permissions', 'product/etc/permissions', 'system_ext/etc/permissions',
                      'vendor/etc/permissions'):
        for path in sorted((system/directory).glob('*.xml')):
            for node in ET.parse(path).getroot().iter('library'):
                name, location = node.get('name'), node.get('file')
                if not name or not location or location.startswith('/apex/'):
                    continue
                packaged = _device_path(system, location)
                if packaged.is_file():
                    dependencies = [d for d in (node.get('dependency') or '').split(':') if d]
                    libraries[name] = (location, packaged, dependencies)
    return libraries


def manifest_libraries(apk):
    """Return (targetSdkVersion, required, optional) from the binary manifest.

    A small direct reader is used because aapt2 refuses some prebuilt
    applications whose resource tables contain obfuscated types."""
    with zipfile.ZipFile(apk) as archive:
        raw = archive.read('AndroidManifest.xml')
    if struct.unpack_from('<HH', raw, 0) != (0x0003, 8):
        raise SystemExit('Unexpected binary manifest: '+str(apk))
    strings, resource_ids, offset = [], [], 8
    target_sdk, required, optional = 1, [], []
    while offset+8 <= len(raw):
        kind, header, size = struct.unpack_from('<HHI', raw, offset)
        if size < 8 or offset+size > len(raw):
            raise SystemExit('Corrupt binary manifest: '+str(apk))
        if kind == 0x0001:
            count, _, flags, strings_start, _ = struct.unpack_from('<IIIII', raw, offset+8)
            utf8 = bool(flags & 0x100)
            for index in range(count):
                position = offset+strings_start+struct.unpack_from('<I', raw, offset+header+4*index)[0]
                if utf8:
                    for _ in range(2):  # UTF-16 length, then UTF-8 byte length
                        position += 2 if raw[position] & 0x80 else 1
                    stop = raw.index(bytes(1), position)
                    strings.append(raw[position:stop].decode('utf-8', 'replace'))
                else:
                    length = struct.unpack_from('<H', raw, position)[0]
                    position += 2
                    if length & 0x8000:
                        length = ((length & 0x7fff) << 16) | struct.unpack_from('<H', raw, position)[0]
                        position += 2
                    strings.append(raw[position:position+2*length].decode('utf-16-le', 'replace'))
        elif kind == 0x0180:
            resource_ids = list(struct.unpack_from('<'+str((size-header)//4)+'I', raw, offset+header))
        elif kind == 0x0102:
            name, attr_start, attr_size, attr_count = struct.unpack_from('<IHHH', raw, offset+header+4)
            element, values = strings[name], {}
            for index in range(attr_count):
                base = offset+header+attr_start+index*attr_size
                _, attr_name, raw_value, _, _, data_type, data = struct.unpack_from('<IIIHBBI', raw, base)
                resource = resource_ids[attr_name] if attr_name < len(resource_ids) else 0
                values[resource] = strings[data] if data_type == 0x03 else data
            if element == 'uses-sdk' and 0x01010270 in values:
                target_sdk = int(values[0x01010270])
            elif element == 'uses-library' and isinstance(values.get(0x01010003), str):
                (optional if values.get(0x0101028e, 1) == 0 else required).append(values[0x01010003])
        offset += size
    return target_sdk, required, optional


def class_loader_context(apk, libraries):
    """Return (host, stored) contexts matching the package manager's runtime context."""
    target_sdk, required, optional = manifest_libraries(apk)
    names = [name for name in dict.fromkeys(required+optional) if name not in REMOVED and name in libraries]
    for sdk, implicit in IMPLICIT:
        if target_sdk < sdk:
            names += [name for name in implicit if name not in names and name in libraries]

    def encode(name, seen):
        location, packaged, dependencies = libraries[name]
        host, stored = 'PCL['+str(packaged)+']', 'PCL['+location+']'
        nested = [encode(d, seen|{name}) for d in dependencies if d in libraries and d not in seen]
        if nested:
            host += '{'+'#'.join(h for h, _ in nested)+'}'
            stored += '{'+'#'.join(t for _, t in nested)+'}'
        return host, stored

    encoded = [encode(name, set()) for name in names]
    return ('PCL[]{'+'#'.join(h for h, _ in encoded)+'}',
            'PCL[]{'+'#'.join(t for _, t in encoded)+'}')


def has_dex(apk):
    with zipfile.ZipFile(apk) as archive:
        return 'classes.dex' in archive.namelist()


def preopt(system, out, product_name, selections, work):
    """Compile selected APKs. `selections` maps a system-relative APK path to a
    compiler filter. Returns reproducibility records for the candidate manifest."""
    compiler = out/'soong/host/linux-x86/bin/dex2oatd64'
    if not compiler.is_file():
        raise SystemExit('Missing source-built host dex2oat')
    host_images, boot_files, boot_locations = _global_inputs(out, product_name, system)
    empty_root = work/'dexpreopt-empty-root'
    empty_root.mkdir(exist_ok=True)
    libraries = shared_libraries(system)
    records = []
    for relative, compiler_filter in sorted(selections.items()):
        apk = system/relative
        if not apk.is_file() or not has_dex(apk):
            raise SystemExit('Not a compilable application: '+relative)
        device = '/'+relative if relative.split('/')[0] in ('product', 'system_ext', 'vendor') else '/system/'+relative
        target = apk.parent/'oat/arm'
        target.mkdir(parents=True, exist_ok=True)
        odex = target/(apk.stem+'.odex')
        # A context that differs from the package manager's would make the
        # runtime reject the compiled code; confirm the dexopt status on device.
        host_context, stored_context = class_loader_context(apk, libraries)
        command = [str(compiler), '--avoid-storing-invocation',
                   '--runtime-arg', '-Xms64m', '--runtime-arg', '-Xmx512m',
                   '--runtime-arg', '-Xbootclasspath:'+':'.join(boot_files),
                   '--runtime-arg', '-Xbootclasspath-locations:'+':'.join(boot_locations),
                   '--class-loader-context='+host_context,
                   '--stored-class-loader-context='+stored_context,
                   '--boot-image='+':'.join(host_images),
                   '--dex-file='+str(apk), '--dex-location='+device,
                   '--oat-file='+str(odex), '--android-root='+str(empty_root),
                   '--instruction-set=arm', '--instruction-set-variant=cortex-a7',
                   '--instruction-set-features=default',
                   '--no-generate-debug-info', '--generate-build-id',
                   '--abort-on-hard-verifier-error', '--force-determinism',
                   '--no-generate-mini-debug-info', '--copy-dex-files=false',
                   '--compiler-filter='+compiler_filter, '-j8']
        result = subprocess.run(command, capture_output=True, text=True)
        vdex = odex.with_suffix('.vdex')
        if result.returncode or not odex.is_file() or not vdex.is_file():
            errors = [line for line in result.stderr.splitlines()
                      if 'dex2oat.cc:3141]' not in line and 'native:' not in line]
            raise SystemExit('Host dex2oat failed for '+relative+':\n'+'\n'.join(errors)[:3000])
        for path in (odex, vdex):
            path.chmod(0o644)
        records.append({'apk': relative, 'filter': compiler_filter, 'class_loader_context': stored_context,
                        'odex_bytes': odex.stat().st_size, 'vdex_bytes': vdex.stat().st_size,
                        'odex_sha256': _digest(odex)})
    return records
