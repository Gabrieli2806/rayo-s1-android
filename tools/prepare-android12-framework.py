#!/usr/bin/env python3
"""Prepare an isolated framework recovery trial offline; never access a device."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--product-out', type=Path, required=True)
    parser.add_argument('--native-product-out', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--reuse-data-manifest', type=Path,
                        help='Retain the UUID of an earlier isolated trial data image')
    parser.add_argument('--local-launcher', action='store_true',
                        help='Include the separately built and signed lightweight launcher')
    parser.add_argument('--radios-product-out', type=Path,
                        help='Integrate the separately audited source radio product')
    args = parser.parse_args()
    if os.name != 'posix':
        raise SystemExit('Use native Linux/WSL ext4 to preserve the product layout')
    product = args.product_out.resolve()
    native = args.native_product_out.resolve()
    out = product.parents[2]
    host = out/'host/linux-x86/bin'
    variables = json.loads((out/'soong/soong.variables').read_text())
    for name, value in {'DeviceName': 'rayos1_tv12', 'DeviceCpuVariant': 'cortex-a7',
                        'DeviceArch': 'arm', 'Platform_sdk_version': 31, 'Binder32bit': True}.items():
        if variables.get(name) != value:
            raise SystemExit('Unexpected product configuration: '+name)
    if b'ro.rayo.graphics.legacy_sphal' not in (product/'system/bin/bootstrap/linkerconfig').read_bytes():
        raise SystemExit('Rebuild linkerconfig with the opt-in legacy graphics namespace')
    if b'ro.rayo.graphics.no_hwc_background' not in (product/'system/vendor/lib/libhwc2on1adapter.so').read_bytes():
        raise SystemExit('Rebuild libhwc2on1adapter with the guarded solid-color fallback')
    candidates = Path.home()/'android/rayo12-framework-candidates'
    candidates.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='candidate-', dir=candidates))
    system, ramdisk, mini = (work/name for name in ('system', 'ramdisk', 'graphics'))
    shutil.copytree(product/'system', system, symlinks=True)
    shutil.copytree(product/'root', ramdisk, symlinks=True)
    if args.local_launcher:
        apk = ROOT/'outputs/Rayo-Dark-TV.apk'
        record = json.loads((ROOT/'outputs/Rayo-Dark-TV.build.json').read_text(encoding='utf-8-sig'))
        if (record.get('package') != 'local.rayo.launcher' or record.get('google_services') is not False
                or digest(apk) != record['sha256']):
            raise SystemExit('Rebuild and verify the lightweight launcher first')
        destination = system/'app/RayoDark/RayoDark.apk'
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(apk, destination)
    # Declare the board's actual radio so SystemServer publishes WifiManager.
    # This is also required by the stock TV Settings connectivity screen.
    shutil.copyfile(args.source/'frameworks/native/data/etc/android.hardware.wifi.xml',
                    system/'etc/permissions/android.hardware.wifi.xml')
    shutil.copyfile(args.source/'frameworks/native/data/etc/android.hardware.usb.host.xml',
                    system/'etc/permissions/android.hardware.usb.host.xml')
    shutil.copyfile(ROOT/'work/android12/framework/ekt2101.kl', system/'usr/keylayout/ekt2101.kl')
    for name in ('rk29-keypad.kl', '20050030_pwm.kl'):
        from android12_input_maps import normalized
        (system/'usr/keylayout'/name).write_text(normalized(ROOT/'work/android81/input-keymaps-oreo'/name))
    validator = host/'validatekeymaps'
    if not validator.is_file():
        raise SystemExit('Build the source validatekeymaps host tool first')
    subprocess.run([str(validator)]+[str(system/'usr/keylayout'/name) for name in
                    ('ekt2101.kl', 'rk29-keypad.kl', '20050030_pwm.kl')], check=True)
    mini.mkdir()

    def copy(relative, original=None):
        original = original or product/'system'/relative
        if not original.is_file():
            raise SystemExit('Missing build artifact: '+str(original))
        target = mini/relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
        target.chmod(0o755 if relative.startswith('bin/') else 0o644)
        return target

    from android12_graphics_root import augment
    readelf = args.source/'prebuilts/clang/host/linux-x86/clang-r416183b/bin/llvm-readelf'
    _, graphics = augment(mini, product, native, ROOT, work, copy, readelf, True, True, audio=True)
    # Merge only the verified graphics closure, not the diagnostic init/services.
    for path in mini.rglob('*'):
        relative = path.relative_to(mini)
        if str(relative).startswith('etc/init/') or relative == Path('vendor/etc/vintf/manifest.xml'):
            continue
        target = system/relative
        if path.is_symlink():
            if target.exists() or target.is_symlink():
                if target.is_dir() and not target.is_symlink():
                    raise SystemExit('Unexpected graphics alias directory: '+str(target))
                target.unlink()
            target.symlink_to(os.readlink(path))
        elif path.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            if target.is_symlink():
                target.unlink()
            shutil.copyfile(path, target)
            target.chmod(path.stat().st_mode & 0o777)
    manifest_path = system/'vendor/etc/vintf/manifest.xml'
    if not manifest_path.exists():
        # This board has no source device manifest. Preserve installed software
        # HAL fragments and add the already verified graphics declarations.
        shutil.copyfile(mini/'vendor/etc/vintf/manifest.xml', manifest_path)
    device_manifest = ET.parse(manifest_path)
    for package, interface in (('android.hardware.audio', 'IDevicesFactory'),
                               ('android.hardware.audio.effect', 'IEffectsFactory')):
        if any(hal.findtext('name') == package for hal in device_manifest.getroot().findall('hal')):
            raise SystemExit('Unexpected preexisting audio declaration: '+package)
        hal = ET.SubElement(device_manifest.getroot(), 'hal', {'format': 'hidl'})
        ET.SubElement(hal, 'name').text = package
        ET.SubElement(hal, 'transport').text = 'hwbinder'
        ET.SubElement(hal, 'version').text = '7.0'
        declaration = ET.SubElement(hal, 'interface')
        ET.SubElement(declaration, 'name').text = interface
        ET.SubElement(declaration, 'instance').text = 'default'
    device_manifest.write(manifest_path, encoding='unicode')
    radio_records = []
    if args.radios_product_out:
        if b'ro.rayo.bluetooth.legacy_hci' not in (system/'lib/libbluetooth.so').read_bytes():
            raise SystemExit('Rebuild the Bluetooth client with the opt-in HCI 1.0 lookup')
        from android12_radio_root import stage
        radio_records = stage(system, args.radios_product_out.resolve(), ROOT,
                              args.source, work, readelf)
    shutil.copyfile(ROOT/'work/android12/framework/audio_policy_configuration.xml',
                    system/'vendor/etc/audio_policy_configuration.xml')
    for name in ('audio_policy_volumes.xml', 'default_volume_tables.xml'):
        shutil.copyfile(args.source/'frameworks/av/services/audiopolicy/config'/name,
                        system/'vendor/etc'/name)
    # The new C++ implementation must load in the new system namespace.
    for name in ('android.hardware.graphics.allocator@2.0-impl.so',
                 'android.hardware.graphics.mapper@2.0-impl.so', 'hwcomposer.rayo12.so'):
        old = system/'vendor/lib/hw'/name
        new = system/'lib/hw'/name
        if old.exists():
            if digest(old) != digest(new):
                raise SystemExit('Unexpected vendor implementation: '+name)
            old.unlink()
    # As in the passing native service profile, run the new graphics bridges
    # from the system namespace. A vendor namespace cannot open system/hw.
    for path in (system/'vendor/etc/init').glob('*.rc'):
        text = path.read_text()
        for name in ('android.hardware.graphics.allocator@2.0-service',
                     'android.hardware.graphics.composer@2.1-service', 'android.hardware.health@2.1-service',
                     'android.hardware.audio.service'):
            text = text.replace('/vendor/bin/hw/'+name, '/system/bin/'+name)
        path.write_text(text)
    bridge_files = ((product/'system/vendor/bin/hw/android.hardware.health@2.1-service',
                            system/'bin/android.hardware.health@2.1-service'),
                           (product/'system/vendor/lib/hw/android.hardware.health@2.0-impl-2.1.so',
                            system/'lib/hw/android.hardware.health@2.0-impl-2.1.so'),
                           (product/'system/vendor/bin/hw/android.hardware.audio.service',
                            system/'bin/android.hardware.audio.service'),
                           (product/'system/vendor/lib/hw/android.hardware.audio@7.0-impl.so',
                            system/'lib/hw/android.hardware.audio@7.0-impl.so'),
                           (product/'system/vendor/lib/hw/android.hardware.audio.effect@7.0-impl.so',
                            system/'lib/hw/android.hardware.audio.effect@7.0-impl.so'))
    for source, target in bridge_files:
        shutil.copyfile(source, target)
        target.chmod(0o755 if target.parent.name == 'bin' else 0o644)
    for _, target in bridge_files:
        if target.parent.name == 'hw':
            (system/'vendor/lib/hw'/target.name).unlink()
    pending = [target for _, target in bridge_files]
    scanned = set()
    while pending:
        path = pending.pop()
        if path in scanned:
            continue
        scanned.add(path)
        dynamic = subprocess.check_output([str(readelf), '-d', str(path)], text=True)
        for name in re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic):
            dependency = system/'lib'/name
            if dependency.is_symlink() or name in ('libc.so', 'libm.so', 'libdl.so', 'libdl_android.so'):
                continue
            if not dependency.exists():
                candidates = [p for p in (product/'system/vendor/lib'/name,
                    product/'system/apex/com.android.vndk.current/lib'/name) if p.is_file()]
                if not candidates or len({digest(p) for p in candidates}) != 1:
                    raise SystemExit('Missing or ambiguous health bridge dependency: '+name)
                shutil.copyfile(candidates[0], dependency)
            pending.append(dependency)
    validation = json.loads((ROOT/'outputs/android12-crypto-probe/VALIDATED.json').read_text())
    crypto = system/'lib/librayo12_crypto_legacy.so'
    accepted = {record['path']: record['sha256'] for record in validation['files']}
    if (not validation.get('passed') or validation.get('fips') is not False
            or validation.get('kernel') != '3.10.104'
            or accepted.get('lib/librayo12_crypto_legacy.so') != digest(crypto)):
        raise SystemExit('The board crypto backend has not passed its isolated device test')
    selected = 0
    for base in (system/'etc/init', system/'vendor/etc/init', system/'etc/init/hw'):
        for path in base.glob('*.rc'):
            lines = []
            for line in path.read_text().splitlines():
                lines.append(line)
                if line.startswith('service ') and any(name in line for name in
                        ('/vendor/bin/hw/android.hardware.keymaster@4.1-service', '/system/bin/keystore2 ',
                         '/system/bin/app_process -Xzygote')+
                        (('/system/bin/netd',) if args.radios_product_out else ())):
                    lines.append('    setenv LD_PRELOAD /system/lib/librayo12_crypto_legacy.so')
                    lines.append('    stdio_to_kmsg')
                    selected += 1
            path.write_text('\n'.join(lines)+'\n')
    if selected != (4 if args.radios_product_out else 3):
        raise SystemExit('Unexpected software key-service preload scope')
    sid_validation = json.loads((ROOT/'outputs/android12-sid-probe/VALIDATED.json').read_text())
    sid_source = ROOT/'work/android12/device/canon/rayos1_12_lab/legacy_sid.rs'
    if (not sid_validation.get('passed') or sid_validation.get('kernel') != '3.10.104'
            or sid_validation.get('helper_sha256') != digest(sid_source)
            or b'ro.rayo.keystore.legacy_pid_sid' not in (system/'bin/keystore2').read_bytes()):
        raise SystemExit('The exact legacy caller-context helper has not passed device validation')
    shutil.copyfile(product/'recovery/root/system/bin/adbd', system/'bin/rayo12-rescue-adbd')
    (system/'bin/rayo12-rescue-adbd').chmod(0o755)
    pending = [system/'bin/rayo12-rescue-adbd']
    scanned = set()
    while pending:
        path = pending.pop()
        if path in scanned:
            continue
        scanned.add(path)
        dynamic = subprocess.check_output([str(readelf), '-d', str(path)], text=True)
        for name in re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic):
            if name in ('libc.so', 'libm.so', 'libdl.so', 'libdl_android.so'):
                continue
            dependency = system/'lib'/name
            if not dependency.is_file():
                source = product/'recovery/root/system/lib'/name
                if not source.is_file():
                    raise SystemExit('Missing rescue dependency: '+name)
                shutil.copyfile(source, dependency)
            pending.append(dependency)
    init = ramdisk/'init'
    init.unlink()
    shutil.copyfile(native/'system/bin/rayo12_framework_init_bootstrap', init)
    init.chmod(0o755)
    (ramdisk/'sbin').mkdir(exist_ok=True)
    with tarfile.open(ROOT/'local/device-kit/runtime/trial-root-v14.tar.gz') as archive:
        for name in ('busybox', 'rayo_hwbinder.ko'):
            matches = [m for m in archive if m.name.lstrip('./') == 'sbin/'+name]
            if len(matches) != 1 or not matches[0].isfile():
                raise SystemExit('Unexpected baseline runtime archive')
            (ramdisk/'sbin'/name).write_bytes(archive.extractfile(matches[0]).read())
            (ramdisk/'sbin'/name).chmod(0o755)
        board_rules = archive.extractfile('./ueventd.rk30board.rc').read().decode()
        nodes = {'/dev/ion', '/dev/mali', '/dev/ump', '/dev/rga', '/dev/sw_sync'}
        (system/'vendor/etc/ueventd.rc').write_text('# Verified v14 graphics node permissions.\n'+
            '\n'.join(line for line in board_rules.splitlines() if line.split() and line.split()[0] in nodes)+'\n')
    shutil.copyfile(native/'system/bin/rayo12_stage1_props', ramdisk/'sbin/rayo12-props')
    for name in ('realdata', 'metadata'):
        (ramdisk/name).mkdir(exist_ok=True)
    # Avoid the product's cache -> /data/cache symlink before /data is mounted.
    cache = ramdisk/'cache'
    if cache.is_symlink():
        cache.unlink()
    cache.mkdir(exist_ok=True)
    policy = product/'system/vendor/etc/selinux/precompiled_sepolicy'
    shutil.copyfile(policy, ramdisk/'sepolicy')
    cil = system/'etc/selinux/plat_sepolicy.cil'
    cil.unlink()
    for relative in ('vendor/etc/init/vendor_flash_recovery.rc', 'etc/init/flash_recovery.rc'):
        path = system/relative
        if path.exists():
            path.write_text('# Recovery installation is excluded from isolated bringup.\n')
    for name in ('init.usb.rc', 'init.usb.configfs.rc'):
        (system/'etc/init/hw'/name).write_text('# USB is owned by the isolated trial rescue service.\n')
    init_rc = system/'etc/init/hw/init.rc'
    text = init_rc.read_text()
    if text.count('on late-init\n') != 1:
        raise SystemExit('Unexpected pinned framework boot dispatch')
    init_rc.write_text(text.replace('on late-init\n', 'on rayo12-framework-start\n'))
    rc = (ROOT/'work/android12/framework/init.rayo12.framework.rc').read_text()
    (system/'etc/init/rayo12.framework.rc').write_text(rc)
    subprocess.run([str(host/'host_init_verifier'),
                    '--property-contexts='+str(system/'etc/selinux/plat_property_contexts'),
                    str(system/'etc/init/rayo12.framework.rc')], check=True)
    if args.radios_product_out:
        subprocess.run([str(host/'host_init_verifier'),
                        '--property-contexts='+str(system/'etc/selinux/plat_property_contexts'),
                        str(system/'etc/init/rayo12.radios.rc')], check=True)
    # Never permit trial service failures to select Loader or another slot.
    for base in (system, ramdisk):
        for path in base.rglob('*.rc'):
            if path.is_symlink():
                continue
            text = path.read_text()
            text = re.sub(r'(?m)^(\s*reboot_on_failure)\s+.*$', r'\1 reboot', text)
            text = re.sub(r'(?m)^(\s*critical.*) target=\S+', r'\1 target=normal', text)
            path.write_text(text)
    # Vold sees only the already-mounted private loops; no physical data entry,
    # encryption, formatting, or removable-volume configuration is exposed.
    (system/'vendor/etc/fstab.rk30board').write_text(
        '/dev/block/loop7 /system ext4 ro wait\n'
        '/dev/block/loop6 /data ext4 noatime,nosuid,nodev wait\n')
    defaults = '''ro.secure=0
ro.adb.secure=0
ro.debuggable=1
ro.gabos.name=GabOS
ro.gabos.version=0.1-dev
ro.build.display.id=GabOS 0.1-dev
ro.product.model=GabOS for Rayo S1
ro.apex.updatable=false
ro.rayo.graphics.no_hwc_background=true
ro.config.low_ram=true
ro.product.first_api_level=25
ro.serialno=0123456789ABCDEF
ro.hardware=rk30board
ro.crypto.state=unencrypted
ro.crypto.type=none
ro.rayo.trial=12-framework
ro.rayo.adb.legacy_ffs=true
ro.rayo.graphics.ion_client_target=true
ro.rayo.graphics.legacy_sphal=true
ro.rayo.cgroup.legacy=true
ro.rayo.audio.legacy_stream_abi=true
ro.rayo.keystore.legacy_pid_sid=true
ro.hardware.hwcomposer=rayo12
ro.opengles.version=131072
debug.renderengine.backend=gles
debug.sf.no_hw_vsync=0
dalvik.vm.heapstartsize=8m
dalvik.vm.heapgrowthlimit=96m
dalvik.vm.heapsize=192m
dalvik.vm.heaptargetutilization=0.75
dalvik.vm.heapminfree=512k
dalvik.vm.heapmaxfree=8m
dalvik.vm.usejit=false
dalvik.vm.usejitprofiles=false
'''
    if args.radios_product_out:
        defaults += ('ro.rayo.bluetooth.legacy_vendor_sphal=true\n'
                     'ro.rayo.bluetooth.legacy_hci=true\n'
                     'bluetooth.enable_timeout_ms=8000\n'
                     'wifi.interface=wlan0\nro.bt.bdaddr_path=/data/misc/bluetooth/bdaddr\n')
    # Android 12's normal PropertyLoadBootDefaults does not read prop.default.
    # Preserve the complete source identity and use its canonical build.prop.
    original_properties = (system/'build.prop').read_text()
    full_properties = original_properties+'\n# Isolated framework trial properties.\n'+defaults
    (system/'etc/build.prop').write_text(full_properties)
    (system/'build.prop').write_text(full_properties)
    effective = dict(line.split('=', 1) for line in full_properties.splitlines()
                     if line and not line.startswith('#') and '=' in line)
    for key, value in {'ro.build.version.release': '12', 'ro.build.version.sdk': '31',
                       'ro.gabos.name': 'GabOS', 'ro.gabos.version': '0.1-dev',
                       'ro.rayo.trial': '12-framework', 'ro.rayo.adb.legacy_ffs': 'true',
                       'ro.rayo.cgroup.legacy': 'true', 'ro.rayo.graphics.ion_client_target': 'true',
                       'ro.rayo.graphics.legacy_sphal': 'true'}.items():
        if effective.get(key) != value:
            raise SystemExit('Canonical boot property validation failed: '+key)
    # Reproduce the disabled system-image target's normal provider generation.
    # A core-ART-only requireLibs list omits networking/stats/other APEX contracts.
    templates = list((out/'soong/.intermediates/system/core/rootdir/system_linker_config').glob('*/linker.config.pb'))
    variables_text = (out/'soong/make_vars-rayos1_tv12.mk').read_text()
    stubs = re.findall(r'^SOONG_STUB_LIBRARIES := (.+)$', variables_text, re.MULTILINE)
    if len(templates) != 1 or len(stubs) != 1:
        raise SystemExit('Missing or ambiguous product linker configuration inputs')
    subprocess.run([str(host/'conv_linker_config'), 'systemprovide', '--source', str(templates[0]),
                    '--output', str(system/'etc/linker.config.pb'), '--value', stubs[0],
                    '--system', str(system)], check=True)
    # Include partition aliases when labeling the combined physical /system tree.
    spec = importlib.util.spec_from_file_location('aosp_fc_sort', args.source/'system/sepolicy/tests/fc_sort.py')
    fc_sort = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fc_sort)

    def expanded_sorted(lines):
        expanded = []
        for line in lines:
            if line.startswith('/(vendor|system/vendor)'):
                expanded.extend(line.replace('/(vendor|system/vendor)', prefix, 1)
                                for prefix in ('/vendor', '/system/vendor'))
            else:
                expanded.append(line)
        nodes = [fc_sort.CreateNode(line.strip()) for line in dict.fromkeys(expanded)]
        nodes = [node for node in nodes if node is not None]
        nodes.sort(key=lambda node: (not node.meta, node.stemLen, node.strlen, node.fileType is not None))
        return [node.line for node in nodes]

    contexts = []
    for directory in ('etc/selinux', 'vendor/etc/selinux', 'product/etc/selinux', 'system_ext/etc/selinux'):
        for path in (system/directory).glob('*file_contexts'):
            lines = expanded_sorted(path.read_text().splitlines())
            if path.name == 'plat_file_contexts':
                lines = expanded_sorted(lines+[
                    '/system/bin/android\\.hardware\\.graphics\\.allocator@2\\.0-service u:object_r:hal_graphics_allocator_default_exec:s0',
                    '/system/bin/android\\.hardware\\.graphics\\.composer@2\\.1-service u:object_r:hal_graphics_composer_default_exec:s0',
                    '/system/bin/android\\.hardware\\.health@2\\.1-service u:object_r:hal_health_default_exec:s0',
                    '/system/bin/android\\.hardware\\.audio\\.service u:object_r:hal_audio_default_exec:s0',
                    '/system/bin/android\\.hardware\\.wifi@1\\.0-service u:object_r:hal_wifi_default_exec:s0',
                    '/system/bin/android\\.hardware\\.bluetooth@1\\.0-service u:object_r:hal_bluetooth_default_exec:s0',
                    '/system/bin/wpa_supplicant u:object_r:hal_wifi_supplicant_default_exec:s0'])
            if directory == 'vendor/etc/selinux':
                lines = expanded_sorted(lines+[
                    '/vendor/bin/hw/android\\.hardware\\.gatekeeper@1\\.0-service\\.software u:object_r:hal_gatekeeper_default_exec:s0',
                    '/system/vendor/bin/hw/android\\.hardware\\.gatekeeper@1\\.0-service\\.software u:object_r:hal_gatekeeper_default_exec:s0'])
            path.write_text('\n'.join(lines)+'\n')
            for line in lines:
                contexts.append(line)
                if re.match(r'^/(vendor|product|system_ext)(/|\()', line):
                    contexts.append('/system'+line)
    context_file = work/'file_contexts'
    context_file.write_text('\n'.join(expanded_sorted(contexts))+'\n')
    env = dict(os.environ, MKE2FS_CONFIG=str(host/'mke2fs.conf'))
    features = 'none,extent,filetype,sparse_super,large_file,dir_index,ext_attr,flex_bg,uninit_bg,dir_nlink,extra_isize'
    system_image = work/'rayo12-framework-system.raw.img'
    size = 800*1024*1024
    subprocess.run([str(host/'mke2fs'), '-t', 'ext4', '-b', '4096', '-I', '256', '-O', features,
                    '-F', str(system_image), str(size//4096)], env=env, check=True)
    subprocess.run([str(host/'e2fsdroid'), '-e', '-f', str(system), '-a', '/system', '-S', str(context_file), str(system_image)], check=True)
    radio_capabilities = {}
    if args.radios_product_out:
        # This kernel predates ambient capabilities. Keep services non-root and
        # grant only their verified executable file capabilities on the RO loop.
        for name, caps in (('android.hardware.wifi@1.0-service', 0x3000),
                           ('wpa_supplicant', 0x3000),
                           ('android.hardware.bluetooth@1.0-service', 0x1000801000),
                           ('wificond', 0x3000), ('netd', 0x74ef)):
            value = struct.pack('<IIIII', 0x02000001, caps & 0xffffffff, 0, caps >> 32, 0)
            encoded, checked = work/'radio-capability-value', work/'radio-capability-check'
            encoded.write_bytes(value)
            subprocess.run(['debugfs', '-w', '-R', 'ea_set -f '+str(encoded)+' /bin/'+name+
                            ' security.capability', str(system_image)], check=True)
            checked.unlink(missing_ok=True)
            subprocess.run(['debugfs', '-R', 'ea_get -f '+str(checked)+' /bin/'+name+
                            ' security.capability', str(system_image)], check=True)
            if checked.read_bytes() != value:
                raise SystemExit('Radio capability readback differs: '+name)
            radio_capabilities[name] = caps
    for path, label in (('/bin/init', 'init_exec'),
                        ('/bin/app_process32', 'zygote_exec'),
                        ('/vendor/bin/boringssl_self_test32', 'vendor_boringssl_self_test_exec'),
                        ('/vendor/bin/hw/android.hardware.keymaster@4.1-service', 'hal_keymaster_default_exec'),
                        ('/vendor/bin/hw/android.hardware.gatekeeper@1.0-service.software', 'hal_gatekeeper_default_exec'),
                        ('/bin/android.hardware.graphics.allocator@2.0-service', 'hal_graphics_allocator_default_exec'),
                        ('/bin/android.hardware.graphics.composer@2.1-service', 'hal_graphics_composer_default_exec'),
                        ('/bin/android.hardware.health@2.1-service', 'hal_health_default_exec'),
                        ('/bin/android.hardware.audio.service', 'hal_audio_default_exec'),
                        ('/vendor/bin/hw/android.hardware.graphics.composer@2.1-service', 'hal_graphics_composer_default_exec')):
        attribute = work/'checked-label'
        subprocess.run(['debugfs', '-R', 'ea_get -f '+str(attribute)+' '+path+' security.selinux', str(system_image)], check=True)
        if not attribute.is_file() or attribute.read_bytes().rstrip(b'\0') != ('u:object_r:'+label+':s0').encode():
            raise SystemExit('Missing execution label: '+path)
        attribute.unlink()
    data_image = work/'rayo12-framework-data.ext4'
    if args.radios_product_out:
        for name, label in (('android.hardware.wifi@1.0-service', 'hal_wifi_default_exec'),
                            ('android.hardware.bluetooth@1.0-service', 'hal_bluetooth_default_exec'),
                            ('wpa_supplicant', 'hal_wifi_supplicant_default_exec')):
            attribute = work/'checked-radio-label'
            attribute.unlink(missing_ok=True)
            subprocess.run(['debugfs', '-R', 'ea_get -f '+str(attribute)+' /bin/'+name+
                            ' security.selinux', str(system_image)], check=True)
            if attribute.read_bytes().rstrip(b'\0') != ('u:object_r:'+label+':s0').encode():
                raise SystemExit('Radio executable label differs: '+name)
    data_uuid = uuid.UUID(json.loads(args.reuse_data_manifest.read_text())['data']['uuid']) if args.reuse_data_manifest else uuid.uuid4()
    subprocess.run([str(host/'mke2fs'), '-t', 'ext4', '-b', '4096', '-I', '256', '-O', features,
                    '-U', str(data_uuid), '-F', str(data_image), str(256*1024*1024//4096)], env=env, check=True)
    (ramdisk/'rayo12-framework-data.cfg').write_text('data_uuid='+data_uuid.hex+'\ndata_bytes=268435456\n')
    (ramdisk/'rayo12-stage1.cfg').write_text('system_sha256='+digest(system_image)+'\nsystem_bytes='+str(size)+'\n')
    image = work/'rayo12-framework-recovery.img'
    subprocess.run(['python3', str(ROOT/'tools/pack-recovery.py'), '--ramdisk', str(ramdisk), '--output', str(image),
                    '--cmdline', 'buildvariant=eng androidboot.selinux=permissive androidboot.init_fatal_reboot_target=normal'], check=True)
    spec = importlib.util.spec_from_file_location('verify', ROOT/'tools/verify-rayo-boot.py')
    verify = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify)
    verified = verify.inspect(image.read_bytes(), (ROOT/'local/device-kit/original/recovery.img').read_bytes(), True)
    manifest = {'purpose': 'Temporary full-framework bringup; not a validated ROM or Google TV Home',
        'radio_services': radio_records, 'radio_file_capabilities': radio_capabilities,
        'local_launcher': record if args.local_launcher else None,
        'system': {'path': system_image.name, 'bytes': size, 'sha256': digest(system_image)},
        'data': {'path': data_image.name, 'bytes': data_image.stat().st_size, 'uuid': str(data_uuid), 'sha256': digest(data_image)},
        'recovery': verified, 'watchdog_seconds': 600, 'device_written': False,
        'graphics': graphics, 'bootstrap_sha256': digest(init),
        'linker_template_sha256': digest(templates[0]), 'framework_requires_trigger': True,
        'android12_init_md5': hashlib.md5((system/'bin/init').read_bytes()).hexdigest(),
        'software_key_crypto': {'sha256': digest(crypto), 'fips': False,
            'scope': 'Software Keymaster, keystore2 and zygote children use the non-FIPS backend; '
                     + ('radio Wi-Fi/supplicant and netd services also preload it; ' if args.radios_product_out else '')
                     + 'platform FIPS module unchanged'},
        'audio': 'Source HIDL 7 bridge with original primary driver; physical audio not validated',
        'legacy_sid': {'helper_sha256': digest(sid_source),
            'scope': 'Opt-in current kernel proc context; pinned PID and verified Binder UID; policy checks retained'}}
    (work/'MANIFEST.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print('Offline framework candidate:', work)


if __name__ == '__main__':
    main()
