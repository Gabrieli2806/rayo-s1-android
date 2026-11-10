# Android 12 TV assessment and current implementation boundary

Date: November 9, 2026. The owner requested Android 12 with a TV-oriented experience if adaptation is modest, while continuing hardware validation. **The investigation found a substantial port, not a small ROM substitution. No Android 12 image has been built, flashed, or claimed working.**

The subsequent explicit request for the original Google TV UI is tracked in [GOOGLE_TV_12_PORT.md](GOOGLE_TV_12_PORT.md). The November 10 [native lab checkpoint](ANDROID12_NATIVE_LAB.md) adds a full pinned source checkout, source-built Cortex-A7/Binder32 runtime, and verified offscreen Android 12 EGL/GLES rendering through the original Mali driver. The official ARM32 reference core instead requires ARMv8 instructions and cannot run on this CPU. These extend this assessment without claiming a bootable or working Android 12 OS.

## Verified upstream findings

`tools/audit-android12-sources.py` fetched 11 bounded official source snapshots. Immutable project commits, file URLs, lengths, and SHA-256 values are recorded in `manifests/android12-tv-source-audit.json`. Run the script again to reproduce the source audit without downloading/building the entire platform. This lock is not a full AOSP manifest.

| Finding | Evidence | Meaning for this projector |
|---|---|---|
| Android 12 includes a 32-bit ARM TV product | `device/google/atv`, `products/aosp_tv_arm.mk` | ARMv7 is not by itself proof that Android 12 is impossible |
| That generic product expects 64-bit Binder, VNDK, compatible vendor interfaces, and dynamic partition assumptions | Comments/settings in the same product | It is not directly compatible with this stock board or a flashable projector ROM |
| TV has a low-RAM configuration | `products/atv_lowram_defaults.mk` | A lightweight TV product can be designed, but the configuration does not solve hardware support |
| Android 12 libbinder still has a binder32bit build variable | `frameworks/native/libs/binder/Android.bp` | Protocol 7 is a porting issue, not proof that all 32-bit Binder support was removed |
| SurfaceFlinger uses the newer composer interfaces/HWC2 implementation | SurfaceFlinger Android.bp/HWComposer.cpp | The current 8.1 HWC1 adapter is not a drop-in Android 12 graphics integration |
| BPF initialization is integrated into boot | `system/bpf/bpfloader/BpfLoader.cpp` | Kernel/userspace networking requirements need deliberate adaptation; missing programs/features cannot simply be ignored |

The official [Android 12 for TV page](https://developer.android.com/tv/release/12) supplies Android TV and Google TV images specifically for ADT-3 developer devices. Those images are not for this Canon board. Google TV's branded application/services experience is distinct from building an AOSP TV system. The earlier default was an AOSP TV product without Google services; the owner's latest original-UI request now makes Google TV component/service integration an explicit research stage after board support. A TV-style launcher does not itself upgrade Android or supply Google certification.

## Runtime evidence from the actual unit

The ARM32 probe `work/probes/kernel_capabilities.c` was built with NDK 25.2.9519653/API 21 and executed on stock 7.1.2 and the 8.1 trial, both using kernel 3.10.104.

| Query | Stock | Trial |
|---|---|---|
| Userspace pointer width | 32 bits | 32 bits |
| PR_GET_SECCOMP | 0, supported/unrestricted | 0, supported/unrestricted |
| PR_GET_NO_NEW_PRIVS | 0, supported/not set | 0, supported/not set |
| getrandom syscall, zero length | ENOSYS (38) | ENOSYS (38) |
| memfd_create syscall | ENOSYS (38) | ENOSYS (38) |
| BPF map creation syscall | ENOSYS (38) | ENOSYS (38) |
| Original binder protocol | 7 | 7 |
| hwbinder/vndbinder | Absent | Protocol 7, isolated backport |

These measurements establish concrete gaps. They do not independently prove each missing syscall is an unavoidable boot blocker; fallback behavior and required backports must be audited in the chosen Android 12 components. The archived `kernel.config` is empty and must not be mistaken for a recovered factory kernel configuration.

## Required work before any Android 12 device trial

1. Select a custom ARM32 TV product and consistent pinned platform manifest. Do not inherit emulator hardware configuration or flash a TV-box image.
2. Decide between auditing/backporting required interfaces into the current kernel and a separate newer-kernel board port. Preserve the working 8.1 experiment and stock restore paths.
3. Integrate the original Rockchip graphics/display stack through the Android 12 composer/gralloc contracts, or independently validate an alternative kernel/Mesa/display stack.
4. Port media, audio, Wi-Fi, Bluetooth, input, power/battery, storage, and projector behavior. Current 8.1 fixes are valuable evidence, not a stable vendor interface guaranteed across four platform releases.
5. Fit memory/storage budgets and test actual video decoding, frame timing, physical output, and prolonged operation. TV UI alone does not reduce all framework costs.
6. Prepare a recovery-slot/userdata-file trial design with verified rollback. Do not replace stock boot/system/bootloader as a shortcut.

## Work completed instead of an unverified Android 12 flash

Captured stock and trial diagnostics, measured actual kernel capabilities, created a portable hardware-validation application with original AVC/AAC test media, restored codec declarations, tested software playback, and tested the factory Rockchip AVC decoder. Audio and initial software video were physically confirmed by the owner. Bluetooth integration was advanced through feature declarations, firmware, permissions/capabilities, and a process-local vendor timer compatibility fix. Current confirmation and remaining work are tracked in STATUS.md and DEVICE_VALIDATION.md.
