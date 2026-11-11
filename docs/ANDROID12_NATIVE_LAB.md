# Android 12 native device checkpoint

Date: November 10, 2026. Normal boot remains Android 8.1 v14. Android 12 init, native graphics and core ART passed temporary recovery-slot trials using the preserved kernel. Subsequent [framework trials](ANDROID12_FRAMEWORK_TRIAL.md) reached System UI, projected wallpaper and cursor movement; Settings and launcher integration remain incomplete. Google TV Home is not validated or installed as the working interface. Earlier sections describe the initial disposable-process experiments.

## Source and CPU baseline

The complete AOSP `android-12.0.0_r34` checkout synchronized successfully. The immutable full project revisions are in `manifests/android12-full-source-snapshot.xml`; the earlier eight-project audit snapshot remains historical. Native sources and output are on Linux ext4, currently `~/android/rayo12` and `~/android/rayo12-out`.

`rayos1_12_lab-eng` builds ARMv7-A/NEON Cortex-A7 code with Binder32. All image outputs are disabled. Android 12 linker, actual runtime-APEX libc/libm/libdl/libdl_android, and the bootstrap probe were compiled from source. Do not package the small platform libc interface stub as a runtime library.

The official ADT-3 runtime is ARM32 **but compiled for Cortex-A53/ARMv8 AArch32**. Its linker produced SIGILL on this Cortex-A7. ARM32 alone does not establish instruction compatibility. Google TV Home's inspected JNI library instead declares ARMv7; that distinction does not validate the rest of the reference bundle. Never deploy the ADT-3 core runtime or firmware to this projector.

## Verified native execution

Source-built Android 12 Bionic ran on the preserved kernel 3.10.104:

- 32-bit pointers, working threads/heap and monotonic clock.
- `getentropy()` succeeded through upstream fallback; raw `getrandom` returned ENOSYS.
- The loaded libc path was the new private Android 12 bundle, not the running OS libc.
- Binder and HwBinder reported protocol 7.
- Android 8.1 remained boot-complete after the tests.

Local evidence: `local/android12-native-runs/20261109-235440-06c356a5` and `20261110-001458-d1c9be0b`. Some subsequent diagnostic files were added to these private device directories; use fresh tool-created directories to reproduce clean tests.

## Verified offscreen graphics

The source-built Android 12 EGL/GLES wrappers, Bionic, libvndksupport, and their Android 12 dependencies successfully rendered a green 1×1 pbuffer using the original Mali-400 driver. Output included:

```text
EGL display=0x1 platform=1
EGL 1.4 vendor=Android
GL renderer=Mali-400 MP version=OpenGL ES 2.0
PIXEL rgba=0,255,0,255
GPU_PBUFFER_PASS
```

Local evidence: `local/android12-graphics-runs/20261110-003223-be317600`. Process maps show separate new and legacy C++ libraries, the source-built EGL wrapper, and a single factory Mali binary. Direct-driver namespace/control probes also passed (`20261110-002521-32f70543`, `20261110-003213-f2ae270c`).

The default linker namespace contains new Android 12 libraries. A visible isolated `sphal` namespace retains the working legacy private dependency graph and shares only selected new core libraries/libvndksupport. Replacing the entire legacy C++ graph with new libbase/libc++ failed; do not repeat that as a system modification. A temporary symbol shim also failed and is not the chosen integration path.

For the wrapper test, a static helper creates a **new mount namespace**, makes propagation recursively private, retains a read-only view of the original `/system/lib`, and binds the private new library directory read-only at `/system/lib` **only in that child**. The helper then executes the new linker/probe and exits. A 20-second alarm bounds execution. No global mount, system file, display mode, service, property, or boot partition is replaced. Both OEM Mali paths must resolve to the same file: preserving `/system/lib/egl` and factory HAL lookup paths in the private view was necessary for a valid EGL display. The parent mount namespace is recorded before/after.

This verifies offscreen rendering only. It does **not** verify HWC2On1, buffer allocation/import across services, fences, SurfaceFlinger, physical projection, input latency, Android 12 boot, networking, or Google TV Home. None should be inferred from a green pixel.

### Allocator/mapper and HWC follow-up

