from pathlib import Path
import struct
import hashlib
import json
import gzip

root=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q')
results=[]
for name in ('boot','recovery'):
    data=(root/f'outputs/rayo-s1/respaldo-original/{name}.img').read_bytes()
    assert data[:8]==b'ANDROID!'
    kernel_size,kernel_addr,ramdisk_size,ramdisk_addr,second_size,second_addr,tags,page,dt_size,os_version=struct.unpack_from('<10I',data,8)
    align=lambda size:(size+page-1)//page*page
    pos=page
    digest=hashlib.sha1()
    sections={}
    target=root/f'work/android81/stock-{name}'
    target.mkdir(exist_ok=True)
    for label,size in (('kernel',kernel_size),('ramdisk',ramdisk_size),('second',second_size)):
        blob=data[pos:pos+size]
        digest.update(blob);digest.update(struct.pack('<I',size))
        sections[label]={'offset':pos,'size':size,'sha256':hashlib.sha256(blob).hexdigest()}
        (target/(label+'.gz' if label=='ramdisk' else label)).write_bytes(blob)
        pos+=align(size)
    if dt_size:
        digest.update(data[pos:pos+dt_size]);digest.update(struct.pack('<I',dt_size));pos+=align(dt_size)
    trailer=data[pos:]
    nonzero=next((i for i,b in enumerate(trailer) if b),None)
    results.append({'image':name,'page_size':page,'addresses':{'kernel':kernel_addr,'ramdisk':ramdisk_addr,'second':second_addr,'tags':tags},
                    'sections':sections,'payload_end':pos,'partition_size':len(data),'dt_size':dt_size,'os_version':os_version,
                    'header_id_sha1':data[576:596].hex(),'calculated_sha1':digest.hexdigest(),
                    'trailer_first_nonzero':None if nonzero is None else pos+nonzero,
                    'trailer_prefix':trailer[:64].hex()})
(root/'work/android81/boot-image-inspection.json').write_text(json.dumps(results,indent=2))
print(json.dumps(results,indent=2))
