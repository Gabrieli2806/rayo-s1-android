from pathlib import Path
root=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81/build-root/aosp')
for name in ('clang','clang++'):
 p=root/'prebuilts/clang/host/linux-x86/clang-4053586/bin'/name
 if p.is_symlink(): continue
 data=p.read_bytes()
 if data.startswith(b'#!/usr/bin/python\n'):
  p.write_bytes(data.replace(b'#!/usr/bin/python\n',b'#!/usr/bin/env python\n',1))
  print('Adjusted build-host Python shebang:',p)
for p in (root/'prebuilts/gcc/linux-x86').glob('*/*/bin/*'):
 if p.is_symlink() or not p.is_file(): continue
 with p.open('rb') as stream: prefix=stream.read(32)
 if prefix.startswith(b'#!/usr/bin/python\n'):
  data=p.read_bytes()
  p.write_bytes(data.replace(b'#!/usr/bin/python\n',b'#!/usr/bin/env python\n',1))
  print('Adjusted GCC wrapper:',p.name)
