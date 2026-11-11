#!/usr/bin/env python3
"""Prepare an init/USB diagnostic image offline, not a full TV ROM; never flash."""
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

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--product-out', required=True, type=Path)
    parser.add_argument('--native-product-out', required=True, type=Path)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--graphics-probes', action='store_true', help='Include a private Mali closure and an offscreen EGL probe')
    parser.add_argument('--graphics-services', action='store_true', help='Also stage explicitly triggered Binder/HIDL graphics services; no framework')
    parser.add_argument('--surfaceflinger', action='store_true', help='Also stage explicitly triggered native composition test; no framework or apps')
    parser.add_argument('--art-runtime', action='store_true', help='Stage a bounded core ART/Java runtime diagnostic; no framework or apps')
    parser.add_argument('--reuse-system-candidate', type=Path, help='Reuse a checksum-verified filesystem only if every staged entry matches')
    args = parser.parse_args()
    if args.surfaceflinger:
        args.graphics_services = True
    if os.name != 'posix':
        raise SystemExit('Run on native Linux/WSL ext4 to preserve symlinks and metadata')
    product = args.product_out.resolve()
    out = product.parents[2]
    variables = json.loads((out/'soong/soong.variables').read_text())
    if any(variables.get(key) != expected for key, expected in {
        'DeviceName': 'rayos1_tv12', 'DeviceArch': 'arm', 'DeviceCpuVariant': 'cortex-a7',
        'Platform_sdk_version': 31, 'Binder32bit': True}.items()):
        raise SystemExit('Unexpected TV source/build profile')
    source = product/'system'
    reboot_source = args.source/'system/core/init/reboot_utils.cpp'
    if ('start_pos += kRebootTargetStringPattern.size();' not in reboot_source.read_text()
            or 'static std::string init_fatal_reboot_target = "normal";' not in reboot_source.read_text()
            or (source/'bin/init').stat().st_mtime_ns < reboot_source.stat().st_mtime_ns):
        raise SystemExit('Rebuild init with the fatal-return parameter correction first')
    host = out/'host/linux-x86/bin'
    readelf = args.source/'prebuilts/clang/host/linux-x86/clang-r416183b/bin/llvm-readelf'
    work = Path(tempfile.mkdtemp(prefix='rayo12-stage1-'))
    system = work/'system'
    ramdisk = work/'ramdisk'
    system.mkdir()
    copied = set()

    def copy(relative, original=None):
        if relative in copied:
            return system/relative
        original = original or source/relative
        if not original.is_file():
            raise SystemExit('Build/install the required source artifact first: '+str(original))
        actual = original.resolve(strict=True)
        if not actual.is_relative_to(product) and not actual.is_relative_to(args.native_product_out.resolve()):
            raise SystemExit('Unexpected external artifact symlink: '+str(original))
        destination = system/relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(actual, destination)
        destination.chmod(0o755 if relative.startswith('bin/') else 0o644)
        copied.add(relative)
        return destination

    # The old static rescue daemon uses an older property-area reader and
    # dropped to shell under Android 12. Use this product's recovery adbd,
    # without needing the normal adbd APEX to be activated first.
    pending = [copy('bin/init'), copy('bin/bootstrap/linker'),
               copy('bin/adbd', product/'recovery/root/system/bin/adbd')]
    (system/'bin/linker').symlink_to('bootstrap/linker')
    (system/'bin/ueventd').symlink_to('init')
    scanned = set()
    core = {'libc.so', 'libm.so', 'libdl.so', 'libdl_android.so'}
    for name in core:
        pending.append(copy('lib/bootstrap/'+name))
    graphics = None
    art = None
    if args.art_runtime:
        if args.graphics_probes or args.graphics_services or args.surfaceflinger:
            raise SystemExit('Keep the first managed-runtime diagnostic separate from graphics')
        from android12_art_root import augment as augment_art
        extra, art = augment_art(system, product, ROOT, work, copy, readelf, host)
        pending.extend(extra)
    if args.graphics_probes or args.graphics_services:
        from android12_graphics_root import augment
        extra, graphics = augment(system, product, args.native_product_out, ROOT, work, copy, readelf,
                                  args.graphics_services, args.surfaceflinger)
        pending.extend(extra)
    if (system/'lib/bootstrap/libc.so').stat().st_size < 500000:
        raise SystemExit('Refusing a libc interface stub')
    while pending:
        elf = pending.pop()
        if elf in scanned:
            continue
        scanned.add(elf)
        if elf.read_bytes()[:6] != b'\x7fELF\x01\x01':
            raise SystemExit('Expected ARM32 little-endian ELF: '+str(elf))
        dynamic = subprocess.check_output([str(readelf), '-d', str(elf)], text=True)
        for name in re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic):
            relative = ('lib/bootstrap/' if name in core else 'lib/')+name
            original = source/relative
            if not original.is_file():
                original = source/'vendor'/relative
            if not original.is_file():
                original = product/'recovery/root/system'/relative
            dependency = copy(relative, original)
            if dependency not in scanned:
                pending.append(dependency)
    for relative in ('etc/ueventd.rc', 'etc/cgroups.json', 'etc/task_profiles.json',
                     'build.prop'):
        copy(relative)
    if art:
        from android12_art_root import finish
        finish(system, work, host)
    # Image outputs are disabled for the development product, so its normal
    # default-property installation is absent. Supply explicit rescue flags;
    # actual platform version/build identity remains the source build.prop.
    (system/'etc/prop.default').write_text(
        'ro.secure=0\nro.adb.secure=0\nro.debuggable=1\n'
        'ro.apex.updatable=false\nro.config.low_ram=true\n'
        'ro.product.first_api_level=25\nro.serialno=0123456789ABCDEF\n')
    for partition in ('', 'vendor/', 'product/', 'system_ext/'):
        directory = source/partition/'etc/selinux'
        if directory.exists():
            for context in directory.glob('*contexts*'):
                if context.is_file():
                    copy(context.relative_to(source).as_posix())
    # Deliberately use the compiled monolithic policy for this minimal root;
    # absent CIL means Android 12's existing monolithic reader is selected.
    if (system/'etc/selinux/plat_sepolicy.cil').exists():
        raise SystemExit('The minimal root must not accidentally select split-policy compilation')
    shutil.copytree(product/'root', ramdisk, symlinks=True)
    init = ramdisk/'init'
    if init.exists() or init.is_symlink():
        init.unlink()
    shutil.copyfile(args.native_product_out/'system/bin/rayo12_early_init_bootstrap', init)
    init.chmod(0o755)
    (ramdisk/'sbin').mkdir(exist_ok=True)
    with tarfile.open(ROOT/'local/device-kit/runtime/trial-root-v14.tar.gz') as archive:
        names = ('adbd', 'busybox', 'rayo_hwbinder.ko') if args.graphics_services else ('adbd', 'busybox')
        for name in names:
            matches = [m for m in archive.getmembers() if m.name.lstrip('./') == 'sbin/'+name]
            if len(matches) != 1 or not matches[0].isfile():
                raise SystemExit('Unexpected verified rescue archive layout')
            payload = archive.extractfile(matches[0]).read()
            (ramdisk/'sbin'/name).write_bytes(payload)
            (ramdisk/'sbin'/name).chmod(0o755)
    shutil.copyfile(args.native_product_out/'system/bin/rayo12_stage1_props', ramdisk/'sbin/rayo12-props')
    # Static rescue shell remains usable even if normal runtime/APEX setup is
    # unavailable. This root intentionally does not attempt APEX or framework.
    shutil.copyfile(ramdisk/'sbin/busybox', system/'bin/sh')
    (system/'bin/sh').chmod(0o755)
    rc = ROOT/'work/android12/stage1/init.rayo12.stage1.rc'
    shutil.copyfile(rc, ramdisk/'init.rayo12.stage1.rc')
    # The OEM bootloader replaces header cmdline text; use the default path
    # as well as the annotated header selection for this minimal root.
    (system/'etc/init/hw').mkdir(parents=True, exist_ok=True)
    shutil.copyfile(rc, system/'etc/init/hw/init.rc')
    # SecondStageMain automatically scans /system/etc/init; do not also import
    # the graphics RC from the hardware RC and define its services twice.
    subprocess.run([str(host/'host_init_verifier'),
        '--property-contexts='+str(system/'etc/selinux/plat_property_contexts'), str(rc)], check=True)
    if args.graphics_services:
        subprocess.run([str(host/'host_init_verifier'),
            '--property-contexts='+str(system/'etc/selinux/plat_property_contexts'),
            str(system/'etc/init/rayo12.graphics.rc')], check=True)
    if args.surfaceflinger:
        subprocess.run([str(host/'host_init_verifier'),
            '--property-contexts='+str(system/'etc/selinux/plat_property_contexts'),
            str(system/'etc/init/rayo12.surface.rc')], check=True)
    if art:
        subprocess.run([str(host/'host_init_verifier'),
            '--property-contexts='+str(system/'etc/selinux/plat_property_contexts'),
            str(system/'etc/init/rayo12.art.rc')], check=True)
    policy = (product/'system/vendor/etc/selinux/precompiled_sepolicy').read_bytes()
    magic, name_length = struct.unpack_from('<II', policy)
    version = struct.unpack_from('<I', policy, 8+name_length)[0]
    if magic != 0xf97cff8c or version > 30:
        raise SystemExit('Policy exceeds the measured original-kernel policy format')
    (ramdisk/'sepolicy').write_bytes(policy)
    image = work/'rayo12-stage1-system.raw.img'
    size = art['system_bytes'] if art else 128*1024*1024
    content_bytes = sum(p.stat().st_size for p in system.rglob('*') if p.is_file())
    if content_bytes > size*0.7:
        raise SystemExit('Minimal diagnostic content no longer fits its bounded filesystem')
    env = os.environ.copy()
    env['MKE2FS_CONFIG'] = str(host/'mke2fs.conf')
    features = 'none,extent,filetype,sparse_super,large_file,dir_index,ext_attr,flex_bg,uninit_bg,dir_nlink,extra_isize'
    if args.reuse_system_candidate:
        old = args.reuse_system_candidate
        old_record = json.loads((old/'MANIFEST.json').read_text())
        old_image = old/'rayo12-stage1-system.raw.img'
        raw = old_image.read_bytes()
        if len(raw) != size or hashlib.sha256(raw).hexdigest() != old_record['system']['sha256']:
            raise SystemExit('Previous diagnostic image checksum differs')
        extracted = work/'previous-system'
        extracted.mkdir()
        subprocess.run(['debugfs', '-R', 'rdump / '+str(extracted), str(old_image)], check=True, stdout=subprocess.DEVNULL)

        def fingerprint(directory):
            result = {}
            for path in directory.rglob('*'):
                relative = path.relative_to(directory).as_posix()
                if relative == 'lost+found' or relative.startswith('lost+found/'):
                    continue
                result[relative] = ('link', os.readlink(path)) if path.is_symlink() else ('file', hashlib.sha256(path.read_bytes()).hexdigest()) if path.is_file() else ('dir', '')
            return result

        if fingerprint(extracted) != fingerprint(system):
            raise SystemExit('The previous filesystem does not exactly match the current diagnostic contents')
        shutil.copyfile(old_image, image)
    else:
        subprocess.run([str(host/'mke2fs'), '-t', 'ext4', '-b', '4096', '-I', '256',
                        '-O', features, '-F', str(image), str(size//4096)], env=env, check=True)
        contexts = source/'etc/selinux/plat_file_contexts'
        subprocess.run([str(host/'e2fsdroid'), '-e', '-f', str(system), '-a', '/system',
                        '-S', str(contexts), str(image)], check=True)
    superblock = image.read_bytes()[1024:2048]
    compat, incompat, ro_compat = struct.unpack_from('<III', superblock, 92)
    if incompat & ~0x242 or ro_compat & ~0x73 or compat & ~0x28:
        raise SystemExit('Unexpected filesystem features for the original kernel')
    labels = subprocess.check_output(['debugfs', '-R', 'ea_list /bin/init', str(image)], text=True, stderr=subprocess.STDOUT)
    if 'u:object_r:init_exec:s0' not in labels:
        raise SystemExit('The readonly init inode does not carry its required SELinux label: '+labels)
    sha = hashlib.sha256(image.read_bytes()).hexdigest()
    (ramdisk/'rayo12-stage1.cfg').write_text(f'system_sha256={sha}\nsystem_bytes={size}\n')
    recovery = work/'rayo12-stage1-recovery.img'
    cmdline = ('buildvariant=eng androidboot.selinux=permissive '
               'androidboot.init_rc=/init.rayo12.stage1.rc '
               'androidboot.init_fatal_reboot_target=normal')
    subprocess.run(['python3', str(ROOT/'tools/pack-recovery.py'), '--ramdisk', str(ramdisk),
                    '--output', str(recovery), '--cmdline', cmdline], check=True)
    spec = importlib.util.spec_from_file_location('verify', ROOT/'tools/verify-rayo-boot.py')
    verify = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify)
    verified = verify.inspect(recovery.read_bytes(), (ROOT/'local/device-kit/original/recovery.img').read_bytes(), True)
    manifest = {'purpose': 'Diagnostic Android 12 init/USB only; not a TV ROM or installed image',
        'system': {'path': image.name, 'bytes': size, 'sha256': sha, 'content_bytes': content_bytes},
        'recovery': verified, 'policy_version': version, 'filesystem_features': [compat, incompat, ro_compat],
        'android12_init': {'sha256': hashlib.sha256((system/'bin/init').read_bytes()).hexdigest(),
                           'md5': hashlib.md5((system/'bin/init').read_bytes()).hexdigest()},
        'physical_userdata': 'Readonly with noload; temporary data/cache use tmpfs',
        'watchdog': 'Unverified boot-time normal-reboot request after 180 seconds; no disarm path',
        'device_written': False}
    if graphics:
        manifest['graphics_probe'] = graphics
    if art:
        manifest['art_runtime'] = art
    (work/'MANIFEST.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print('Offline diagnostic candidate:', work)


if __name__ == '__main__':
    main()
