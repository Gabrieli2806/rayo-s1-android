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
import zipfile
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
    parser.add_argument('--gabos-apps', action='store_true',
                        help='Include checksum-verified Media, Diagnostics and Share with native DocumentsUI')
    parser.add_argument('--software-media', action='store_true',
                        help='Declare source OMX software codecs for baseline media validation')
    parser.add_argument('--legacy-media', action='store_true',
                        help='Opt into the experimental source facade over factory OMX C APIs')
    parser.add_argument('--data-mib', type=int, default=256,
                        help='Size of a newly created private data image (256-3072 MiB)')
    parser.add_argument('--data-image', type=Path,
                        help='Use this already grown private data image; requires --reuse-data-manifest')
    parser.add_argument('--watchdog-seconds', type=int, default=600,
                        help='Independent return-to-normal timer packaged in the ramdisk (600-14400)')
    parser.add_argument('--installed-boot', action='store_true',
                        help='Start the framework without a host trigger and end the watchdog on a '
                             'completed boot; also emit the image for the boot slot')
    parser.add_argument('--preopt-filter', default='speed', choices=('speed', 'verify', 'none'),
                        help='Host ahead-of-time compiler filter for packaged applications')
    args = parser.parse_args()
    if args.legacy_media and not args.software_media:
        parser.error('--legacy-media requires --software-media for explicit OMX declarations')
    if not 256 <= args.data_mib <= 3072 or not 600 <= args.watchdog_seconds <= 14400:
        parser.error('Data size or watchdog window is outside the bootstrap limits')
    if args.data_image and not args.reuse_data_manifest:
        parser.error('--data-image requires --reuse-data-manifest for its expected UUID')
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
    if b'debug.rayo.graphics.client_blend' not in (product/'system/vendor/lib/libhwc2on1adapter.so').read_bytes():
        raise SystemExit('Rebuild the opt-in translucent-frame client compositor')
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
        # A privileged system app: its GabOS Bluetooth page connects and forgets
        # devices (BLUETOOTH_PRIVILEGED). Android 12 refuses to boot when a
        # privileged app requests a privileged permission that is not listed,
        # so the list below must cover every one the launcher requests.
        destination = system/'priv-app/RayoDark/RayoDark.apk'
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(apk, destination)
        (system/'etc/permissions').mkdir(parents=True, exist_ok=True)
        (system/'etc/permissions/privapp-permissions-gabos-home.xml').write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<permissions>\n'
            '    <privapp-permissions package="local.rayo.launcher">\n'
            '        <permission name="android.permission.BLUETOOTH_PRIVILEGED"/>\n'
            '        <permission name="android.permission.CHANGE_CONFIGURATION"/>\n'
            '    </privapp-permissions>\n</permissions>\n')
        # The launcher package carries the pointer- and D-pad-capable GabOS
        # keyboard. The D-pad-only Leanback keyboard could not be operated with
        # the projector's pointer (owner report, run 20261111-211628).
        with zipfile.ZipFile(apk) as archive:
            has_keyboard = b'KeyboardService' in archive.read('classes.dex')
        leanback_ime = system/'product/app/LeanbackIME'
        if has_keyboard and leanback_ime.is_dir():
            shutil.rmtree(leanback_ime)
    gabos_apps = []
    if args.gabos_apps:
        if not (system/'priv-app/DocumentsUI/DocumentsUI.apk').is_file():
            raise SystemExit('Build the native DocumentsUI target first')
        for name in ('Media', 'Diagnostics', 'Share'):
            apk = ROOT/'outputs'/('GabOS-'+name+'.apk')
            metadata = json.loads(Path(str(apk)+'.build.json').read_text(encoding='utf-8-sig'))
            if (metadata.get('package') != 'local.gabos.'+name.lower()
                    or metadata.get('google_services') is not False
                    or digest(apk) != metadata.get('sha256')):
                raise SystemExit('Rebuild and verify GabOS '+name)
            target = system/'app'/('GabOS'+name)/apk.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(apk, target)
            gabos_apps.append(metadata)

        # Hardware Check (validation app) as an ordinary system app.
        check = ROOT/'outputs/Rayo-Hardware-Check.apk'
        if check.is_file():
            target = system/'app/GabOSHardwareCheck/HardwareCheck.apk'
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(check, target)
            gabos_apps.append({'package': 'local.rayo.validation', 'sha256': digest(check), 'google_services': False})
        # Aurora Store is preinstalled (owner request). The pinned F-Droid APK
        # keeps its original signature, so it can update itself normally.
        pins = {item['package']: item for item in json.loads((ROOT/'manifests/gabos-optional-apps.json').read_text())}
        aurora_pin = pins['com.aurora.store']
        aurora = ROOT/'local/device-kit/apps'/aurora_pin['file']
        if not aurora.is_file() or digest(aurora) != aurora_pin['sha256']:
            raise SystemExit('Missing or unverified pinned Aurora Store APK')
        target = system/'app/AuroraStore/AuroraStore.apk'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(aurora, target)
        gabos_apps.append({'package': 'com.aurora.store', 'sha256': aurora_pin['sha256'], 'google_services': False})
        # Owner apps from the separate apps workspace (GRemote pointer, GBrowse),
        # listed in manifests/gabos-partner-apps.json and copied, verified, to
        # local/device-kit/apps/partner/. They keep their own release signature,
        # so later builds install over them as normal updates. Missing files are
        # skipped with a notice; a file with the wrong package is refused.
        partner_list = ROOT/'manifests/gabos-partner-apps.json'
        for item in json.loads(partner_list.read_text()) if partner_list.is_file() else []:
            apk = ROOT/'local/device-kit/apps/partner'/item['file']
            if not apk.is_file():
                print('Partner app not staged (missing %s)' % apk.name)
                continue
            with zipfile.ZipFile(apk) as archive:
                if archive.testzip() is not None:
                    raise SystemExit('Corrupt partner APK '+apk.name)
                names = archive.namelist()
                if any(name.startswith('lib/') for name in names) and not any(name.startswith('lib/armeabi-v7a/') for name in names):
                    raise SystemExit(apk.name+' has native code but no armeabi-v7a libraries')
                target = system/'app'/item['dir']/(item['dir']+'.apk')
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(apk, target)
                # System apps do not extract native code at install; provide it beside the APK.
                for name in names:
                    if name.startswith('lib/armeabi-v7a/') and name.endswith('.so'):
                        library = target.parent/'lib/arm'/Path(name).name
                        library.parent.mkdir(parents=True, exist_ok=True)
                        library.write_bytes(archive.read(name))
            gabos_apps.append({'package': item['package'], 'sha256': digest(apk), 'google_services': False})

    # GabOS wordmark boot animation (tools/make-gabos-bootanimation.py).
    animation = ROOT/'outputs/gabos-bootanimation.zip'
    if not animation.is_file():
        raise SystemExit('Render the boot animation with tools/make-gabos-bootanimation.py')
    with zipfile.ZipFile(animation) as archive:
        if any(info.compress_type != zipfile.ZIP_STORED for info in archive.infolist()) or 'desc.txt' not in archive.namelist():
            raise SystemExit('Boot animation must be an uncompressed zip with desc.txt')
    (system/'media').mkdir(exist_ok=True)
    shutil.copyfile(animation, system/'media/bootanimation.zip')

    boot_script = system/'bin/gabos-boot'
    shutil.copyfile(ROOT/'work/android12/framework/gabos-boot.sh', boot_script)
    boot_script.chmod(0o755)
    stage_script = system/'bin/gabos-update-stage'
    shutil.copyfile(ROOT/'work/android12/framework/gabos-update-stage.sh', stage_script)
    stage_script.chmod(0o755)
    # Speaker pop helper (device/canon/rayos1_12_lab/codec_quiet_start.c), a
    # static binary that is not part of the product packages.
    quiet = out/'soong/.intermediates/device/canon/rayos1_12_lab/gabos-codec-quiet/android_arm_armv7-a-neon_cortex-a7/gabos-codec-quiet'
    if not quiet.is_file():
        raise SystemExit('Build gabos-codec-quiet with tools/build-android12-tv.sh first')
    shutil.copyfile(quiet, system/'bin/gabos-codec-quiet')
    (system/'bin/gabos-codec-quiet').chmod(0o755)
    # GabOS 1.0 ships no Google packages; the Google TV staging path is gone.

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
    # Input device configuration: InputReader looks these up by device name
    # with characters other than [A-Za-z0-9_-] replaced by '_' (20050030.pwm).
    # They class the panel, side keys and remote as external so that motion
    # and key presses wake the projector after the inactivity light-off.
    idc_names = ('ekt2101.idc', 'rk29-keypad.idc', '20050030_pwm.idc')
    (system/'usr/idc').mkdir(parents=True, exist_ok=True)
    for name in idc_names:
        shutil.copyfile(ROOT/'work/android12/framework/idc'/name, system/'usr/idc'/name)
        (system/'usr/idc'/name).chmod(0o644)
    validator = host/'validatekeymaps'
    if not validator.is_file():
        raise SystemExit('Build the source validatekeymaps host tool first')
    subprocess.run([str(validator)]+[str(system/'usr/keylayout'/name) for name in
                    ('ekt2101.kl', 'rk29-keypad.kl', '20050030_pwm.kl')]
                   +[str(system/'usr/idc'/name) for name in idc_names], check=True)
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
    _, graphics = augment(mini, product, native, ROOT, work, copy, readelf, True, True, audio=True, legacy_media=args.legacy_media)
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
    # With the fixed HIDL lookup delay removed, every HAL that is registered on
    # the board must be declared so late starters are awaited normally. Run
    # 20261111-211628 showed configstore registered and the passthrough mapper
    # used, both undeclared.
    declared = {hal.findtext('name') for hal in device_manifest.getroot().findall('hal')}
    for package, transport, version, interface in (
            ('android.hardware.configstore', 'hwbinder', '1.1', 'ISurfaceFlingerConfigs'),
            ('android.hardware.graphics.mapper', 'passthrough', '2.0', 'IMapper')):
        if package in declared:
            continue
        hal = ET.SubElement(device_manifest.getroot(), 'hal', {'format': 'hidl'})
        ET.SubElement(hal, 'name').text = package
        node = ET.SubElement(hal, 'transport')
        node.text = transport
        if transport == 'passthrough':
            node.set('arch', '32')
        ET.SubElement(hal, 'version').text = version
        declaration = ET.SubElement(hal, 'interface')
        ET.SubElement(declaration, 'name').text = interface
        ET.SubElement(declaration, 'instance').text = 'default'
    if args.software_media:
        service = system/'vendor/bin/hw/android.hardware.media.omx@1.0-service'
        if not service.is_file():
            raise SystemExit('Build android.hardware.media.omx@1.0-service first')
        hal = ET.SubElement(device_manifest.getroot(), 'hal', {'format': 'hidl'})
        ET.SubElement(hal, 'name').text = 'android.hardware.media.omx'
        ET.SubElement(hal, 'transport').text = 'hwbinder'
        ET.SubElement(hal, 'version').text = '1.0'
        for interface in ('IOmx', 'IOmxStore'):
            declaration = ET.SubElement(hal, 'interface')
            ET.SubElement(declaration, 'name').text = interface
            ET.SubElement(declaration, 'instance').text = 'default'
        codec_source = args.source/'frameworks/av/media/libstagefright/data'
        for filename in ('media_codecs_google_audio.xml', 'media_codecs_google_video.xml'):
            shutil.copyfile(codec_source/filename, system/'vendor/etc'/filename)
        if args.legacy_media:
            if (b'ro.rayo.media.legacy_omx' not in (system/'lib/libstagefright_omx.so').read_bytes()
                    or not (system/'lib/librayo12_omx_plugin.so').is_file()):
                raise SystemExit('Build the source OMX facade and opt-in loader first')
            if b'OMX.rk.video_decoder.hevc' not in (system/'lib/librayo12_omx_plugin.so').read_bytes():
                raise SystemExit('Rebuild the OMX facade with the HEVC decoder exposed')
            vpuapi = (system/'vendor/lib/legacy/librk_vpuapi.so').read_bytes()
            if (not (system/'vendor/lib/legacy/rkhevc.so').is_file()
                    or b'/vendor/lib/legacy/rkhevc.so' not in vpuapi
                    or b'/system/lib/librk_hevcdec.so' in vpuapi):
                raise SystemExit('Stage the factory HEVC library and the patched vpuapi first')
            with tarfile.open(ROOT/'local/device-kit/runtime/rayo81-live-overlay.tar.gz') as archive:
                members = [m for m in archive if m.isfile() and m.name.endswith('/etc/media_codecs.xml')]
                if len(members) != 1:
                    raise SystemExit('Expected one pinned v14 codec declaration')
                prior_codecs = ET.fromstring(archive.extractfile(members[0]).read())
            decoders = [node for node in prior_codecs.findall('./Decoders/MediaCodec')
                        if node.get('name') == 'OMX.rk.video_decoder.avc']
            if len(decoders) != 1:
                raise SystemExit('Expected the verified v14 AVC entry')
            # The other factory video decoders the facade exposes, with the
            # limits and quirks of the factory 7.1.2 declaration.
            factory_xml = subprocess.run(['debugfs', '-R', 'cat /etc/media_codecs.xml',
                                          str(ROOT/'local/device-kit/original/system.img')],
                                         capture_output=True, check=True).stdout
            factory = {node.get('name'): node for node in ET.fromstring(factory_xml).findall('./Decoders/MediaCodec')}
            # HEVC uses the staged factory HEVC library (android12_graphics_root).
            for name in ('OMX.rk.video_decoder.vp8', 'OMX.rk.video_decoder.m4v',
                         'OMX.rk.video_decoder.m2v', 'OMX.rk.video_decoder.h263',
                         'OMX.rk.video_decoder.hevc'):
                if name not in factory:
                    raise SystemExit('Missing factory codec declaration: '+name)
                decoders.append(factory[name])
            included = ET.Element('Included')
            container = ET.SubElement(included, 'Decoders')
            for node in decoders:
                # Prefer the hardware entries over C2 software without changing their limits.
                node.set('rank', '1')
                container.append(node)
            ET.ElementTree(included).write(system/'vendor/etc/media_codecs_rayo.xml', encoding='unicode')
            init_media = system/'etc/init/init.rayo12.media.rc'
            # The factory HEVC decoder (via libvpu.so) uses the separate RK3128
            # HEVC engine node, which ueventd otherwise leaves root-only.
            init_media.write_text(
                'on post-fs-data\n'
                '    chown mediacodec media /dev/vpu_service\n'
                '    chmod 0660 /dev/vpu_service\n'
                '    chown mediacodec media /dev/hevc_service\n'
                '    chmod 0660 /dev/hevc_service\n')
        (system/'vendor/etc/media_codecs.xml').write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<MediaCodecs>\n'
            + ('  <Include href="media_codecs_rayo.xml"/>\n' if args.legacy_media else '')
            + '  <Include href="media_codecs_google_audio.xml"/>\n'
            '  <Include href="media_codecs_google_video.xml"/>\n</MediaCodecs>\n')

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
    if args.installed_boot:
        # No host is required: start the framework at the normal late-init
        # point and tell the bootstrap's boot-success watchdog when Android
        # reports a completed boot.
        rc += ('\non late-init\n    trigger rayo12-framework-start\n'
               '\non property:sys.boot_completed=1\n    write /dev/.rayo12-boot-ok 1\n')
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
        '/dev/block/loop6 /data ext4 noatime,nosuid,nodev wait\n'
        # Lets init write the recovery command for "reboot,recovery" (the rescue
        # slot). Not mounted; the rescue clears the command again.
        '/dev/block/platform/1021c000.rksdmmc/by-name/misc /misc emmc defaults defaults\n')
    defaults = '''ro.secure=0
ro.adb.secure=0
ro.debuggable=1
ro.gabos.name=GabOS
ro.gabos.version=1.4
ro.build.display.id=GabOS 1.4
ro.build.type=userdebug
ro.system.build.type=userdebug
# The panel is 1280x720. Players (ExoPlayer on TVs) read these to size their
# track choice to the native resolution instead of choosing 1080p streams.
sys.display-size=1280x720
vendor.display-size=1280x720
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
ro.rayo.audio.pcm_soft_start=true
ro.audio.flinger_standbytime_ms=1800000
debug.rayo.graphics.client_blend=1
debug.hwui.use_buffer_age=false
debug.hwui.use_partial_updates=false
ro.rayo.keystore.legacy_pid_sid=true
ro.rayo.hidl.no_legacy_wait=true
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
    if args.legacy_media:
        defaults += 'ro.rayo.media.legacy_omx=true\n'
    if args.radios_product_out:
        defaults += ('ro.rayo.bluetooth.legacy_vendor_sphal=true\n'
                     'ro.rayo.bluetooth.legacy_hci=true\n'
                     'bluetooth.enable_timeout_ms=8000\n'
                     'wifi.interface=wlan0\nro.bt.bdaddr_path=/data/misc/bluetooth/bdaddr\n')
    # The isolated legacy namespace keeps its own factory-era transport library.
    hidl_copies = [path for path in system.rglob('libhidlbase.so')
                   if path.is_file() and not path.is_symlink() and 'legacy' not in path.parts]
    if not hidl_copies or any(b'ro.rayo.hidl.no_legacy_wait' not in path.read_bytes() for path in hidl_copies):
        raise SystemExit('Rebuild every packaged libhidlbase with the opt-in lookup-delay removal')
    if b'ro.rayo.audio.pcm_soft_start' not in (system/'lib/hw/android.hardware.audio@7.0-impl.so').read_bytes():
        raise SystemExit('Rebuild the PCM soft-start audio implementation first')
    tv_apks = list(system.rglob('TvSettings.apk'))
    if len(tv_apks) != 1:
        raise SystemExit('Expected one native TV Settings application')
    with zipfile.ZipFile(tv_apks[0]) as apk:
        if any(b'ro.gabos.settings.opaque' in apk.read(name)
                   for name in apk.namelist() if name.endswith('.dex')):
            raise SystemExit('Rebuild native TV Settings after restoring its transparency')
    # Android 12's normal PropertyLoadBootDefaults does not read prop.default.
    # Preserve the complete source identity and use its canonical build.prop.
    original_properties = (system/'build.prop').read_text()
    full_properties = original_properties+'\n# Isolated framework trial properties.\n'+defaults
    (system/'etc/build.prop').write_text(full_properties)
    (system/'build.prop').write_text(full_properties)
    effective = dict(line.split('=', 1) for line in full_properties.splitlines()
                     if line and not line.startswith('#') and '=' in line)
    for key, value in {'ro.build.version.release': '12', 'ro.build.version.sdk': '31',
                       'ro.gabos.name': 'GabOS', 'ro.gabos.version': '1.4',
                       'ro.build.display.id': 'GabOS 1.4', 'ro.product.model': 'GabOS for Rayo S1',
                       'ro.rayo.trial': '12-framework', 'ro.rayo.adb.legacy_ffs': 'true',
                       'ro.rayo.cgroup.legacy': 'true', 'ro.rayo.graphics.ion_client_target': 'true',
                       'ro.rayo.graphics.legacy_sphal': 'true',
                       # An eng build type makes every Log.wtf in an app fatal
                       # (ActivityManagerService: Build.IS_ENG). TV Settings logs
                       # wtf for the absent HDMI-CEC service and was killed when
                       # opening Bluetooth accessories. ro.debuggable/ro.secure
                       # above keep root ADB.
                       'ro.build.type': 'userdebug', 'ro.system.build.type': 'userdebug'}.items():
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
    preopt_records = []
    if args.preopt_filter != 'none':
        # The eng product compiles only the boot image and system server, and
        # the board runs without JIT; compile every packaged application here.
        from android12_dexpreopt import has_dex, preopt
        selections = {str(apk.relative_to(system)): args.preopt_filter
                      for apk in sorted(system.rglob('*.apk'))
                      if 'overlay' not in apk.relative_to(system).parts and has_dex(apk)}
        preopt_records = preopt(system, out, 'rayos1_tv12', selections, work)
    env = dict(os.environ, MKE2FS_CONFIG=str(host/'mke2fs.conf'))
    features = 'none,extent,filetype,sparse_super,large_file,dir_index,ext_attr,flex_bg,uninit_bg,dir_nlink,extra_isize'
    system_image = work/'rayo12-framework-system.raw.img'
    # Keep the long-validated 800 MiB image when the tree fits; otherwise grow
    # in 16 MiB steps with filesystem overhead and a small margin.
    tree_bytes = sum(path.lstat().st_size+4096 for path in system.rglob('*'))
    size = max(800, -(-(tree_bytes*104//100+16*1024*1024)//(16*1024*1024))*16)*1024*1024
    if size > 2047*1024*1024:
        raise SystemExit('System tree exceeds the bootstrap image limit')
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
    data_replaces = None
    if args.data_image:
        # A grown copy of the retained private data (tools/grow-android12-data.py).
        grown = json.loads(Path(str(args.data_image)+'.json').read_text(encoding='utf-8'))
        if grown.get('sha256') != digest(args.data_image) or grown.get('uuid') != str(data_uuid):
            raise SystemExit('Grown data image does not match its record')
        data_replaces = {'md5': grown['source_md5'], 'bytes': grown['source_bytes'],
                         'source_run': grown['source_run']}
        shutil.copyfile(args.data_image, data_image)
        with data_image.open('rb') as stream:
            stream.seek(1024)
            superblock = stream.read(1024)
        compat, incompat, ro_compat = struct.unpack_from('<III', superblock, 92)
        if (superblock[56:58] != bytes((0x53, 0xef)) or superblock[104:120] != data_uuid.bytes
                or (compat & ~0x28) or (incompat & ~0x242) or (ro_compat & ~0x73)):
            raise SystemExit('Grown data image identity or features differ from the bootstrap guard')
    else:
        subprocess.run([str(host/'mke2fs'), '-t', 'ext4', '-b', '4096', '-I', '256', '-O', features,
                        '-U', str(data_uuid), '-F', str(data_image), str(args.data_mib*1024*1024//4096)], env=env, check=True)
    data_bytes = data_image.stat().st_size
    if data_bytes % 4096 or not 268435456 <= data_bytes <= 3221225472:
        raise SystemExit('Private data image size is outside the bootstrap limits')
    (ramdisk/'rayo12-framework-data.cfg').write_text('data_uuid='+data_uuid.hex+'\ndata_bytes='+str(data_bytes)+'\n')
    (ramdisk/'rayo12-watchdog.cfg').write_text('watchdog_seconds='+str(args.watchdog_seconds)+'\n')
    if (b'/rayo12-watchdog.cfg' not in init.read_bytes()
            or b'private %llu MiB data mounted' not in init.read_bytes()):
        raise SystemExit('Rebuild the bootstrap with the bounded data size and watchdog records')
    (ramdisk/'rayo12-stage1.cfg').write_text('system_sha256='+digest(system_image)+'\nsystem_bytes='+str(size)+'\n')
    image = work/'rayo12-framework-recovery.img'
    boot_image = None
    if args.installed_boot:
        if b'boot_success_fallback=recovery' not in init.read_bytes():
            raise SystemExit('Rebuild the bootstrap with the boot-success watchdog')
        # Boot-slot image first: a failed boot restarts into the recovery slot,
        # which must then hold the rescue system. The recovery-slot rehearsal
        # image is identical except that it falls back to the normal boot slot.
        boot_image = work/'rayo12-framework-boot.img'
        (ramdisk/'rayo12-boot-mode.cfg').write_text('boot_success_fallback=recovery\n')
        subprocess.run(['python3', str(ROOT/'tools/pack-recovery.py'), '--ramdisk', str(ramdisk), '--output', str(boot_image),
                        '--cmdline', 'buildvariant=eng androidboot.selinux=permissive androidboot.init_fatal_reboot_target=normal'], check=True)
        (ramdisk/'rayo12-boot-mode.cfg').write_text('boot_success_fallback=normal\n')
    subprocess.run(['python3', str(ROOT/'tools/pack-recovery.py'), '--ramdisk', str(ramdisk), '--output', str(image),
                    '--cmdline', 'buildvariant=eng androidboot.selinux=permissive androidboot.init_fatal_reboot_target=normal'], check=True)
    spec = importlib.util.spec_from_file_location('verify', ROOT/'tools/verify-rayo-boot.py')
    verify = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify)
    verified = verify.inspect(image.read_bytes(), (ROOT/'local/device-kit/original/recovery.img').read_bytes(), True)
    verified_boot = (verify.inspect(boot_image.read_bytes(), (ROOT/'local/device-kit/original/recovery.img').read_bytes(), True)
                     if boot_image else None)
    manifest = {'purpose': 'Guarded full-framework trial candidate; not a validated stable ROM',
        'gabos_apps': gabos_apps,
        'software_media': args.software_media,
        'legacy_media': args.legacy_media,
        'native_settings_transparent': True, 'client_blend_fallback': True,
        'hwui_full_redraw': True, 'pcm_soft_start_ms': 100,
        'radio_services': radio_records, 'radio_file_capabilities': radio_capabilities,
        'local_launcher': record if args.local_launcher else None,
        'system': {'path': system_image.name, 'bytes': size, 'sha256': digest(system_image)},
        'data': dict({'path': data_image.name, 'bytes': data_bytes, 'uuid': str(data_uuid), 'sha256': digest(data_image)},
                     **({'replaces': data_replaces} if data_replaces else {})),
        'recovery': verified, 'watchdog_seconds': args.watchdog_seconds, 'device_written': False,
        'gabos_version': '1.4', 'google_packages': False,
        'hardware_hevc': args.legacy_media, 'input_wake_idc': list(idc_names),
        'host_preopt': preopt_records,
        'graphics': graphics, 'bootstrap_sha256': digest(init),
        'linker_template_sha256': digest(templates[0]), 'framework_requires_trigger': not args.installed_boot,
        'boot_success_watchdog': args.installed_boot, 'boot': verified_boot,
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
