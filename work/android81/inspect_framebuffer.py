"""Read-only decoding of a captured BGRA framebuffer, for display diagnosis."""
from pathlib import Path
import struct
import zlib
work = Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
data = (work / 'rayo-fb0.raw').read_bytes()
w, h = 1280, 720
assert len(data) == w * h * 4
rgb = bytearray(w*h*3)
rgb[0::3], rgb[1::3], rgb[2::3] = data[2::4], data[1::4], data[0::4]
def chunk(kind, payload):
    return struct.pack('>I',len(payload))+kind+payload+struct.pack('>I',zlib.crc32(kind+payload))
scanlines = b''.join(b'\0'+rgb[y*w*3:(y+1)*w*3] for y in range(h))
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB',w,h,8,2,0,0,0))
png += chunk(b'IDAT', zlib.compress(scanlines)) + chunk(b'IEND',b'')
(work / 'rayo-fb0-decoded.png').write_bytes(png)
print('Decoded captured framebuffer')
