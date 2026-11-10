from pathlib import Path
import gzip
import hashlib
import json
import os
import stat
import struct
import sys

import argparse
parser=argparse.ArgumentParser(description='Pack a trial recovery while preserving the factory kernel')
parser.add_argument('--ramdisk',required=True,type=Path)
parser.add_argument('--output',required=True,type=Path)
parser.add_argument('--original-recovery',type=Path,default=Path(__file__).resolve().parents[1]/'local/device-kit/original/recovery.img')
parser.add_argument('--cmdline', default='buildvariant=userdebug androidboot.selinux=permissive',
                    help='ASCII kernel command line; default preserves the established v14 packaging')
args=parser.parse_args()
try:
    command=args.cmdline.encode('ascii')
except UnicodeEncodeError:
    raise SystemExit('Boot command line must be ASCII')
if len(command)>511 or any(byte<32 or byte>126 for byte in command):
    raise SystemExit('Boot command line exceeds the header or contains control characters')
source=args.ramdisk.resolve()
destination=args.output.resolve()
if not source.is_dir():raise SystemExit('Missing ramdisk directory')
if destination.exists():raise SystemExit('Refusing to overwrite an existing output')
original=args.original_recovery.read_bytes()
if len(original)!=33554432 or hashlib.md5(original).hexdigest()!='b32f971e7dfce13c708d264c6221844d':
    raise SystemExit('Original recovery does not match this unit')
archive=bytearray()
def pad():
    archive.extend(b'\0'*((-len(archive))%4))
def entry(name,mode,payload,inode,rdev=(0,0)):
    encoded=name.encode()+b'\0'
    values=(inode,mode,0,0,2 if stat.S_ISDIR(mode) else 1,0,len(payload),0,0,rdev[0],rdev[1],len(encoded),0)
    archive.extend(b'070701'+b''.join(f'{number:08x}'.encode() for number in values))
    archive.extend(encoded);pad();archive.extend(payload);pad()
for number,path in enumerate(sorted(source.rglob('*')),1):
    info=path.lstat()
    name=path.relative_to(source).as_posix()
    if stat.S_ISLNK(info.st_mode): payload=os.readlink(path).encode()
    elif stat.S_ISREG(info.st_mode): payload=path.read_bytes()
    elif stat.S_ISDIR(info.st_mode): payload=b''
    else: raise RuntimeError('Unexpected ramdisk object: '+name)
    # DrvFS does not reliably retain Unix mode bits. Set deterministic modes.
    if stat.S_ISLNK(info.st_mode): mode=stat.S_IFLNK|0o777
    elif stat.S_ISDIR(info.st_mode): mode=stat.S_IFDIR|(0o771 if name=='data' else 0o755)
    else:
        executable=name=='init' or ((name.startswith('sbin/') or name.startswith('system/bin/')) and not name.endswith('.ko'))
        mode=stat.S_IFREG|(0o755 if executable else 0o644)
    entry(name,mode,payload,number)
for index,(name,major,minor,permissions) in enumerate((
    ('null',1,3,0o666),('zero',1,5,0o666),('random',1,8,0o666),
    ('urandom',1,9,0o666),('kmsg',1,11,0o600),('console',5,1,0o600)),100000):
    entry('dev/'+name,stat.S_IFCHR|permissions,b'',index,(major,minor))
entry('TRAILER!!!',0,b'',0)
ramdisk=gzip.compress(bytes(archive),compresslevel=9,mtime=0)
# Rockchip's hardware SHA DMA consumes words. The factory boot and recovery
# both pad their gzip streams to a 4-byte length before recording/hashing size.
ramdisk+=bytes((-len(ramdisk))%4)
page=struct.unpack_from('<I',original,36)[0]
header=bytearray(original[:page])
kernel_size,_,old_ramdisk_size,_,second_size=struct.unpack_from('<5I',original,8)
kernel=original[page:page+kernel_size]
second_offset=page+((kernel_size+page-1)//page)*page+((old_ramdisk_size+page-1)//page)*page
second=original[second_offset:second_offset+second_size]
if hashlib.sha256(kernel).hexdigest()!='e98f7640163344ccc5ae196310a4d3d03738adcbd29d055a3e66f1256ce6af94':
    raise SystemExit('Unexpected factory kernel')
struct.pack_into('<I',header,16,len(ramdisk))
header[64:576]=command.ljust(512,b'\0')
header[608:1632]=bytes(1024)
digest=hashlib.sha1()
for blob in (kernel,ramdisk,second): digest.update(blob);digest.update(struct.pack('<I',len(blob)))
# Rockchip SecureNSModeBootImageShaCheck additionally hashes tags, page size,
# both unused/OS-version words, board name, and the first 512 cmdline bytes.
# This extension exactly reproduces the factory recovery header's SHA-1.
digest.update(header[32:576])
header[576:608]=digest.digest()+bytes(12)
image=bytearray(header)
for blob in (kernel,ramdisk,second):
    image.extend(blob);image.extend(b'\0'*((-len(image))%page))
assert len(image)<=len(original)
image.extend(bytes(len(original)-len(image)))
destination.write_bytes(image)
assert image[page:page+len(kernel)]==kernel
offset=page+(len(kernel)+page-1)//page*page
assert gzip.decompress(image[offset:offset+len(ramdisk)])==archive
print(json.dumps({'image':str(destination),'bytes':len(image),'ramdisk_bytes':len(ramdisk),
                  'sha256':hashlib.sha256(image).hexdigest(),'stock_kernel_sha256':hashlib.sha256(kernel).hexdigest(),
                  'warning':'Recovery-slot experiment, not a replacement for boot/system/uboot.'},indent=2))
