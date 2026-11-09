from pathlib import Path
work = Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
root = work / 'build-root/aosp'
p = root / 'hardware/interfaces/graphics/allocator/2.0/default/Gralloc0Allocator.cpp'
text = p.read_text()
old = 'static_cast<int>(info.format), info.usage,'
new = '''static_cast<int>(info.format),
                                // Rayo trial: factory framebuffer handles cannot
                                // be imported across HIDL processes. Allocate a
                                // regular GPU buffer; framebuffer HAL copies it.
                                info.usage & ~static_cast<uint64_t>(GRALLOC_USAGE_HW_FB),'''
if old in text:
    p.write_text(text.replace(old, new, 1))
elif new not in text:
    raise SystemExit('Unexpected allocator source')
print('Applied Rayo-only allocator trial adaptation')
