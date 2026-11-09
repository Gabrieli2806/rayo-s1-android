#!/usr/bin/env python3
"""Patch only the known unit's BT vendor imports; preserve a separate original."""
from pathlib import Path
import argparse
import hashlib
import json

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--input', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
source = args.input.read_bytes()
expected = '7821421cd2da8b5ea4bd0dfbb25b727e4043cef7b6fecae31dd8a801585c4398'
if hashlib.sha256(source).hexdigest() != expected:
    raise SystemExit('Refusing to patch an unexpected vendor binary')
if args.output.exists():
    raise SystemExit('Refusing to overwrite an existing output')
patched = source
for old, new in [(b'timer_settime\0', b'rayo_timerset\0'), (b'libdl.so\0', b'libtg.so\0')]:
    if len(old) != len(new) or patched.count(old) != 1:
        raise SystemExit('Unexpected ELF string layout')
    patched = patched.replace(old, new)
if len(patched) != len(source):
    raise SystemExit('ELF size changed unexpectedly')
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_bytes(patched)
print(json.dumps({'bytes': len(patched), 'original_sha256': expected,
                  'patched_sha256': hashlib.sha256(patched).hexdigest(),
                  'requires': 'libtg.so exporting rayo_timerset; Bluetooth HAL NET_ADMIN'}, indent=2))
