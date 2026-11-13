# GabOS architecture

GabOS 1.0 runs AOSP `android-12.0.0_r34` (API 31, ARMv7 Cortex-A7, 32-bit
Binder) on the **unchanged factory kernel** (Linux 3.10.104) of the Canon Rayo
S1. The Android 12 userspace is built from source. The factory Android 7.1.2
hardware libraries (Mali, HWC, gralloc, audio, OMX/VPU and the radio vendor
library) are loaded behind compatibility layers instead of being replaced.

The design has four constraints:

- Never repartition or format.
- Keep the factory kernel byte-identical.
- Keep a full rescue system one restart away.
- Verify every image by checksum before it is used.

## Storage layout

The partition table is the factory Rockchip `parameter` layout; see
[RECOVERY.md](RECOVERY.md). GabOS uses the partitions like this:

| Partition | Content | Written by GabOS tools |
|---|---|---|
| `uboot`, `trust`, `resource`, `kernel`, `misc` | Factory | Never. `misc` is only read, and cleared of a recovery command after `reboot,recovery` |
| `boot` (32 MiB) | GabOS boot image: factory kernel and second stage, GabOS ramdisk | `promote-android12.py` (promotion and rollback), `update-android12.py` |
| `recovery` (32 MiB) | Android 8.1 **v14 rescue** image: factory kernel, v14 ramdisk | `promote-android12.py` (promotion and rollback), and trials temporarily |
| `system` (2 GiB) | Factory Android 7.1.2, preserved | Never |
| `userdata` | ext4; holds the image files below | Files only |

Files on userdata (`/realdata` in GabOS and in the rescue):

| Path | Purpose |
|---|---|
| `local/tmp/rayo12-framework-system.raw.img` | GabOS system: read-only raw ext4, 800 to 2047 MiB in 16 MiB steps (1.0: 928 MiB) |
| `rayo12-framework-data/userdata.ext4` | GabOS private `/data`: ext4 with a fixed UUID, 256 to 3072 MiB (1.0: 704 MiB) |
| `gabos-rescue/` | Verified copies of the factory recovery and the v14 rescue image, used by rollback, plus the staged `gabos-boot.img` of the last promotion or update |
| `local/tmp/rayo81-system.raw.img`, `rayo81-trial/` | v14 rescue system image, overlay and data |
| everything else | The factory 7.1.2 user data, which GabOS does not bind or modify |

The tools keep at least 768 MiB of userdata free at all times.

## Boot image format

Boot images are packed by `tools/pack-recovery.py` and checked by
`tools/verify-rayo-boot.py`:

- Android boot image v0 with a 16 KiB page.
- The factory kernel and the second-stage RSCE (resource) block are copied
  byte for byte from the factory recovery image. The kernel SHA-256 must be
  `e98f7640...`.
- The ramdisk is gzip-compressed, and padded to a four-byte length before its
  size is declared and hashed.
- Rockchip's extended SHA-1 also covers `header[32:576]`. The bootloader
  rejects images without these two details.
- The command line is
  `buildvariant=eng androidboot.selinux=permissive androidboot.init_fatal_reboot_target=normal`.
- Each image is exactly 33,554,432 bytes.

## Boot chain

```
Rockchip loader (factory uboot/trust)
  └─ boot partition: factory kernel 3.10.104 + GabOS ramdisk
       └─ /init = rayo12_framework_init_bootstrap   (static C, PID 1)
            ├─ forks the watchdog child
            ├─ verifies the kernel, userdata, system image and data image
            ├─ mounts /system (loop7, ro) and /data (loop6)
            └─ exec /system/bin/init selinux_setup  (Android 12 init)
                 └─ init.rayo12.framework.rc, then the normal Android 12 boot
```

### PID 1 bootstrap

Source: `work/android12/device/canon/rayos1_12_lab/early_init_bootstrap.c`
and `framework_data.h`. It is compiled as `framework_bootstrap.c` with
`RAYO_FRAMEWORK_TRIAL`. The build target `rayo12_framework_init_bootstrap` is
added by `tools/apply-android12-legacy-build-fixes.py`. The packager installs
it as the ramdisk's `/init`. The bootstrap performs these steps in order:

1. It refuses to do anything unless it is PID 1 and root. Run from ADB, it
   exits with code 64 without mounting.
