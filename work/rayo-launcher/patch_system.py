from pathlib import Path
import hashlib
import shutil
import subprocess

root = Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q')
source = root / 'outputs/rayo-s1/respaldo-original/system.img'
target = root / 'outputs/rayo-s1/system-rayo-dark-v0.1.img'
apk = root / 'outputs/rayo-s1/Rayo-Dark.apk'
work = root / 'work/rayo-launcher'
if target.exists():
    raise SystemExit('Target already exists; preserving it')
shutil.copyfile(source, target)
label = work / 'selinux-label.bin'
label.write_bytes(b'u:object_r:system_file:s0\0')
commands = work / 'debugfs-patch.txt'
commands.write_text('\n'.join([
    'mkdir /app/RayoDark',
    f'write {apk} /app/RayoDark/RayoDark.apk',
    'set_inode_field /app/RayoDark mode 040755',
    'set_inode_field /app/RayoDark uid 0',
    'set_inode_field /app/RayoDark gid 0',
    'set_inode_field /app/RayoDark/RayoDark.apk mode 0100644',
    'set_inode_field /app/RayoDark/RayoDark.apk uid 0',
    'set_inode_field /app/RayoDark/RayoDark.apk gid 0',
    f'ea_set -f {label} /app/RayoDark security.selinux',
    f'ea_set -f {label} /app/RayoDark/RayoDark.apk security.selinux',
]) + '\n')
subprocess.run(['debugfs', '-w', '-f', str(commands), str(target)], check=True)
check = subprocess.run(['e2fsck', '-fn', str(target)], capture_output=True, text=True)
(work / 'e2fsck-result.txt').write_text(check.stdout + check.stderr)
print(check.stdout + check.stderr)
if check.returncode != 0:
    raise SystemExit(f'Filesystem check failed: {check.returncode}')
extracted = work / 'extracted-patched.apk'
subprocess.run(['debugfs', '-R', f'dump /app/RayoDark/RayoDark.apk {extracted}', str(target)], check=True)
assert extracted.read_bytes() == apk.read_bytes(), 'APK verification failed'
for path in (target, apk):
    digest = hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
    (path.parent / (path.name + '.sha256')).write_text(digest + '  ' + path.name + '\n')
print('Patched image, filesystem, and embedded APK verified.')