The source-built Android 12 allocator/mapper passthrough implementations successfully allocated a 64×64 RGBA buffer through the factory gralloc, imported it, locked it, wrote/read one pixel, unlocked it and freed it. Output: `stride=64 lock=1 pixel=1 unlock=1 free=1`, `GRALLOC_BUFFER_PASS`. This is a **local passthrough** test, not cross-process HIDL buffer/fence transport. The test uses the actual vendor libhardware build variant; the core/VNDK-APEX copy in this workspace was not interchangeable for routing historical system-path HALs into sphal.

The upstream composer 2.1 service, allocator 2.0 service/implementation, mapper 2.0 implementation and the namespace-aware `hwcomposer.rayo12` ABI adapter compile. The wrapper reuses the unchanged, versioned 8.1 adapter and static layout assertions; only factory-library loading is routed through sphal. The factory HWC and new adapter both loaded and resolved HMI under the new runtime (`FACTORY_HWC_LOAD_PASS`). The probe intentionally **did not open HWC**, register callbacks, prepare/set layers or submit a framebuffer.

Legacy libgraphicsenv/libnativeloader additionally imported four namespace functions from `libdl.so@LIBC_PLATFORM`, now moved upstream to libdl_android. The replay tool restores these four exact proxies in the new libdl, delegating to the existing Android 12 loader implementations: create_namespace, get_exported_namespace, init_anonymous_namespace and link_namespaces (all with the `android_` prefix). Namespace checks remain in the actual loader. No vendor ELF or running library was overwritten. This supersedes the failed standalone shim experiment.

After that libdl change, bootstrap, Android 12 EGL/GLES and allocator/mapper tests all passed again. Local evidence: `local/android12-native-runs/20261110-005209-a9f0eedd`; graphics runs `20261110-005150-ae0b8c0c` (HWC load), `20261110-005213-deec118a` (EGL), `20261110-005223-5e755af3` (buffer). v14 boot and independent recovery hashes were checked before each run; the parent mount namespace and boot-complete state remained intact.

### Android 12 GraphicBuffer client / existing allocator transport

`rayo12_graphicbuffer_probe` additionally passed through the real Android 12 libui client and an Android 12 mapper 2.0 passthrough implementation, using the **existing working 8.1 allocator service** over public HIDL/HwBinder transport. Output: `GraphicBuffer init=0 stride=64`, `GRAPHICBUFFER_TRANSPORT_PASS`. Local evidence: `local/android12-graphics-runs/20261110-011642-d9ab06d7`. This demonstrates a real protocol-7 client transaction and buffer allocation/import/CPU lock/unlock through the new client stack. It does not validate the Android 12 allocator server, GPU/display fences, composer submission or a full Android 12 boot.

The disposable child view places the new mapper under `/system/lib/hw` to load its C++ graph in the new namespace, and a checked private factory gralloc copy under `/vendor/lib/hw` to load the OEM C module through the legacy sphal namespace. The child additionally shadows only that vendor HAL directory read-only; the parent stays unchanged. A symlink escaping the vendor HAL directory was correctly rejected by upstream libhardware's realpath check. The test stages a real checked copy instead; **the path check was not bypassed**. Preserve this separation in the eventual product: blindly putting new C++ HIDL implementations in the old private namespace is not a valid integration.

## Binder32 build fixes

`tools/apply-android12-legacy-build-fixes.py` applies narrow host-width logging casts and generates dedicated Binder32 export maps. The upstream ARM32 map assumes 64-bit Binder offsets, which do not match protocol-7 `uint32_t` offsets. Exact `freeBuffer`/`ipcSetDataReference` manglings are corrected in the generated maps; the absent uint64 logging instantiation is omitted because the existing uint32 export is used. Upstream maps remain intact. The dedicated source workspace selects these Rayo maps for its ARM32 build; it must not be reused as an unmodified Binder64 build workspace. No wire structures, kernel protocol guards, or linker undefined-symbol checks are bypassed.

## Reproduction

After a complete pinned source sync, on Linux:

```bash
bash tools/build-android12-native-lab.sh
bash tools/build-android12-native-lab.sh libbinder libui libEGL libGLESv1_CM libGLESv2 libvndksupport libbase liblog libc++
```

On Windows with the project environment loaded:

```powershell
python tools/package-android12-native-smoke.py --out-dir '<Linux OUT_DIR via UNC>'
./tools/build-android12-graphics-probes.ps1
python tools/run-android12-native-smoke.py
python tools/run-android12-native-smoke.py --execute
python tools/run-android12-graphics-smoke.py --execute --platform-egl --private-mount-helper outputs/android12-probes/rayo12-private-mount-probe --out-dir '<Linux OUT_DIR via UNC>' --probe outputs/android12-probes/rayo12-gpu-namespace-probe
```

