# Google TV 12: original UI target and port preparation

Current checkpoint: November 10, 2026. The owner requested Android 12 with the original Google TV menu/UI; a lookalike is not the selected target. Source-built Android 12 has reached System UI, projected wallpaper and cursor movement in a bounded recovery-slot trial. Settings/service integration remains incomplete and Google TV Home has not yet been tested. Normal startup remains Android 8.1 v14. See [the framework trial record](ANDROID12_FRAMEWORK_TRIAL.md). The reference inspection below records the earlier research stage.

## What was inspected

The official [Android 12 TV download page](https://developer.android.com/tv/release/12) provides separate Android TV and Google TV images for ADT-3. The Google TV archive was downloaded from Google's own server and matched its published SHA-256:

```text
adt3_gtv-stt1.211025.001.z3-factory-91f7efe7.zip
91f7efe7cccfc507a80366c0edb32991df931620ecfc7d2aa765e7b233dc01f1
```

Only system/product/vendor image data and selected packages/configuration were extracted for offline inspection. No flashing script or ADT-3 bootloader was extracted/executed by the preparation tools. The full image targets the Amlogic `deadpool` board, not RK3128, and must not be flashed onto the Canon.

The reference reports SDK 31 and **armeabi-v7a,armeabi**, with no ARM64 userspace ABI. Selected Google TV applications also contain ARMv7 native code. This establishes that this reference experience is not inherently ARM64-only; it does not establish Canon compatibility.

Google describes Google TV as an experience on top of Android TV OS. Its home interface is not automatically included by compiling the AOSP TV product. See the [Google TV FAQ](https://tv.google/intl/en_ca/) and [Google TV developer overview](https://developer.android.com/training/tv/get-started/google-tv).

## Actual reference components

Package signatures were verified with Android apksigner. Version codes, APK SHA-256 values, signer certificate digests, native libraries, SDK levels, and reference privileged allowlists are recorded in `manifests/google-tv-android12-reference-components.json`.

| Role | Package | Minimum API | Observation |
|---|---|---:|---|
| Original Google TV Home | `com.google.android.apps.tv.launcherx` | 28 | Version 1.0.383207611; ARMv7 native library |
| Google Play services | `com.google.android.gms` | 30 | TV-specific package, version 21.30.16; ARMv7 libraries |
| Assistant/search | `com.google.android.katniss` | 24 | Version 5.18.0.20210822.5; ARMv7 libraries |
| Google TV setup | `com.google.android.tungsten.setupwraith` | 28 | Version 1.0.399708934 |
| TV settings | `com.android.tv.settings` | 31 | Two-panel reference settings; platform-signed |
| Google Services Framework | `com.google.android.gsf` | 31 | Reference framework package |
| System UI | `com.android.systemui` | 31 | Reference platform-signed system UI |

The seven inspected APKs total 240,364,326 bytes before other files, extracted libraries, optimization, app data, or updates. **This is storage size, not a RAM measurement or a complete dependency closure.** No Google TV workload has been benchmarked on the Canon's approximately 1 GB RAM.

Home declares Leanback and `com.google.android.feature.AMATI_EXPERIENCE`; the reference also carries privileged permission allowlists. The TV system library is listed as optional in Home's manifest. These facts do not justify blindly declaring capabilities or granting every permission on the Canon. Accounts, recommendations, setup, search, providers, runtime permissions, and online-service availability need testing as a system.

Reference Settings/SystemUI share an ADT-3 platform certificate, while other Google applications have their own signer identities. Copying or re-signing the whole reference UI bundle is not a demonstrated solution. Board-specific settings, platform signatures/shared UIDs, overlays, and Google service contracts must be integrated deliberately.

This is a **2021 Google TV 12 reference**, not a promise of the current Home application's appearance or server-driven features. Keep original packages/signatures local; no APKs, proprietary images, or extracted Google configuration are added to Git or releases by this research step.

## Concrete graphics progress

Android 12 still includes upstream `HWC2On1Adapter`. Its HwcLoader accepts HWC1 minor versions at least 1.1. Our existing Rockchip ABI adapter exposes HWC1.3. The prospective chain is:

```text
Android 12 SurfaceFlinger
  -> graphics composer HIDL service
  -> upstream HWC2On1Adapter
  -> Canon/Rockchip ABI adapter
  -> preserved factory HWC1.3 and display integration
```

The relevant upstream files are pinned separately in `manifests/android12-graphics-bridge-audit.json`. Their availability is encouraging, not a runtime guarantee. Fences, allocator/mapper contracts, buffer transport, callbacks, client composition, Mali loading, display timing, and factory binary dependencies remain gates.

`tools/build-android12-hwc-probe.ps1` successfully built the existing ABI adapter against real Android 12 headers as an **ARM32/API31 shared library**, using NDK 25.2.9519653. All existing factory layout assertions passed. Its dependencies are liblog, libdl, libm, and libc; the probe does not introduce a C++ shared-runtime dependency. This is a build-only artifact, not a complete composer service or an installed/tested driver.

Recorded probe SHA-256: `1eb11e565896f89a426e2b3e6fa124222fa756f3027344549c35ce69ab6e78df`. It was not copied to the projector or placed in a release. Do not substitute it into the working 8.1 runtime.

## Prepared source workspace

A separate Linux ext4 workspace was initialized at `~/android/rayo12`, using AOSP tag `android-12.0.0_r34` (API 31). Its manifest commit is `2ad7a0b216d6eeeceea5ca4881c4d888c1692d60`; the source selection and upstream XML are stored in `manifests/`. This is an initial research baseline, not an assertion that it exactly reproduces the proprietary ADT-3 build or provides current security support.

The initial eight-project subset is recorded in `manifests/android12-selected-source-snapshot.json`. On November 10 the full platform sync completed; immutable project revisions are exported in `manifests/android12-full-source-snapshot.xml`. A native-only Cortex-A7/Binder32 lab product builds successfully. This is not yet a bootable board product. Earlier branch-based audit locks remain separate research snapshots, not the platform product manifest.

## Implementation sequence

1. Audit the selected release's Binder protocol, getrandom/memfd fallbacks, BPF/networking, init, APEX/linker configuration, and allocator requirements against the measured stock kernel. Choose justified backports or a separate kernel port. Missing syscalls are concrete gaps, not individually proven unavoidable boot blockers.
2. Build a custom Cortex-A7/ARMv7 TV product with low-RAM settings and a consistent manifest. Do not inherit emulator hardware configuration or ADT-3 vendor files as board support.
3. Validate Android 12 native libraries and the factory graphics dependency chain in an isolated test environment. Integrate upstream HWC2On1 plus the factory ABI adapter only after their buffer/fence contracts are checked.
4. Obtain Android 12 boot, ADB, and physical projection; port audio, accelerated AVC, radios, input, battery/power, and projector controls. The working 8.1 integrations provide evidence and source, not automatic binary compatibility.
5. Integrate the original Google TV Home and the smallest **verified** supporting service set. Treat settings/system UI/signing and account/setup flows explicitly. Benchmark RAM, storage, frame timing, and real user interaction before expanding the bundle. Do not install all ADT-3 applications merely because they were bundled.
6. Use a separately reviewed recovery/userdata-file test design preserving v14 normal boot, its overlay/data, independent recovery backup, and the previous system backups. No partition layout change, wipe, or bootloader replacement is part of this research step.

The initial child-process tests below were followed by temporary OS trials documented in [ANDROID12_FRAMEWORK_TRIAL.md](ANDROID12_FRAMEWORK_TRIAL.md). Google services and Home remain a separate, unvalidated integration stage. Temporary recovery trials preserve normal v14 boot and restore factory recovery after return.

## Reproduce the bounded inspection

To recreate the selected source subset on a case-sensitive Linux filesystem:

```bash
mkdir -p ~/android/rayo12
cd ~/android/rayo12
repo init -u https://android.googlesource.com/platform/manifest -b android-12.0.0_r34 --depth=1 --no-clone-bundle --no-use-superproject
repo sync -c --no-clone-bundle --no-tags -j4 device/google/atv hardware/interfaces hardware/libhardware system/core system/logging frameworks/native system/netd system/bpf
```

The command above reproduces the historical bounded audit. For native building, synchronize the full selection with `repo sync -c --no-clone-bundle --no-tags --fail-fast -j8` and verify the full snapshot. Source modifications must be preserved as versioned patches/replay tools/device sources in this repository rather than leaving the only copy in the native Linux checkout.

On Windows, load the project toolchain and use Ubuntu's `android-sdk-libsparse-utils` and `e2fsprogs` for image inspection:

```powershell
. .\local\setup\enter-project-env.ps1
python tools/prepare-google-tv-reference.py --download
python tools/inspect-google-tv-reference.py
python tools/audit-android12-sources.py --lock manifests/android12-graphics-bridge-audit.json
tools/build-android12-hwc-probe.ps1 -Source '\\wsl.localhost\Ubuntu-24.04\home\gabri\android\rayo12'
```

The last command requires the selected source projects to have been synchronized on Linux. Paths are parameters, not a requirement to reuse this PC's username. Large archives/images, extracted packages, native source caches, and built probes stay outside versioned source. No command above accesses or writes a connected Android device.
