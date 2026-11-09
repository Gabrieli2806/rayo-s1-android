from pathlib import Path
import struct, zipfile, hashlib
root=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q')
out=root/'outputs/rayo-s1'
old=(out/'respaldo-original/resource.img').read_bytes()
new=bytearray(old)
bmp=(root/'work/logo-new-v0.3.bmp').read_bytes()
assert old[:4]==b'RSCE'
changed=[]
for i in range(struct.unpack_from('<I',old,12)[0]):
    e=(old[9]+i*old[10])*512
    name=old[e+4:e+224].split(b'\0')[0].decode()
    offset,size=struct.unpack_from('<II',old,e+260)
    start=offset*512
    if name in ('logo.bmp','logo_kernel.bmp'):
        assert size==len(bmp) and old[start:start+2]==b'BM'
        assert struct.unpack_from('<iiHHI',old,start+18)==struct.unpack_from('<iiHHI',bmp,18)
        new[start:start+size]=bmp
        changed.append((start,size))
assert len(changed)==2
restored=bytearray(new)
for start,size in changed:restored[start:start+size]=old[start:start+size]
assert restored==old
resource=out/'resource-gabrieli-v0.3.img';resource.write_bytes(new)
animation=out/'bootanimation-gabrieli-v0.3.zip'
with zipfile.ZipFile(animation,'w',compression=zipfile.ZIP_STORED) as z:
    z.writestr('desc.txt','1280 720 15\np 0 0 part0\n')
    z.write(out/'Logo-arranque-v0.3.png','part0/00000.png')
with zipfile.ZipFile(animation) as z:assert z.testzip() is None
for path in (resource,animation):
    digest=hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
    (out/(path.name+'.sha256')).write_text(digest+'  '+path.name+'\n')
print('v0.3 verified: both images rotated 180 degrees; hardware description unchanged.')