The device tools check the known unit, root v14 identity, kernel, boot MD5, and independent original recovery MD5 before staging. They create unique private directories on userdata and verify transferred checksums. Logs and all binaries stay ignored under `local/` and `outputs/`. The reference-runtime preparation tool is for reproducing the failed architecture audit, not for deployment.

## Remaining installation gates

`device/canon/rayos1_tv12` and `tools/build-android12-tv.sh` now prepare a separate actual TV framework/product configuration: Cortex-A7, Binder32, low-RAM TV defaults, flattened APEXes, no dynamic partitions, and density 160 matching the running device's measured 1280×720 logical display. Image generation remains disabled while OEM/root/init/linker/rollback integration is incomplete. No replacement/lookalike launcher is selected; the original Google TV Home integration remains a later stage. This product is not an installable ROM yet. Its build uses a separate `~/android/rayo12-tv-out` output directory.

The first selected TV build completed successfully in 21:13: `services SystemUI TvSettings surfaceflinger init adbd linkerconfig netd bpfloader`. Log: `local/google-tv-reference/tv-framework-build.log`. `droidcore` also completed in 06:45, including SELinux policy checks. With partition images disabled, that target did not populate all selected product files. The full-tree `files` target subsequently completed successfully (35:03 for the resumed run, `local/google-tv-reference/tv-product-files-build.log`), producing approximately 698 MiB under system. This is a built AOSP TV tree, not a hardware-integrated or boot-verified ROM. Init's compiled ELF interpreter is `/system/bin/bootstrap/linker`.

## First boot diagnostics

`prepare-android12-stage1.py` creates a separate 128 MiB readonly init/USB-only system and a guarded recovery ramdisk, with temporary RAM-backed data/cache. It deliberately does not start the framework, HWC or radios. The original 8.1 static rescue ADB/shell remain available in this diagnostic. `verify-rayo-boot.py` independently checked factory/v14 images and rejected four corrupted variants. Adding a configurable header command line to the packer preserved an exact v14 rebuild SHA-256. The static bootstrap refused normal ADB execution with exit 64, before mounting or rebooting.

The first two guarded recovery-slot runs did not reach Android 12 init. Both stopped at the userdata identity guard; the 180-second normal-reboot watchdog worked, v14 returned, and the original independent recovery was restored with matching MD5. The owner confirmed the projected 8.1 menu after the first run. Evidence: `local/android12-stage1-runs/20261110-105203-f9857755` and `20261110-110418-d7f4747c`; each retains RESULT, fresh original recovery and prior-kernel logs. No Android 12 framework/Google TV boot is established by these attempts.

The logs exposed two board-specific facts. Recovery adds the `parameter` partition to its enumeration (15 partitions), while normal boot's userdata is mmcblk0p14. Selecting minor 14 during recovery read zero metadata, and the UUID guard correctly refused to mount it. The corrected bootstrap selects the measured Linux physical userdata start `0x5f4000` plus size bounds, then still checks this unit's exact filesystem UUID. Rockchip Loader's recorded relative address is `0x5f2000`, with an additional `0x2000`-sector physical prefix; do not confuse the address spaces or use an old minor number across boot modes. Run `20261110-111413-fc9b30d8` verified selection of recovery minor 15, exact UUID, image hashing and readonly loop mount; it then stopped at a missing cache mountpoint. The normal-return watchdog and original recovery restoration both passed.

After creating `/data/cache`, run `20261110-112226-9d6bdadd` executed the source-built Android 12 init as PID 1 and loaded SELinux policy version 30. It failed creating the property service socket because the custom first stage omitted `/dev/socket`; the corrected fatal handler returned to normal 8.1 and original recovery was restored. The bootstrap now provides the basic filesystem setup from upstream FirstStageMain, including socket/devpts directories and temporary early mountpoints.

Run `20261110-112738-e2429e08` reached Android 12 init and working USB ADB: release 12, SDK 31, and `sys.rayo12.stage=init-ready`. Both `/realdata` and `/system` were readonly and `/data` was tmpfs. The reused static rescue daemon dropped to UID 2000, preventing the required root/PID-1-executable checks. Therefore the complete stage-one result is **NOT PASSED**, despite confirmed Android 12 init/USB progress. At approximately 180 seconds the watchdog returned to normal v14 and the original recovery MD5 was restored and verified. Resolve rescue transport privileges before expanding to graphics/framework; this does not establish a full TV boot or projected Android 12 image.

