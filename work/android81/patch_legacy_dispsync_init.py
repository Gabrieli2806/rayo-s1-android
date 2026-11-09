from pathlib import Path
root=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81/build-root/aosp')
p=root/'frameworks/native/services/surfaceflinger/SurfaceFlinger_hwc1.cpp'
text=p.read_text()
marker='''    char value[PROPERTY_VALUE_MAX];

    property_get("ro.bq.gpu_to_cpu_unsupported", value, "0");'''
replacement='''    // The legacy HWC1 path must initialize DispSync just like HWC2.
    mPrimaryDispSync.init(hasSyncFramework, dispSyncPresentTimeOffset);

'''+marker
if 'mPrimaryDispSync.init(hasSyncFramework, dispSyncPresentTimeOffset);' not in text:
    assert text.count(marker)==1
    p.write_text(text.replace(marker,replacement,1))
print('Legacy DispSync initialization restored')
