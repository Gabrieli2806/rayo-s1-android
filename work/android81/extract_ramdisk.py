from pathlib import Path, PurePosixPath
import gzip
import json
import os
import stat
import sys

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
destination.mkdir(parents=True, exist_ok=True)
data = gzip.decompress(source.read_bytes())
offset = 0
entries = []
links = []
while offset + 110 <= len(data):
    header = data[offset:offset+110]
    assert header[:6] in (b'070701', b'070702')
    values = [int(header[6+i*8:14+i*8],16) for i in range(13)]
    mode, uid, gid, size, name_size = values[1], values[2], values[3], values[6], values[11]
    name = data[offset+110:offset+110+name_size-1].decode()
    payload = (offset+110+name_size+3) & ~3
    if name == 'TRAILER!!!': break
    relative = PurePosixPath(name)
    if relative.is_absolute() or '..' in relative.parts: raise ValueError(name)
    path = destination / name
    blob = data[payload:payload+size]
    entries.append({'path': name, 'mode': mode, 'uid': uid, 'gid': gid, 'size': size})
    if stat.S_ISDIR(mode):
        path.mkdir(parents=True, exist_ok=True)
        path.chmod(stat.S_IMODE(mode))
    elif stat.S_ISREG(mode):
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.is_symlink(): raise ValueError('Existing symlink: '+name)
        path.write_bytes(blob)
        path.chmod(stat.S_IMODE(mode))
    elif stat.S_ISLNK(mode):
        links.append((path,blob.decode()))
    offset = (payload+size+3) & ~3
# Links are last so archive paths cannot escape through them during extraction.
for path,target in links:
    path.parent.mkdir(parents=True,exist_ok=True)
    if path.is_symlink():
        if os.readlink(path) != target: raise ValueError('Changed symlink: '+str(path))
    elif not path.exists(): path.symlink_to(target)
(destination.parent/(destination.name+'-manifest.json')).write_text(json.dumps(entries,indent=2))
print('Extracted entries:',len(entries))
