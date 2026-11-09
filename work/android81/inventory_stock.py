from pathlib import Path
import struct, json, hashlib, gzip

workspace=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q')
stock=workspace/'work/android81/stock-system'
out=workspace/'outputs/rayo-s1/android81'
out.mkdir(exist_ok=True)

def elf_info(path):
    data=path.read_bytes()
    if data[:5] != b'\x7fELF\x01' or data[5] != 1:return None
    h=struct.unpack_from('<16sHHIIIIIHHHHHH',data)
    phoff,phsize,phnum=h[5],h[9],h[10]
    loads=[]; dynamic=None
    for i in range(phnum):
        p=struct.unpack_from('<IIIIIIII',data,phoff+i*phsize)
        if p[0]==1:loads.append(p)
        if p[0]==2:dynamic=p
    needed=[]; string_address=None; soname_offset=None
    if dynamic:
        for off in range(dynamic[1],dynamic[1]+dynamic[4],8):
            tag,value=struct.unpack_from('<II',data,off)
            if tag==0:break
            if tag==1:needed.append(value)
            if tag==5:string_address=value
            if tag==14:soname_offset=value
    string_offset=None
    if string_address is not None:
        for p in loads:
            if p[2] <= string_address < p[2]+p[4]:
                string_offset=p[1]+string_address-p[2];break
    def string(pos):
        end=data.find(b'\0',pos)
        return data[pos:end].decode(errors='replace')
    dependencies=[string(string_offset+i) for i in needed] if string_offset is not None else []
    imports=[]; exports=[]
    shoff,shsize,shnum=h[6],h[11],h[12]
    sections=[struct.unpack_from('<IIIIIIIIII',data,shoff+i*shsize) for i in range(shnum)] if shoff else []
    for s in sections:
        if s[1]!=11 or s[9]==0:continue
        strings=sections[s[6]]
        for offset in range(s[4],s[4]+s[5],s[9]):
            name,value,size,info,other,index=struct.unpack_from('<IIIBBH',data,offset)
            if name and info>>4 in (1,2):
                target=imports if index==0 else exports
                target.append(string(strings[4]+name))
    return {'machine':h[2], 'needed':dependencies, 'imports':sorted(set(imports)), 'exports':sorted(set(exports)),
            'soname':string(string_offset+soname_offset) if string_offset is not None and soname_offset is not None else None}

inventory=[]
for folder in ('lib','vendor/lib','bin'):
    base=stock/folder
    if not base.exists():continue
    for path in sorted(base.rglob('*')):
        if path.is_symlink() or not path.is_file():continue
        info=elf_info(path)
        if info is None:continue
        info.update(path=str(path.relative_to(stock)),bytes=path.stat().st_size,
                    sha256=hashlib.file_digest(path.open('rb'),'sha256').hexdigest())
        inventory.append(info)
(out/'inventario-elf-original.json').write_text(json.dumps(inventory,indent=2))
critical=[x for x in inventory if '/hw/' in x['path'] or '/egl/' in x['path'] or Path(x['path']).name in
          ('libvpu.so','librga.so','libion.so','librockchipxxx.so','libbt-vendor.so','wpa_supplicant','displayd')]
lines=['# Dependencias de los componentes originales','',
       'Inventario de binarios Android 7.1.2. Encontrarlos no prueba compatibilidad binaria con Android 8.1.','',
       '| Componente | Dependencias dinámicas |','|---|---|']
for item in critical:lines.append('| '+item['path']+' | '+', '.join(item['needed'])+' |')
lines+=['','Los binarios que dependen de libgui, libui, libbinder, libutils o libstagefright requieren especial revisión: esas interfaces no son una ABI de proveedor estable entre Android 7.1 y 8.1.',
        '','Los módulos .ko deben conservarse con su kernel original; no son intercambiables con un kernel más nuevo.']
(out/'dependencias-controladores.md').write_text('\n'.join(lines)+'\n')

# Read the actual unit boot image, rather than the incompatible downloaded firmware.
boot=(workspace/'outputs/rayo-s1/respaldo-original/boot.img').read_bytes()
assert boot[:8]==b'ANDROID!'
kernel=struct.unpack_from('<I',boot,8)[0]; ramdisk=struct.unpack_from('<I',boot,16)[0]; page=struct.unpack_from('<I',boot,36)[0]
start=page+((kernel+page-1)//page)*page
cpio=gzip.decompress(boot[start:start+ramdisk])
dest=workspace/'work/android81/stock-ramdisk';dest.mkdir(exist_ok=True)
offset=0; count=0
while offset+110 <= len(cpio):
    header=cpio[offset:offset+110]
    assert header[:6] in (b'070701',b'070702')
    values=[int(header[6+i*8:14+i*8],16) for i in range(13)]
    size,namesize,mode=values[6],values[11],values[1]
    name=cpio[offset+110:offset+110+namesize-1].decode()
    payload=(offset+110+namesize+3)&~3
    if name=='TRAILER!!!':break
    if name.endswith(('.rc','.prop')) or 'fstab' in name or name.startswith('ueventd'):
        path=dest/name
        assert path.resolve().is_relative_to(dest.resolve())
        if mode & 0o170000 == 0o100000:
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(cpio[payload:payload+size]);count+=1
    offset=(payload+size+3)&~3
print(f'Inventoried {len(inventory)} ELF files; mapped {len(critical)} critical components; extracted {count} ramdisk configuration files.')
