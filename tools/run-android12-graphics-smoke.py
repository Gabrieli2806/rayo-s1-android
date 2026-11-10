#!/usr/bin/env python3
"""Test an offscreen Mali pbuffer with isolated legacy libraries; no display/boot writes."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--out-dir', required=True, type=Path)
    parser.add_argument('--probe', required=True, type=Path)
    parser.add_argument('--platform-egl', action='store_true', help='Also test source-built Android 12 EGL/GLES wrappers')
    parser.add_argument('--buffer', action='store_true', help='Test local Android 12 allocator/mapper passthrough instead of EGL')
    parser.add_argument('--hwc-load', action='store_true', help='Resolve factory HWC/adapter symbols without opening hardware')
    parser.add_argument('--transport-buffer', action='store_true', help='Test the Android 12 GraphicBuffer client with existing public allocator transport')
    parser.add_argument('--private-mount-helper', type=Path, help='Run wrappers in a disposable, read-only child mount view')
    args = parser.parse_args()
    if sum((args.platform_egl, args.buffer, args.hwc_load, args.transport_buffer)) > 1:
        raise SystemExit('Choose one graphics test mode')
    if args.transport_buffer and not args.private_mount_helper:
        raise SystemExit('Transport buffer mode requires the private mount helper')
    if args.platform_egl and not args.private_mount_helper:
        raise SystemExit('Platform EGL requires the private mount view for its absolute wrapper paths')
    if args.buffer and (args.platform_egl or not args.private_mount_helper):
        raise SystemExit('Buffer mode requires the private helper and excludes EGL mode')
    if args.hwc_load and (args.buffer or args.platform_egl or not args.private_mount_helper):
        raise SystemExit('HWC load-only mode requires the private helper and excludes rendering modes')
    bundle = ROOT/'outputs/android12-native-built'
    subprocess.run([sys.executable, str(ROOT/'tools/run-android12-native-smoke.py')], check=True)
    product = args.out_dir/'target/product/rayos1_12_lab/system/lib'
    files = {p: bundle/p for p in ('bin/linker', 'lib/libc.so', 'lib/libm.so',
                                  'lib/libdl.so', 'lib/libdl_android.so')}
    files.update({'lib/libvndksupport.so': product/'libvndksupport.so',
                  'lib/liblog.so': product/'liblog.so',
                  'lib/libc++.so': product/'libc++.so',
                  'bin/rayo12-gpu-probe': args.probe})
    if args.buffer:
        # The production allocator/composer services are vendor processes.
        # Their libhardware routes factory modules through sphal even when
        # the historical board stores a module under /system/lib/hw.
        files['lib/libhardware.so'] = args.out_dir/'soong/.intermediates/hardware/libhardware/libhardware/android_vendor.31_arm_armv7-a-neon_cortex-a7_shared/libhardware.so'
    if args.hwc_load:
        files['lib/hwcomposer.rayo12.so'] = product.parent/'vendor/lib/hw/hwcomposer.rayo12.so'
    if args.transport_buffer:
        files['lib/hw/android.hardware.graphics.mapper@2.0-impl.so'] = product.parent/'vendor/lib/hw/android.hardware.graphics.mapper@2.0-impl.so'
    if args.platform_egl or args.buffer or args.transport_buffer:
        ndk = Path(os.environ['ANDROID_HOME'])/'ndk/25.2.9519653/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
        if args.buffer or args.transport_buffer:
            dynamic = subprocess.check_output([str(ndk), '-d', str(args.probe)], text=True)
            pending = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
            if args.transport_buffer:
                dynamic = subprocess.check_output([str(ndk), '-d', str(files['lib/hw/android.hardware.graphics.mapper@2.0-impl.so'])], text=True)
                pending.extend(re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic))
        else:
            pending = ['libEGL.so', 'libGLESv1_CM.so', 'libGLESv2.so']
        while pending:
            name = pending.pop()
            if 'lib/'+name in files:
                continue
            path = product/name
            if not path.is_file():
                raise SystemExit('Build the missing Android 12 dependency first: '+name)
            files['lib/'+name] = path
            dynamic = subprocess.check_output([str(ndk), '-d', str(path)], text=True)
            pending.extend(re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic))
    if args.private_mount_helper:
        files['bin/private-mount-probe'] = args.private_mount_helper
    for path in files.values():
        if path.read_bytes()[:5] != b'\x7fELF\x01':
            raise SystemExit('Expected a 32-bit ELF: '+str(path))
    if not args.execute:
        print('Graphics preflight only. No file staging or GPU process launched.')
        return
    adb = shutil.which('adb')
    prefix = [adb, '-s', '0123456789ABCDEF']

    def command(*parts):
        return subprocess.run(prefix+list(parts), capture_output=True, text=True,
                              encoding='utf-8', errors='replace', timeout=45)

    def checked(*parts):
        result = command(*parts)
        if result.returncode:
            raise SystemExit(result.stdout+result.stderr)
        return result.stdout.strip()

    stamp = time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
    remote = '/data/local/tmp/rayo12-graphics-'+stamp
    legacy = remote+'/legacy' if args.private_mount_helper else '/system/lib'
    default_permitted = remote+':/system/lib' if args.private_mount_helper else remote
    logs = ROOT/'local/android12-graphics-runs'/stamp
    logs.mkdir(parents=True)
    # Only this eng linker child consumes the config. No global linker files
    # or environment are replaced. Private C++ libraries stay in sphal.
    config = f'''dir.rayo = {remote}/bin
[rayo]
additional.namespaces = sphal
namespace.default.isolated = true
namespace.default.search.paths = {remote}/lib
namespace.default.permitted.paths = {default_permitted}
namespace.sphal.isolated = true
namespace.sphal.visible = true
namespace.sphal.search.paths = /vendor/lib:{legacy}
namespace.sphal.permitted.paths = /vendor:/system/vendor:{legacy}
namespace.sphal.links = default
namespace.sphal.link.default.shared_libs = libc.so:libm.so:libdl.so:libdl_android.so:libvndksupport.so
'''
    config_path = logs/'ld.config.txt'
    config_path.write_text(config, encoding='utf-8')
    checked('shell', '/sbin/busybox', 'mkdir', '-p', remote+'/bin', remote+'/lib', remote+'/legacy')
    if args.private_mount_helper:
        # Preserve the single factory Mali inode through both OEM paths.
        checked('shell', '/sbin/busybox', 'ln', '-s', '/vendor/lib/egl', remote+'/lib/egl')
        if args.transport_buffer:
            checked('shell', '/sbin/busybox', 'mkdir', '-p', remote+'/lib/hw', remote+'/vendor-hw')
            # Preserve libhardware's realpath partition check. A symlink out
            # of vendor/hw is correctly rejected; stage a checked private copy.
            factory = logs/'gralloc.rk30board.so'
            checked('pull', '/system/lib/hw/gralloc.rk30board.so', str(factory))
            if factory.read_bytes()[:5] != b'\x7fELF\x01':
                raise SystemExit('Unexpected factory gralloc ELF')
            files['vendor-hw/gralloc.rk30board.so'] = factory
        else:
            checked('shell', '/sbin/busybox', 'ln', '-s', remote+'/legacy/hw', remote+'/lib/hw')
    records = []
    for name, path in {**files, 'ld.config.txt': config_path}.items():
        data = path.read_bytes()
        checked('push', str(path), remote+'/'+name)
        if checked('shell', 'md5sum', remote+'/'+name).split()[0] != hashlib.md5(data).hexdigest():
            raise SystemExit('Private transfer checksum mismatch')
        records.append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    checked('shell', '/sbin/busybox', 'chmod', '0755', remote+'/bin/linker', remote+'/bin/rayo12-gpu-probe')
    assignments = ['LD_CONFIG_FILE='+remote+'/ld.config.txt']
    if args.platform_egl:
        assignments.append('RAYO_USE_PLATFORM_EGL=1')
    if args.hwc_load:
        assignments.append('RAYO_HWC_LOAD_ONLY=1')
    before_namespace = checked('shell', '/sbin/busybox', 'readlink', '/proc/self/ns/mnt')
    if args.private_mount_helper:
        checked('shell', '/sbin/busybox', 'chmod', '0755', remote+'/bin/private-mount-probe')
        child = [remote+'/bin/private-mount-probe', remote]
    else:
        child = [remote+'/bin/linker', remote+'/bin/rayo12-gpu-probe']
    result = command('shell', *assignments, *child)
    (logs/'stdout.txt').write_text(result.stdout, encoding='utf-8')
    (logs/'stderr.txt').write_text(result.stderr, encoding='utf-8')
    post = checked('shell', 'getprop', 'sys.boot_completed')
    after_namespace = checked('shell', '/sbin/busybox', 'readlink', '/proc/self/ns/mnt')
    (logs/'context.json').write_text(json.dumps({'private_device_directory': remote,
        'test_mode': ('transport-buffer' if args.transport_buffer else 'hwc-load' if args.hwc_load else 'buffer' if args.buffer else 'platform-egl' if args.platform_egl else 'direct-egl'),
        'files': records, 'exit_code': result.returncode, 'post_boot_completed': post,
        'parent_mount_namespace_before': before_namespace,
        'parent_mount_namespace_after': after_namespace}, indent=2)+'\n')
    print(result.stdout, result.stderr)
    token = 'GRAPHICBUFFER_TRANSPORT_PASS' if args.transport_buffer else ('FACTORY_HWC_LOAD_PASS' if args.hwc_load else ('GRALLOC_BUFFER_PASS' if args.buffer else 'GPU_PBUFFER_PASS'))
    if result.returncode or token not in result.stdout or post != '1' or before_namespace != after_namespace:
        raise SystemExit('Offscreen namespace test failed; logs: '+str(logs))
    print('Offscreen namespace test passed. This does not validate projection or Android 12 boot. Logs:', logs)


if __name__ == '__main__':
    main()