The subsequent source-built Android 12 recovery daemon enumerated USB but remained offline with its upstream asynchronous transport. An explicitly selected synchronous FunctionFS adapter then passed the full gate in `20261110-120400-d199fe21`: release 12, SDK 31, UID 0, PID 1 `/system/bin/init`, matching init binary and readonly storage checks. Multiple shell reads worked. The watchdog returned to normal v14 and original recovery was restored and hash-verified. See [ADB compatibility source and limits](../work/android12/adb/README.md). This establishes init/USB bringup, not framework, graphics or Google TV.

Graphics integration now has an optional, still 128 MiB readonly diagnostic root. `android12_graphics_root.py` stages only the recursively inspected legacy ELF closure from the verified v14 overlay/factory image and new source-built dependencies. The one Mali inode is shared through both OEM paths; old C++ remains in `sphal`. A mistaken earlier probe selection caused the ninth automatic test to fail in the default namespace. The correctly checksummed namespace probe was then uploaded to RAM-backed data and passed EGL and HWC-load-only checks under the actual Android 12 init root (`20261110-121054-31fee39a/graphics-current-probe.txt`, `hwc-load-current-probe.txt`). That run's automatic NOT PASSED result is retained; these are separate follow-up observations.

The tenth profile exposed incorrect toolbox/toybox staging and omission of the existing HwBinder module. The controller exited early; the independent watchdog returned to v14, and a separately guarded restoration verified boot and restored original recovery. Evidence is `20261110-121633-56cc0448/manual-restoration.json`. A manually checked upload of the unchanged v14 module (MD5 `916b9b43766452ca9b99c54b64be6482`, exact kernel-banner/layout/symbol guards intact) allowed the Android 12 allocator transport test to pass during that boot. The native lshal diagnostic crashed while inspecting process details; do not use its exit status as a successful graphics gate. The controller now continues bounded observation/restoration even if an optional diagnostic raises an exception, and uses a dedicated composer client instead.

Run `20261110-122443-f6109e71` passed all automatic gates: root init/USB, offscreen Android 12 EGL, Android 12 composer `createClient`/hotplug/config query (display 1, config 0, 1280x720), and GraphicBuffer allocation/lock/write/unlock/free through the new Android 12 allocator service. The root loads the unchanged, guarded v14 HwBinder module only when explicitly triggering graphics. The watchdog returned to v14 and original recovery was restored with its expected MD5. This is a hardware-integrated native graphics service test; no frame submission, projected output, ART/framework or Google TV Home boot is established by it.

The next native SurfaceFlinger profiles are explicitly separate from framework/app boot. The twelfth run (`20261110-122909-b1b0c1c3`) accepted bufferless color-layer transactions, but the owner reported no projected image, including after a temporary replay of the old RGBA framebuffer-format correction. The thirteenth (`20261110-123656-254906e1`) added an in-flight layer dump and diagnostic stderr logging. Bufferless layers alone leave SurfaceFlinger in its BOOTLOADER stage: upstream transitions to BOOTANIMATION on the first newly latched buffer. Transaction acceptance therefore was not adequate proof of frame submission. The probe now queues an actual 1280x720 RGBA buffer instead.

The fourteenth run (`20261110-124510-f1711bb5`) successfully locked, filled and queued that real buffer. Its automatic allocation comparison isolated another barrier: RGBA and BGRA 1280x720 allocations both returned status 2 with `HW_FB` (usage `0x1b00`); RGBA without that flag (usage `0xb00`) passed with stride 1280. SurfaceFlinger reported failure allocating/dequeuing the framebuffer target, and its HWC client target remained null. An opt-in source adaptation now selects ION render/composer consumer usage in FramebufferSurface, retaining upstream behavior unless `ro.rayo.graphics.ion_client_target=true`. This preserves allocator validation and the factory HAL. Its projection result is not yet established. All four profiles returned to normal v14 and original recovery was restored and hash-verified. Programmatic PASS means only the enumerated native checks; physical projection is recorded separately and has not passed.

