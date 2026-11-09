# First Android 8.1 component tests

November 8, 2026. Historical stage: stock remained 7.1.2; Android 8.1 had not been flashed.

AOSP android-8.1.0_r81 was synchronized/compiled for ARMv7 Cortex-A7 and 32-bit Binder. Its executables/linker/libraries ran in an isolated device directory.

| Test | Result | Scope |
|---|---|---|
| Stock Mali load | Passed | Android 8.1 core libraries |
| Stock gralloc load | Passed with original libion | Generic libion lacks ion_get_phys |
| Stock compositor load | Passed | Added 8.1 libhardware_legacy/libsync |
| Mali-400 rendering | Passed | EGL 1.4 / GLES 2.0, 1×1 pbuffer |
| Pixel readback | 0,255,0,255; GPU_PBUFFER_PASS | Expected green result |
| Stock libbt-vendor load | Passed | Does not prove Bluetooth pairing/function |

No screen modes changed or framebuffer accessed. Exposed only urandom/mali/ion and read-only proc; temporary mounts were removed. Core libraries were compiled 8.1 except original Rockchip libion; proprietary drivers came from this unit. MAPPED log records identify loaded libraries. Mali also opens /system/lib/egl/libGLES_mali.so; linking that to its vendor copy fixed initial EGL failure.

Pending then: Binder/HwBinder kernel support, verifiable original source/configuration, full Android/services/projector validation, Loader write restoration, MaskROM. Merely aliasing binder does not create independent HIDL domains or scatter-gather transactions. Factory manifest identifies rk/kernel revision 8b7326d577bd29c355ac014df06f6a61fd952a80; exact sources/configuration were unavailable. Rockchip release-3.10 is a reference, not a proven replacement.

Device-tree correction: prj_lcdc is disabled; active lcdc@1010e000 is compatible with rockchip,rk312x-lcdc. Reference: https://source.android.com/docs/core/architecture/hidl/binder-ipc

Historical log names remain exact: prueba-abi-aislada.txt (graphics/library map), prueba-gpu-aislada.txt (render/readback), prueba-bt-carga.txt (Bluetooth load), traza-gpu.txt (successful EGL accesses). This demonstrated compatibility progress, not an installable ROM.
