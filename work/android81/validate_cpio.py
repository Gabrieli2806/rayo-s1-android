from pathlib import Path
import struct,gzip,subprocess,hashlib

work=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
image=(work/'recovery-bootstrap-diagnostic.img').read_bytes()
page=struct.unpack_from('<I',image,36)[0]
k,r=struct.unpack_from('<I',image,8)[0],struct.unpack_from('<I',image,16)[0]
offset=page+(k+page-1)//page*page
cpio=gzip.decompress(image[offset:offset+r])
(work/'bootstrap-diagnostic.cpio').write_bytes(cpio)
result=subprocess.run(['cpio','-it'],input=cpio,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
print('GNU cpio listing exit:',result.returncode)
print(result.stderr.decode(errors='replace')[:1000])
print('Entries:',len(result.stdout.splitlines()))
result=subprocess.run(['cpio','-i','--to-stdout','init'],input=cpio,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
print('Round-trip init equals source:',result.stdout==(work/'recovery-ramdisk/init').read_bytes())
print(result.stderr.decode(errors='replace')[:1000])