2. It forks the **watchdog** child (described below) before any check that
   can fail. On error, `fatal()` logs to `/dev/kmsg` and sleeps forever,
   never panicking PID 1. The watchdog is what recovers.
3. It requires kernel release `3.10.104`.
4. It reads the records the packager writes into the ramdisk:
   - `rayo12-stage1.cfg`: the system image SHA-256 and size.
   - `rayo12-framework-data.cfg`: the data image UUID and size.
   - `rayo12-watchdog.cfg`: the timer length, 600 to 14400 s. If the record
     is missing or invalid, the 600 s default applies.
   - `rayo12-boot-mode.cfg`: the fallback target.
5. It mounts `/dev`, `/proc`, `/sys`, selinuxfs and the tmpfs directories
   that Android 12 first-stage init normally creates.
6. It finds userdata without trusting partition numbers. Recovery and normal
   boot number the partitions differently. It scans `/sys/class/block/mmcblk0p*`
   for the partition whose Linux start sector is `0x5f4000` and whose size is
   4 to 8 GiB. More than one match is fatal.
7. It reads that partition's ext4 superblock. It requires the ext4 magic and
   **the original unit's filesystem UUID** (a compiled constant; see
   [INSTALL.md](INSTALL.md#current-limitations)). It then mounts userdata
   read-write at `/realdata`.
8. It reads the whole system image file and compares its SHA-256 with the
   ramdisk record. It then attaches the file read-only to an unused `loop7`
   and mounts it at `/system` (ext4, `ro`, `noload`). A changed or corrupted
   system file therefore never boots.
9. It checks the private data file before attaching it to `loop6` and
   mounting it at `/data`. The checks cover the owning directory, owner, mode,
   link count, exact size, ext4 UUID and feature mask. The original Android
   data tree is never bound. `/metadata` and `/cache` are tmpfs.
10. It clears `LD_*`, sets `ANDROID_ROOT`/`ANDROID_DATA`, and execs
    `/system/bin/init selinux_setup`. Android 12 init continues from its
    second stage. The bootstrap has already done the first-stage mounts.

### Watchdog

The child process makes itself unkillable by the low-memory killer
(`oom_score_adj -1000`). What happens next depends on
`rayo12-boot-mode.cfg`:

- **Installed boot** (`boot_success_fallback=recovery`): every 5 s, the
  watchdog checks for `/dev/.rayo12-boot-ok`. The packager adds an init
  trigger that writes this file on `sys.boot_completed=1`. If the file does
  not appear within the window (900 s for 1.0), the watchdog syncs and
  restarts with `RESTART2 "recovery"`. The bootloader then starts the v14
  rescue.
- **Rehearsal image** (`boot_success_fallback=normal`): the same behaviour,
  but it restarts into normal boot (v14 during a rehearsal).

### Android 12 init additions

`work/android12/framework/init.rayo12.framework.rc`:

- On `early-init`, it loads `/sbin/rayo_hwbinder.ko armed=1`. This is the
  HwBinder/vndbinder module built for the factory kernel; the 3.10 kernel has
  only legacy Binder. The module checks the kernel banner and structure
  layouts (`rayo_layout_guard.h`) and refuses to load on a mismatch. Never
  force-load it. Source: `work/android81/hwbinder-module/`.
- It configures USB ADB through the legacy `android_usb` gadget with
  functionfs, and starts a root adbd with the `recovery` banner and serial
  `0123456789ABCDEF`. Root ADB stays available even if the framework fails.
- It keeps a rotating logcat in `/data/rayo12-logcat.txt`.
- On `sys.boot_completed=1`, it runs `gabos-boot`
  (`work/android12/framework/gabos-boot.sh`). This script sets density 192,
  marks setup complete for the framework (the GabOS wizard lives in Home),
  grants the launcher its settings permissions, selects the GabOS keyboard,
  and starts the Share receiver.

The board properties are installed in the canonical `/system/etc/build.prop`.
They include `ro.gabos.name=GabOS`, `ro.gabos.version=1.0`,
`ro.hardware.hwcomposer=rayo12`, `debug.sf.no_hw_vsync=0`, and the opt-in
`ro.rayo.*` compatibility switches described below. The packager checks the
effective values after it writes them.

## Legacy vendor namespace

Android 12 C++ libraries cannot share a process namespace with the factory
Android 7.1.2 libraries, because their libc++ and libutils ABIs differ. GabOS
therefore uses two library sets:

- **System namespace:** everything built from Android 12 source, including the
  new allocator/mapper, composer service, Health, Keymaster and audio HAL
  wrappers.
- **Legacy `sphal` namespace:** the factory closure in `/vendor/lib/legacy`,
  with the search path `/vendor/lib/legacy:/vendor/lib`. It is enabled by
  `ro.rayo.graphics.legacy_sphal` in a source-built `linkerconfig`.

`tools/android12_graphics_root.py` stages the closure. It resolves every
`NEEDED` entry and copies only ARM32 ELF files from the factory system image
(read with `debugfs`, never mounted) or the v14 overlay. Rules that must hold:

- `/system/lib/egl/libGLES_mali.so` and its vendor path resolve to **one**
  inode. Two loaded copies of the Mali driver break.
- The Rockchip `libion.so` is kept. The generic AOSP libion lacks extensions
  that the factory code uses. The allocator uses ION client targets that match
  the verified physical scanout.
- Only C interfaces cross the namespace boundary. No legacy C++ object is
  passed to Android 12 code.

## Graphics

```
SurfaceFlinger (Android 12, HWC2)
  └─ android.hardware.graphics.composer@2.1-service
       └─ libhwc2on1adapter            (patched: solid-colour fallback, client-blend fallback)
            └─ hwcomposer.rayo12        (rk_hwc_abi.h adapter, in the system namespace)
                 └─ dlopen in the legacy namespace → factory hwcomposer.rk30board.so (HWC1)
                      └─ factory gralloc / Mali-400 / ION / framebuffer
```

- The factory HWC1 has a private ABI: four extra callbacks, a different list
  layout and 208-byte layers. `hwc_adapter12.cpp` reuses the Android 8.1
  adapter (`work/android81/device/canon/rayos1_fluidity/hwc_adapter.cpp` and
  `rk_hwc_abi.h`, copied in by the build scripts) with its static layout
  checks. It never passes AOSP layouts to the factory module directly.
- Hardware VSYNC stays enabled (`debug.sf.no_hw_vsync=0`).
- The native TV Settings panel is translucent. Frames with fractional alpha
  or dim layers request full client (GPU) composition
  (`debug.rayo.graphics.client_blend`). HWUI's buffer-age and partial updates
  are off (`debug.hwui.use_buffer_age=false`,
  `debug.hwui.use_partial_updates=false`); this removed the gray flicker.
- The packager refuses a TV build whose `linkerconfig` or
  `libhwc2on1adapter.so` lacks these switches.

## Media (OMX facade)

```
MediaCodec / Codec2 + OMX (Android 12)
  └─ libstagefright_omx  (guarded plugin selection, apply-android12-omx-compat.py)
       └─ librayo12_omx_plugin  (OMXPluginBase facade, omx_plugin_bridge.cpp)
            └─ legacy namespace: factory libOMX_Core RKOMX_* C API → libomxvpu_dec → librk_vpuapi → VPU / HEVC
```

- The facade queries the factory component list and roles through the
  exported `RKOMX_*` C functions. Only the C OMX structures cross the boundary,
  with the platform's 1.0 parameter headers.
- **Release fix:** the factory `Rkvpu_Dec_Terminate` clears a 128-byte scratch
  buffer at decoder-private offset 616, which is NULL on Android 12. Before
  this fix, the codec service crashed on every codec release. The facade wraps
  `SendCommand` to provide that buffer before state changes, and frees it in
  `ComponentDeInit`.
- `libomxvpu_dec.so` loads `librk_vpuapi.so` at runtime, so it is staged
  explicitly. `librk_vpuapi.so` loads the HEVC decoder by an absolute
  `/system/lib` path that the namespace forbids. GabOS stages the factory
  `librk_hevcdec.so` under a short name in `/vendor/lib/legacy` and patches
  the path string in its own copy of `librk_vpuapi.so`. The factory IEP path
  cannot be redirected; it was already optional for AVC.
- `media_codecs.xml` lists AVC, HEVC, VP8, MPEG-4, MPEG-2 and H.263. The
  limits and quirks are taken from the factory 7.1.2 declaration, and these
  decoders are ranked ahead of the software decoders. VP9 and all encoders are
  software only.

## Audio

The source-built HIDL audio 7.0 HAL bridges to the factory
`audio.primary.rk30board.so`. Three measures reduce the amplifier pop:

- A 100 ms PCM16 soft start after stream creation or standby
  (`apply-android12-audio-soft-start.py`). It only attenuates gain and never
  adds or removes frames.
- A 30-minute output standby delay.
- An always-on anti-pop stream kept by GabOS Home (`work/rayo-tv/SystemService.java`).

`work/android12/framework/audio_policy_configuration.xml` describes the
speaker output.

## Radios

The radio product (`rayos1_tv12_radios`) builds the Wi-Fi HAL,
`wpa_supplicant` and the Bluetooth HAL from source.
`tools/android12_radio_root.py` stages them with the following factory parts:

- The AP6330 firmware (`fw_RK903_ag.bin`, nvram) and `bcm40183b2.hcd`,
  unmodified.
- The factory `libbt-vendor.so`, with the import patch from
  `tools/patch-bt-vendor.py`.
- A timer guard (`librayo12_bt_timer_compat`), because the factory library
  can arm a null timer.

The services get limited file capabilities (NET_ADMIN/NET_RAW), which the
packager reads back from the image. The Bluetooth address is read from
`/data/misc/bluetooth/bdaddr`.

## Other compatibility layers

Each is a replayable `tools/apply-android12-*.py` patch, enabled by a board
property where possible:

- **HIDL:** removes libhidl's fixed one-second sleep per `getService()` on
  non-VINTF products (`ro.rayo.hidl.no_legacy_wait`). The packager declares
  the registered services and refuses unpatched `libhidlbase` copies. This
  removed 7-second first-frame stalls.
- **Process groups:** cgroup v1 `memory/apps` membership, because the kernel
  has no cgroup2.
- **Network:** legacy BPF/netd patches (`patches/android12/`) for a kernel
  without the eBPF features that Android 12 expects.
- **Keystore and crypto:** software Keymaster/Gatekeeper with a non-FIPS
  BoringSSL backend. A caller-context helper (`legacy_sid.rs`) handles the
  kernel's older SELinux interface. Both are gated by on-device validation
  records before packaging.
- **ADB:** an opt-in FunctionFS transport for the old kernel's USB gadget.
- **TV Settings:** a native TV Settings build that preserves its transparent
  panel.
- **Dexpreopt:** apps are compiled ahead of time on the host (`speed` filter)
  and checked for class-loader context.

## Apps in the system image

| App | Package | Path |
|---|---|---|
| GabOS Home, Settings, setup, keyboard, theme provider | `local.rayo.launcher` | `/system/app/RayoDark` |
| GabOS Media / Diagnostics / Share | `local.gabos.media` / `.diagnostics` / `.share` | `/system/app/GabOS*` |
| Hardware Check | `local.rayo.validation` | `/system/app/GabOSHardwareCheck` |
| Aurora Store 4.8.4 (original signature) | `com.aurora.store` | `/system/app/AuroraStore` |
| Android Files (DocumentsUI) | AOSP | `/system/priv-app/DocumentsUI` |
| TV Settings | AOSP | `/system/system_ext/priv-app/TvSettings` |

`LeanbackIME` is removed when the launcher carries the GabOS keyboard. The
GabOS apps share `work/gabos-ui/GabosUi.java` and read the appearance from
Home's theme provider. GabOS Share runs a local HTTP receiver advertised with
NSD; see `work/gabos-share/PROTOCOL.md`.

## The rescue system (v14)

The recovery slot holds `trial-recovery-v14.img`: the factory kernel and
second stage, plus the Android 8.1 v14 ramdisk (`work/android81/trial-root-v01`
and the kit's `trial-root-v14.tar.gz`). It works like this:

- It mounts userdata at `/realdata` and attaches
  `/realdata/local/tmp/rayo81-system.raw.img` read-only on `loop7`. It
  bind-mounts `/realdata/rayo81-trial/data` as `/data`, with the runtime
  overlay in `rayo-live`.
- It runs a complete Android 8.1 with projection, Wi-Fi and root ADB (static
  adbd, `recovery` banner, serial `0123456789ABCDEF`, `/sbin/busybox`).
- It never maps the GabOS images. `update-android12.py` checks this before it
  replaces the GabOS system file.

The rescue system was built from AOSP `android-8.1.0_r81`; see
[BUILD.md](BUILD.md). It stays in place until a separate plan retires the
older systems.

## Install, update and rollback flow

```
stock 7.1.2 ──rayo.ps1 PrepareRuntime/StartTrial──▶ v14 in recovery (one-time)
            ──main-boot.py install-experimental──▶ boot=v14, recovery=factory
            ──trial-android12-framework.py──▶ GabOS rehearsed in recovery, then factory restored
            ──promote-android12.py──▶ boot=GabOS, recovery=v14   (installed layout)

update:   GabOS ─reboot,recovery─▶ v14 rescue: replace system file, write boot, read back ─▶ GabOS
rollback: promote-android12.py --action rollback ─▶ boot=v14, recovery=factory
7.1.2:    main-boot.py --action restore-previous-boot (from v14) ─▶ boot=factory
```

All of these tools follow the same rules:

- Each tool checks the partition MD5s it expects before it writes, and
  refuses on any other state.
- It checks that the misc bootloader message is empty, or that it holds only
  the recovery command the tool itself issued.
- It checks the image files by size and checksum, and the private data by its
  UUID.
- It always keeps the userdata reserve.
- It writes with `dd` from a checksum-verified staging file, then reads the
  partition back.
- It records a JSON result under `local/android12-framework-runs/` or
  `local/android12-promotion/`.
- Without `--execute`, it only prints its plan.

These rules describe the GabOS tools (`trial-`, `promote-`, `update-android12.py`
and `restore-framework-recovery.py`). The older `main-boot.py` and `rayo.ps1`
also check checksums and read back, but keep no JSON record.

The GabOS boot image contains the system image's SHA-256. An update therefore
replaces the system file and the boot image together, and a mismatched pair
does not boot: the watchdog returns to the rescue.

## Host build pipeline

| Stage | Where | Entry point | Output |
|---|---|---|---|
| Source | WSL | `repo` with `manifests/android12-full-source-snapshot.xml` | `~/android/rayo12` |
| TV product | WSL | `tools/build-android12-tv.sh` | `~/android/rayo12-tv-out` (framework, OMX facade, crypto backend and probes) |
| Radio product | WSL, then Windows | `tools/build-android12-radios.sh`; `audit-android12-radio-build.py` runs on Windows (NDK readelf) | `~/android/rayo12-radios-out`, `outputs/android12-radios/SOURCE-BUILD.json` |
| Native product | WSL | `tools/build-android12-native-lab.sh` | `~/android/rayo12-out` (core runtime, bootstrap, props helper, graphics probes) |
| Device gates | Windows + v14 (boot = v14, recovery = factory) | `package-android12-native-smoke.py`, then `run-android12-crypto-probe.py` on the TV product (with and without `--sid`) | `outputs/android12-{crypto,sid}-probe/VALIDATED.json` |
| Probes | Windows NDK | `build-android12-graphics-probes.ps1` | `outputs/android12-probes/` |
| Apps | Windows JDK/SDK | `build-lightweight-tv.ps1`, `build-gabos-*.ps1`, `make-gabos-bootanimation.py` | `outputs/*.apk`, `.build.json`, boot animation |
| Package | WSL | `prepare-android12-framework.py` | `~/android/rayo12-framework-candidates/candidate-*` |
| Install | Windows + device | `trial-`, `promote-`, `update-android12.py` | partitions and records |

Factory inputs come only from `local/device-kit/`, the owner's own backup.
`download-device-kit.ps1` verifies the kit archive's SHA-256 before it
extracts it. The packager itself checks the v14 overlay archive against
`local/device-kit/MANIFEST.json` (with `--legacy-media`) and checks the
factory kernel against the factory recovery image; it reads the other kit
files without a separate manifest check.

## Unit-specific values

These values tie the current tools to the original projector. Fixing them is a
prerequisite for installing on another unit:

- The userdata ext4 UUID compiled into the bootstrap.
- The factory recovery and boot MD5s, the v14 rescue MD5 and the factory
  kernel SHA-256, repeated in about 15 tools.
- The stock ADB serial in `main-boot.py` and `rayo.ps1`.
- The host-wide Bluetooth address and Wi-Fi secrets.

Model-wide values do not need a change: the by-name path
`/dev/block/platform/1021c000.rksdmmc/by-name/`, the userdata start
`0x5f4000`, the 32 MiB boot and recovery size, the projector I2C sysfs paths,
and the kernel release. See [INSTALL.md](INSTALL.md#current-limitations) for
the full list and the planned per-unit profile.
