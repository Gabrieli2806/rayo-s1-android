from pathlib import Path
import hashlib
import struct
import zipfile
import shutil
import subprocess

root = Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q')
out = root / 'outputs/rayo-s1'
original = (out / 'respaldo-original/resource.img').read_bytes()
patched = bytearray(original)
bmp = (root / 'work/logo-new.bmp').read_bytes()
assert original[:4] == b'RSCE'
count = struct.unpack_from('<I', original, 12)[0]
entries = []
changed = []
for index in range(count):
    entry = (original[9] + index * original[10]) * 512
    assert original[entry:entry+4] == b'ENTR'
    name = original[entry+4:entry+224].split(b'\0')[0].decode()
    offset, size = struct.unpack_from('<II', original, entry+260)
    start = offset * 512
    assert start + size <= len(original)
    entries.append((name, start, size))
    if name in ('logo.bmp', 'logo_kernel.bmp'):
        assert original[start:start+2] == b'BM'
        assert size == len(bmp)
        assert struct.unpack_from('<iiHHI', original, start+18) == struct.unpack_from('<iiHHI', bmp, 18)
        assert original[entry+224:entry+260] == bytes(36), 'Unexpected resource hashes'
        patched[start:start+size] = bmp
        changed.append(name)
assert sorted(changed) == ['logo.bmp', 'logo_kernel.bmp']
for name, start, size in entries:
    if name not in changed:
        assert patched[start:start+size] == original[start:start+size]
expected = bytearray(patched)
for name, start, size in entries:
    if name in changed:
        expected[start:start+size] = original[start:start+size]
assert expected == original, 'Unexpected changes outside logo images'
resource = out / 'resource-gabrieli-v0.2.img'
resource.write_bytes(patched)
animation = out / 'bootanimation-gabrieli.zip'
with zipfile.ZipFile(animation, 'w', compression=zipfile.ZIP_STORED) as archive:
    archive.writestr('desc.txt', '1280 720 15\np 0 0 part0\n')
    archive.write(out / 'Logo-arranque.png', 'part0/00000.png')
with zipfile.ZipFile(animation) as archive:
    assert archive.testzip() is None
for path in (resource, animation):
    digest = hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
    (out / (path.name + '.sha256')).write_text(digest + '  ' + path.name + '\n')
print('Verified: only logo.bmp and logo_kernel.bmp changed; DTB and all other bytes preserved.')
print('Boot animation ZIP verified, uncompressed, 1280x720.')
system = out / 'system-rayo-dark-v0.2.img'
if system.exists():
    raise SystemExit('System v0.2 already exists; preserving it')
shutil.copyfile(out / 'system-rayo-dark-v0.1.img', system)
commands = root / 'work/rayo-launcher/debugfs-logo.txt'
commands.write_text('\n'.join([
    'rm /media/bootanimation.zip',
    f'write {animation} /media/bootanimation.zip',
    'set_inode_field /media/bootanimation.zip mode 0100644',
    'set_inode_field /media/bootanimation.zip uid 0',
    'set_inode_field /media/bootanimation.zip gid 0',
    f'ea_set -f {root}/work/rayo-launcher/selinux-label.bin /media/bootanimation.zip security.selinux',
]) + '\n')
subprocess.run(['debugfs', '-w', '-f', str(commands), str(system)], check=True)
subprocess.run(['e2fsck', '-fn', str(system)], check=True)
extracted = root / 'work/rayo-launcher/extracted-bootanimation.zip'
subprocess.run(['debugfs', '-R', f'dump /media/bootanimation.zip {extracted}', str(system)], check=True)
assert extracted.read_bytes() == animation.read_bytes()
digest = hashlib.file_digest(system.open('rb'), 'sha256').hexdigest()
(out / (system.name + '.sha256')).write_text(digest + '  ' + system.name + '\n')
print('System v0.2 with launcher and custom startup verified.')
