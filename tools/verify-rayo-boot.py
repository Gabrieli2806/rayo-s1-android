#!/usr/bin/env python3
"""Independently inspect this unit's Rockchip boot packaging; never write a device."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib

KERNEL_SHA = 'e98f7640163344ccc5ae196310a4d3d03738adcbd29d055a3e66f1256ce6af94'


def inspect(data, original, require_static_init=False):
    if len(data) != 33554432 or data[:8] != b'ANDROID!':
        raise ValueError('Unexpected image size/magic')
    if len(original) != 33554432 or hashlib.md5(original).hexdigest() != 'b32f971e7dfce13c708d264c6221844d':
        raise ValueError('Reference is not this unit\'s factory recovery')
    fields = struct.unpack_from('<10I', data, 8)
    baseline = struct.unpack_from('<10I', original, 8)
    if fields[7] != 16384 or any(fields[i] != baseline[i] for i in (0, 1, 3, 4, 5, 6, 7, 8, 9)):
        raise ValueError('Kernel/second size, addresses, page size or reserved fields changed')
    if fields[2] == 0 or fields[2] % 4:
        raise ValueError('Ramdisk must have a four-byte declared length')

    def components(image, header):
        position = header[7]
        blobs = []
        for size in (header[0], header[2], header[4]):
            if size < 0 or position+size > len(image):
                raise ValueError('Component extends beyond image')
            blobs.append(image[position:position+size])
            position += (size+header[7]-1)//header[7]*header[7]
        return blobs

    kernel, ramdisk, second = components(data, fields)
    old_kernel, _, old_second = components(original, baseline)
    if kernel != old_kernel or hashlib.sha256(kernel).hexdigest() != KERNEL_SHA or second != old_second:
        raise ValueError('Factory kernel or second payload changed')
    hasher = hashlib.sha1()
    for blob in (kernel, ramdisk, second):
        hasher.update(blob)
        hasher.update(struct.pack('<I', len(blob)))
    hasher.update(data[32:576])
    if data[576:608] != hasher.digest()+bytes(12):
        raise ValueError('Rockchip extended SHA-1 does not match')
    inflater = zlib.decompressobj(31)
    archive = inflater.decompress(ramdisk, 64*1024*1024)
    if not inflater.eof or inflater.unconsumed_tail or any(inflater.unused_data):
        raise ValueError('Invalid/oversized ramdisk or nonzero gzip padding')
    offset = 0
    entries = {}
    trailer = False
    while offset+110 <= len(archive):
        if archive[offset:offset+6] != b'070701':
            raise ValueError('Unexpected CPIO format')
        record = [int(archive[offset+6+i*8:offset+14+i*8], 16) for i in range(13)]
        name_size, size = record[11], record[6]
        start = offset+110
        if not 1 <= name_size <= 4096 or start+name_size > len(archive) or archive[start+name_size-1] != 0:
            raise ValueError('Invalid CPIO filename')
        name = archive[start:start+name_size-1].decode('utf-8')
        payload = (start+name_size+3)&~3
        if payload+size > len(archive):
            raise ValueError('Truncated CPIO entry')
        if name == 'TRAILER!!!':
            trailer = True
            break
        if name.startswith('/') or '..' in name.split('/') or name in entries:
            raise ValueError('Unsafe/duplicate CPIO name')
        entries[name] = (record[1], archive[payload:payload+size])
        offset = (payload+size+3)&~3
    if not trailer or 'init' not in entries:
        raise ValueError('Missing archive trailer/init')
    mode, init = entries['init']
    if require_static_init:
        if mode&0o170000 != 0o100000 or not mode&0o111 or init[:6] != b'\x7fELF\x01\x01' or struct.unpack_from('<H', init, 18)[0] != 40:
            raise ValueError('Stage-one init must be an executable ARM32 ELF')
        phoff = struct.unpack_from('<I', init, 28)[0]
        phentsize, phnum = struct.unpack_from('<HH', init, 42)
        if phentsize < 32 or phoff+phentsize*phnum > len(init):
            raise ValueError('Invalid init ELF program headers')
        if any(struct.unpack_from('<I', init, phoff+i*phentsize)[0] == 3 for i in range(phnum)):
            raise ValueError('Stage-one init must not require a dynamic interpreter')
    return {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
            'kernel_sha256': KERNEL_SHA, 'ramdisk_bytes': len(ramdisk),
            'cpio_entries': len(entries), 'init_sha256': hashlib.sha256(init).hexdigest(),
            'cmdline': data[64:576].split(b'\0')[0].decode('ascii'),
            'static_init_required': require_static_init}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image', type=Path)
    parser.add_argument('--original-recovery', type=Path, default=Path(__file__).resolve().parents[1]/'local/device-kit/original/recovery.img')
    parser.add_argument('--require-static-init', action='store_true')
    args = parser.parse_args()
    print(json.dumps(inspect(args.image.read_bytes(), args.original_recovery.read_bytes(), args.require_static_init), indent=2))


if __name__ == '__main__':
    main()