The ION adaptation compiled and ran in `20261110-155852-4c9efb4d`. SurfaceFlinger acquired a non-null client target, submitted it through HWC2On1/factory HWC, and advanced framebuffer flips. The owner still reported no projected image. Normal v14 returned and original recovery was restored and verified. The active diagnostic now continuously posts a real native buffer and samples a SurfaceFlinger capture; a readonly per-attribute inventory compares framebuffer/DLP state to working v14. `capture_rayo_display.py` reads only known status attributes, excluding calibration/debug/action controls. The working baseline's physical scanout is 864x480 with 1280x720 source buffers and scaling; do not treat logical size as lens resolution or change optical calibration to match it.

**Projected native composition passed** in `20261110-160923-6301117b`: the owner reported seeing the colors. The probe repeatedly queued actual native buffers and read back the composed display through ScreenshotClient. Capture was RGBA 1280x720, stride 1280, with samples `ff0000ff ff00ff00 ffff0000 ffffffff ffffff00 ffff00ff ff00ffff ff000000`, exactly matching the eight intended color bars. Readonly DLP controls matched the working v14 reference; framebuffer scanout settled to ARGB888 at source width 1280 scaled to the existing physical 864x480 mode. No brightness/calibration/input-selection write was applied. This does not establish first-frame-only behavior, sustained UI frame rate, ART/framework, apps, audio/radios or Google TV Home. The independent watchdog returned to normal v14; original recovery was restored and read back with its expected hash. The normal boot partition never changed.

The bootloader also replaces the boot-image header command line. Therefore the diagnostic installs its RC at Android 12's default `/system/etc/init/hw/init.rc`, and the dedicated init build defaults fatal reboot to normal boot. The upstream fatal-target parser also needed `.size()` rather than `sizeof(std::string)`. A header argument alone does not establish the active behavior; the prior kernel log is the evidence. No kernel or bootloader was changed.

The full-tree build exposed a host dependency of the bundled old RenderScript compiler: `libncurses.so.5`. `tools/setup-android12-host-compat.sh` installed real `libncurses5`/`libtinfo5` version `6.3-2ubuntu0.3` amd64 from Ubuntu's signed official jammy-security repository, alongside the host's current ABI. It uses separate temporary native APT lists and does not change the default Ubuntu 24.04 package sources. No ABI-6 symlink or unsigned third-party package was substituted. Local source/plan/version records are in `local/setup/ncurses5-apt`; rerun that administrative setup on a fresh compatible Linux host before the full build.

The explicit legacy-network source experiment compiled netd and bpfloader, with no live network test or rule changes. See [patch provenance and limits](../patches/android12/README.md). The TV product selects that backend; runtime firewall/accounting/network validation remains outstanding.

The [first OS boot plan](ANDROID12_FIRST_BOOT_PLAN.md) records protected paths, fresh rollback copies, recovery-slot limitations and the pre-installation gates. Read it before creating or writing any Android 12 boot/recovery artifact. Read-only kernel inventory also confirmed legacy LMK and vmpressure/event-control nodes, with no PSI memory file. Upstream Android 12 lmkd contains in-kernel LMK detection and vmpressure fallback; neither has been runtime validated under the new OS, and no live memory-killer settings were changed.

Build/integrate the HWC2On1 composer and legacy allocator/mapper bridge, preserving the factory HWC ABI adapter and buffer/fence contracts. Then create a separate actual TV board product, root/init/APEX/linker configuration and a userdata-file trial with verified rollback to v14 normal boot. Resolve the kernel's missing BPF networking backend without silently disabling packet filtering. Only then package a boot candidate and test physical projection, controls, radios/media and the original Google TV application/service set. There is no installable Android 12 candidate at this checkpoint.
## Managed runtime checkpoint — November 10, 2026

Run `20261110-164755-375a0a0e` automatically passed the core Android 12 ART/Java probe on the preserved 3.10.104 kernel. It executed the source-built ARM32 runtime, created a Java worker thread, sorted and verified an array, and printed `ART12_MANAGED_RUNTIME_PASS`. The controller verified return to normal Android 8.1 and restoration of factory recovery. Earlier profiles exposed missing public-library configuration and canonical Bionic aliases; the successful profile uses normal flattened APEX paths and clears inherited bootstrap library overrides. This is not a zygote, SystemServer, application, or Google TV Home boot.

The separate full-framework bootstrap compiled successfully. Its normal-Android guard refused execution with exit 64 because it was not PID 1; boot and recovery hashes remained the expected baseline values. The subsequent isolated full-root trial and its limitations are recorded in [ANDROID12_FRAMEWORK_TRIAL.md](ANDROID12_FRAMEWORK_TRIAL.md). This supersedes the earlier preparation-only status; normal boot remains v14.
