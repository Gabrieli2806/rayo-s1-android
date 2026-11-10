# Android 12 / original Google TV research

Target: custom ARMv7 Cortex-A7 Android 12 TV board support for Canon Rayo S1, then the original Google TV Home/menu with a verified supporting service set.

Read [the port assessment](../../docs/GOOGLE_TV_12_PORT.md), [kernel/runtime findings](../../docs/ANDROID12_TV_ASSESSMENT.md), and [current device status](../../docs/STATUS.md).

Current deliverables are reference/component inventories, immutable source research locks, a tagged partial source workspace, and an ARM32/API31 build-only graphics ABI probe. There is no AndroidProducts.mk/BoardConfig here because no validated Android 12 board product exists yet. Do not fabricate a lunch target, label an APK collection a ROM, or flash the reference ADT-3 image.

The working Android 8.1 v14 source and device configuration remain the baseline. Keep experimental 12 changes separate and preserve all original recovery paths and data.
