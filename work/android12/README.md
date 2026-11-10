# Android 12 / original Google TV research

Target: custom ARMv7 Cortex-A7 Android 12 TV board support for Canon Rayo S1, then the original Google TV Home/menu with a verified supporting service set.

Read [the port assessment](../../docs/GOOGLE_TV_12_PORT.md), [kernel/runtime findings](../../docs/ANDROID12_TV_ASSESSMENT.md), and [current device status](../../docs/STATUS.md).

Current deliverables include reference/component inventories, immutable source research locks, source preparation, and an ARM32/API31 build-only graphics ABI probe. `device/canon/rayos1_12_lab/` is a native-only build product for compiling the actual Android 12 linker/libc and an isolated bootstrap probe; all partition-image outputs are disabled. It is not a bootable board product or a ROM. Do not label an APK collection or this laboratory product an installable Android system, and never flash the reference ADT-3 image.

The working Android 8.1 v14 source and device configuration remain the baseline. Keep experimental 12 changes separate and preserve all original recovery paths and data.

See [the native checkpoint](../../docs/ANDROID12_NATIVE_LAB.md) for successful Android 12 Bionic/EGL/GLES offscreen tests, legacy namespace isolation, and the remaining OS installation gates. The full pinned platform checkout is now synchronized. The unused `dl_namespace_compat` experiment failed and is not the production integration path.
