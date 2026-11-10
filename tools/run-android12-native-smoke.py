#!/usr/bin/env python3
"""Check or run a bounded native process; never reboot, mount, or write partitions."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BOOT = '/dev/block/platform/1021c000.rksdmmc/by-name/boot'
RECOVERY = '/dev/block/platform/1021c000.rksdmmc/by-name/recovery'
BOOT_MD5 = '7a3ac489b05f0f2e9cde16280551511a'
RECOVERY_MD5 = 'b32f971e7dfce13c708d264c6221844d'
ALLOWED_FILES = {'bin/linker', 'bin/rayo12-bootstrap-probe',
                 'lib/libc.so', 'lib/libm.so', 'lib/libdl.so', 'lib/libdl_android.so'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true', help='Stage private files and run the child process')
    parser.add_argument('--bundle', type=Path, default=ROOT/'outputs/android12-native-built')
    args = parser.parse_args()
    BUNDLE = args.bundle
    manifest = json.loads((BUNDLE/'MANIFEST.json').read_text(encoding='utf-8'))
    if {r['path'] for r in manifest['files']} != ALLOWED_FILES:
        raise SystemExit('Unexpected bundle contents')
    if len(manifest['files']) != len(ALLOWED_FILES):
        raise SystemExit('Duplicate bundle entries')
    if manifest.get('runtime_origin', '').startswith('Official ADT-3'):
        raise SystemExit('The ADT-3 core runtime is ARMv8/Cortex-A53, not this Cortex-A7. Use the source-built bundle.')
    for record in manifest['files']:
        data = (BUNDLE/record['path']).read_bytes()
        if len(data) != record['bytes'] or hashlib.sha256(data).hexdigest() != record['sha256']:
            raise SystemExit('Local smoke bundle checksum mismatch: '+record['path'])
    adb = shutil.which('adb')
    if not adb:
        raise SystemExit('Load the project environment or install Android Platform Tools')
    listing = subprocess.check_output([adb, 'devices'], text=True)
    sessions = [line.split() for line in listing.splitlines()[1:] if line.split()]
    known = [r[0] for r in sessions if len(r) > 1 and r[0] == '0123456789ABCDEF'
             and r[1] in ('device', 'recovery')]
    if len(known) != 1:
        raise SystemExit('Connect the known projector in its normal Android 8.1 v14 startup, not Loader')
    prefix = [adb, '-s', known[0]]

    def command(*parts, timeout=30):
        return subprocess.run(prefix+list(parts), capture_output=True, text=True,
                              encoding='utf-8', errors='replace', timeout=timeout)

    def shell(*parts):
        result = command('shell', *parts)
        if result.returncode:
            raise SystemExit('Read/setup command failed: '+result.stderr)
        return result.stdout.strip()

    identity = {p: shell('getprop', p) for p in ('ro.build.version.release', 'ro.rayo.trial', 'sys.boot_completed')}
    if list(identity.values()) != ['8.1.0', '1', '1'] or shell('id', '-u') != '0':
        raise SystemExit('The expected completed, root v14 runtime is not active')
    if shell('uname', '-r') != '3.10.104':
        raise SystemExit('Unexpected kernel; stop before staging')
    if shell('md5sum', BOOT).split()[0] != BOOT_MD5:
        raise SystemExit('Boot checkpoint is not the recorded v14 image')
    if shell('md5sum', RECOVERY).split()[0] != RECOVERY_MD5:
        raise SystemExit('Independent recovery does not match the recorded original')
    print('Verified v14 normal-boot and original independent recovery checkpoints.')
    if not args.execute:
        print('Read-only preflight complete. No files uploaded or process launched.')
        return
    stamp = time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
    remote = '/data/local/tmp/rayo12-native-'+stamp
    local = ROOT/'local/android12-native-runs'/stamp
    local.mkdir(parents=True)
    shell('/sbin/busybox', 'mkdir', '-p', remote+'/bin', remote+'/lib')
    for record in manifest['files']:
        destination = remote+'/'+record['path']
        result = command('push', str(BUNDLE/record['path']), destination, timeout=90)
        if result.returncode:
            raise SystemExit('Private bundle transfer failed')
        if shell('md5sum', destination).split()[0] != record['md5']:
            raise SystemExit('Transferred bundle checksum mismatch')
    shell('/sbin/busybox', 'chmod', '0755', remote+'/bin/linker', remote+'/bin/rayo12-bootstrap-probe')
    # Android's linker does not implement glibc's --library-path option.
    # This assignment applies only to the newly executed child; no env binary,
    # parent process, service, or persistent device property is modified.
    result = command('shell', 'LD_LIBRARY_PATH='+remote+'/lib', remote+'/bin/linker',
                     remote+'/bin/rayo12-bootstrap-probe', timeout=30)
    (local/'stdout.txt').write_text(result.stdout, encoding='utf-8')
    (local/'stderr.txt').write_text(result.stderr, encoding='utf-8')
    (local/'context.json').write_text(json.dumps({'identity': identity, 'bundle': manifest,
                                                'private_device_directory': remote,
                                                'exit_code': result.returncode}, indent=2)+'\n', encoding='utf-8')
    if result.returncode:
        raise SystemExit('Native process did not pass. Logs retained at '+str(local))
    try:
        data = json.loads(result.stdout)
    except ValueError:
        raise SystemExit('Unexpected probe output; inspect retained logs')
    if not (data.get('pointer_bits') == 32 and data.get('kernel') == '3.10.104'
            and data.get('getentropy_result') == 0 and data.get('thread_heap_ok')
            and data.get('monotonic_clock_ok') and data.get('libm_load_ok')
            and data.get('libc_object') == remote+'/lib/libc.so'):
        raise SystemExit('Native runtime acceptance fields differ; no boot changes performed')
    if shell('getprop', 'ro.build.version.release') != '8.1.0' or shell('getprop', 'sys.boot_completed') != '1':
        raise SystemExit('Post-test v14 status differs; inspect device before further work')
    print(json.dumps(data, indent=2))
    print('Isolated native test passed; the running OS remains 8.1. Logs:', local)


if __name__ == '__main__':
    main()
