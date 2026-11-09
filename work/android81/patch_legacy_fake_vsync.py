from pathlib import Path
root=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81/build-root/aosp')
p=root/'frameworks/native/services/surfaceflinger/DisplayHardware/HWComposer_hwc1.cpp'
text=p.read_text()
old='''        // don't need a vsync thread if we have a hardware composer
        needVSyncThread = false;'''
new='''        // Rayo legacy HWC: the debug fallback must create its timing thread
        // even while the factory compositor continues handling composition.
        needVSyncThread = mDebugForceFakeVSync;'''
if old in text: p.write_text(text.replace(old,new,1))
elif new not in text: raise SystemExit('Unexpected legacy HWC source')
print('Legacy HWC software-vsync fallback thread enabled when requested')
