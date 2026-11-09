from pathlib import Path
import subprocess

root=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
art=root/'build-root/aosp/art'
path=art/'runtime/mem_map.cc'
text=path.read_text()
marker='''                          off_t offset,
                          bool low_4gb) {
'''
patch='''#if defined(__linux__) && !defined(__ANDROID__)
  // Modern Linux hosts may treat an address as a placement hint. ART requires
  // this exact address. NOREPLACE rejects conflicts without overwriting any
  // mapping; never change the Android target's behavior or use MAP_FIXED here.
  if (addr != nullptr && (flags & MAP_FIXED) == 0) {
    flags |= 0x100000;  // Linux MAP_FIXED_NOREPLACE, absent in the old host sysroot.
  }
#endif
'''
if patch not in text:
    assert text.count(marker)==1
    path.write_text(text.replace(marker,marker+patch,1))
(root/'host-art-mmap.patch').write_bytes(subprocess.check_output(['git','-C',str(art),'diff','--','runtime/mem_map.cc']))
print('Applied host-only exact-address mapping compatibility patch.')
