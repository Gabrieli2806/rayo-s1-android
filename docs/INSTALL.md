# Installing GabOS on a Canon Rayo S1

This guide covers building GabOS 1.0 from source and installing it on a Canon
Rayo S1 with the guarded tools in `tools/`. It is written for a technical
hobbyist who is comfortable with ADB, PowerShell, WSL and long AOSP builds.

Read [Current limitations](#current-limitations) first. Today the complete
path has only been run on **one projector**, and several steps depend on
artifacts from that unit's private backup. If you own a different Rayo S1,
expect to stop at the points marked **Blocked on other units**.

Plan for:

- **Disk:** about 200 GiB on the WSL ext4 disk, measured on the original
  host: source checkout 93 GiB, TV build output 59 GiB, native and radio outputs
  about 7 GiB each, about 2 GiB per packaged candidate. Use 250 GiB or more for
  comfort. On Windows, you also need about 10 GiB for `local/` and `outputs/`.
- **RAM:** AOSP 12 needs a lot of memory. The original host gave WSL 46 GiB
  and 24 threads. With 16 GiB, expect swapping and failed links.
- **Time:** the source sync takes hours, depending on bandwidth. The first full
  build takes several more hours (build times were not recorded). Packaging
  takes minutes. One guarded device trial takes 15 to 30 minutes. Promotion or
  an update takes about 10 minutes.
- **Projector:** keep it on its charger. Do not unplug USB or power while a
  tool is writing.

## Current limitations

These are the true state of the tools on November 13, 2026. They come from an
audit of unit-specific assumptions in the sources.

1. **The GabOS bootstrap only boots on the original unit.** The PID 1
   bootstrap (`work/android12/device/canon/rayos1_12_lab/early_init_bootstrap.c`,
   lines 27 to 29) has the original projector's userdata ext4 UUID compiled
   in. On any other unit, it stops with `unit filesystem UUID`. After 900
   seconds the watchdog restarts into the rescue slot, so GabOS never starts.
   To make it portable, the UUID must come from a per-unit record written into
   the ramdisk. That change is planned but not made.
2. **The tools check against the original unit's factory images.** About 15
   tools hardcode these MD5 values:
   - factory recovery `b32f971e7dfce13c708d264c6221844d`
   - factory boot `a42af2dbc934f2fc77bff14de619f116`
   - v14 rescue `7a3ac489b05f0f2e9cde16280551511a`
   - factory kernel SHA-256 `e98f7640...`

   They also read `local/device-kit/original/recovery.img` as the reference.
   These values are probably the same for every unit on the same factory
   firmware, but nobody has checked another unit. If your factory recovery
   differs by even one byte, every tool refuses, with messages such as
   "Factory recovery backup differs", "Unexpected boot/recovery baseline" or
   "Original recovery does not match this unit". **Do not edit the
   constants to force a match.** A different factory kernel means the HwBinder
   module and its layout guards do not apply. That would be a separate porting
   project.
3. **There is no direct path from stock 7.1.2 to GabOS.**
   `trial-android12-framework.py` requires boot = v14 and recovery = factory,
   and promotion requires a rehearsal from that state. So you must first
   install the Android 8.1 v14 rescue system as the normal boot. The v14
   images (`rayo81-system.raw.img`, `trial-recovery-v14.img`, the runtime
   overlay and `trial-root-v14.tar.gz`) only exist in the original owner's
   private device kit. The repository has no script that regenerates them
   from another unit's backup. The historical scripts in `work/android81/` are
   records, not a reproducible pipeline.
4. **The packager needs the original unit's device kit.**
   `tools/prepare-android12-framework.py` and its helpers read these files:
   - `local/device-kit/original/{system,recovery}.img`
   - `local/device-kit/runtime/rayo81-live-overlay.tar.gz` (Mali, HWC,
     OMX/VPU, audio and radio blobs and firmware)
   - `local/device-kit/runtime/trial-root-v14.tar.gz` (busybox,
     `rayo_hwbinder.ko`, ueventd rules)
   - `local/device-kit/MANIFEST.json`

   Most of these blobs are the same on every Rayo S1, but the archives are
   assembled from the original unit. They are not distributed. See
   [Licence notes](../README.md#licence-notes).
5. **Serial numbers do not identify the unit.** Every tool uses
   `adb -s 0123456789ABCDEF`, the Rockchip placeholder serial that the v14 and
   GabOS ramdisks report. Two connected projectors cannot be told apart, so
   connect only one. The only real stock serial that `tools/main-boot.py` and
   `tools/rayo.ps1` recognise is the original unit's `3GKL2KGCA400000000`;
   `rayo.ps1` refuses any other stock 7.1.2 unit, even for `-Action Status`.
6. **Host records are not tied to a unit.** A rehearsal recorded in
   `local/android12-framework-runs/` satisfies `promote-android12.py` for
   any unit with the same system image checksum. The Wi-Fi migration secret
   (`local/secrets/gabos-wifi-migration.json`) and the Bluetooth address
   record (`local/secrets/gabos-bt-address.txt`) are single host-wide files.
   On a host that has served one projector, do not run
   `gabos-wifi-profile.py --connect` against another projector: it pushes the
   profile captured from the first one. `gabos-bluetooth-address.py` reads the
   address from the connected projector each time, but it overwrites the
   host record.
7. **The v14 install needs the owner's GitHub release** (removed when the
   repository became public; the v14 path is historical, see GabOS Rescue).
   `main-boot.py --action install-experimental` checks a published
   `previous-main-7.1.2` release in the original repository.
8. **The Wi-Fi MAC address may be shared.** The AP6330 nvram copied into the
   system image contains `macaddr=00:90:4c:c5:12:38`. This value is probably a
   vendor default. It has not been checked whether the driver replaces it with
   a per-unit MAC.

In short: **the steps below reproduce and update the installation on the
original unit.** On another unit, you can do the backup, host setup, source
sync, builds and app builds. Offline packaging is also blocked: the packager
needs the original device kit (item 4) and the two device gate records,
which the tools can only create on a v14 unit (item 3). Installation stays
blocked until items 1 to 4 are resolved.

## 1. Host setup

### Windows

- Windows 10/11 with WSL2.
- Git and GitHub CLI (`gh`). `gh` is only needed for the private device kit.
- Python 3.11 or newer on the Windows `PATH` as `python`. The device tools
  use `hashlib.file_digest`. Install Pillow (`python -m pip install pillow`)
  for the boot animation renderer.
- Android SDK Platform Tools (`adb`) on the `PATH`, and the Rockchip USB
  driver if Windows does not see the projector. The original device kit has
  it as `local/device-kit/tools/rockchip/DriverAssitant.zip`.
- JDK 17 (`javac`, `keytool`) for the GabOS apps.
- Android SDK in the default location `%LOCALAPPDATA%\Android\Sdk`, **and**
  `ANDROID_HOME` set to that same directory. Some tools read only
  `ANDROID_HOME` (`package-android12-native-smoke.py` fails without it), and
  others read only the default location (`build-android12-graphics-probes.ps1`
  and `run-android12-crypto-probe.py --sid`). Install these SDK packages:
  `platforms;android-26`, `build-tools;36.0.0` and `ndk;25.2.9519653`. The
  graphics probes, the radio audit and the gate tools use the Windows NDK
  (`windows-x86_64`).

Check the host:

```powershell
powershell -File tools/rayo.ps1 -Action Doctor
```

### WSL (Ubuntu 24.04)

Keep the source and outputs on the WSL ext4 disk (`~/android/...`), never on
`/mnt/c` and never in `/tmp`. The packager refuses to run on Windows.

1. Install the usual AOSP host packages for Ubuntu 24.04, plus `python3`,
   `e2fsprogs` (`debugfs`, `mke2fs`), `android-sdk-libsparse-utils`, and
   Google's `repo` launcher.
2. Install the legacy ncurses5/tinfo5 ABI that the bundled Android 12
   RenderScript compiler needs. The script uses the signed Ubuntu jammy
   security source without changing your APT sources:

   ```bash
   sudo bash tools/setup-android12-host-compat.sh
   ```

## 2. Identify the projector

Connect only one projector over USB.

```powershell
adb devices
powershell -File tools/rayo.ps1 -Action Status
```

Interpret the result as follows:

- A real serial (for example `3GKL2KGCA400000000` on the original unit) with
  Android `7.1.2` is the factory system.
- Serial `0123456789ABCDEF` with ADB state `recovery` is either the v14
  rescue (Android `8.1.0`) or GabOS (Android `12`). The rescue adbd always
  reports `recovery`, so check the Android version, not the ADB banner.

Also run `adb shell getprop ro.build.version.release` and `adb shell id`.
`id` should show uid 0 on v14 and GabOS. On stock, the tools use `su 0`.
On a unit other than the original, `rayo.ps1 -Action Status` stops with
"This known Rayo S1 is not connected/authorized" while stock 7.1.2 runs
(limitation 5); use the plain `adb` commands instead.

## 3. Back up the projector first

Do this before anything else, even if you only plan to build. Your own backup
is the only legal source of the factory components, and it is your only way
back.

The repository has **no enrollment or backup tool for a fresh unit**. The
original kit was made with Rockchip Loader reads and checked ADB reads. A
read-only manual dump from stock Android 7.1.2 works when `su 0` is available,
as on the original unit. Run it from **Git Bash** (PowerShell 5.1 `>`
redirection corrupts binary data). Put the dump in a new directory under
`local/`:

`MSYS_NO_PATHCONV=1` stops Git Bash from rewriting the `/dev/block/...`
arguments into Windows paths before they reach `adb.exe`.

```bash
export MSYS_NO_PATHCONV=1
mkdir -p local/my-unit/original && cd local/my-unit/original
for p in uboot trust misc resource kernel boot recovery system; do
  adb exec-out su 0 cat /dev/block/platform/1021c000.rksdmmc/by-name/$p > $p.img
  adb shell su 0 md5sum /dev/block/platform/1021c000.rksdmmc/by-name/$p
  md5sum $p.img
done
sha256sum *.img > SHA256SUMS.txt
```

Compare each pair of MD5 values. The expected sizes follow from the sector
counts in [RECOVERY.md](RECOVERY.md): boot, recovery and kernel are each
33,554,432 bytes, and system is 2,147,483,648 bytes. These commands only read. Do not use
`dd of=`, `fastboot`, `format` or `erase` at this stage. Copy the dump to a
second disk.

Then compare your `recovery.img` MD5 with `b32f971e7dfce13c708d264c6221844d`,
your `boot.img` MD5 with `a42af2dbc934f2fc77bff14de619f116`, and your kernel
inside them with the factory kernel SHA-256 recorded in
`tools/verify-rayo-boot.py`. If they match, your unit runs the same factory
firmware as the original. If they do not, stop: see
[Current limitations](#current-limitations), item 2.

## 4. Obtain the factory components

The build expects a **device kit** at `local/device-kit/` with the layout in
`local/device-kit/MANIFEST.json`:

- `original/` holds the factory partition dumps, `build.prop`, `cmdline.txt`
  and `fstab.rk30board`.
- `runtime/` holds the v14 overlay and ramdisk archives.
- `boot/` holds the v14 rescue image.
- `system/` holds the v14 system image.
- `apps/` holds the pinned Aurora Store and SmartTube APKs.
- `kernel/`, `branding/` and `tools/rockchip/` hold the HwBinder module, older
  branding and the Rockchip Windows tools.

- **Original owner:** the kit is no longer published. Since the repository
  became public (November 26, 2026) the device-kit and factory-backup releases
  were removed, because they contain proprietary files; the owner keeps the
  verified archives privately (`local/release-backups/`).
  `tools/download-device-kit.ps1` therefore no longer finds them.
- **Other owners: blocked on other units.** You can fill `original/` from
  your own dump. The `runtime/`, `boot/` and `system/` artifacts cannot be
  regenerated with the current tools (limitation 3). Do not use another
  person's kit. It holds their unit's partitions and proprietary files.

Download the optional third-party APKs yourself from the sources pinned in
`manifests/gabos-optional-apps.json`, and place them in
`local/device-kit/apps/`. The tools check each file's SHA-256 and signer.

## 5. Sync the Android 12 source

Run this in WSL. The pinned snapshot `manifests/android12-full-source-snapshot.xml`
fixes every project revision of `android-12.0.0_r34`.

```bash
mkdir -p ~/android/rayo12 && cd ~/android/rayo12
repo init -u https://android.googlesource.com/platform/manifest -b android-12.0.0_r34 --no-clone-bundle --no-use-superproject
cp /mnt/c/<path-to-repo>/manifests/android12-full-source-snapshot.xml .repo/manifests/rayo-pinned.xml
repo init -m rayo-pinned.xml
repo sync -c --no-clone-bundle --no-tags --fail-fast -j8
```

The build scripts default to `~/android/rayo12`. You can override this with
`RAYO_ANDROID12_SOURCE_DIR`, `RAYO_ANDROID12_OUT_DIR`,
`RAYO_ANDROID12_TV_OUT_DIR`, `RAYO_ANDROID12_RADIOS_OUT_DIR` and `RAYO_JOBS`.
The scripts apply the replayable source patches themselves. Every
`tools/apply-android12-*.py` step is idempotent and refuses divergent sources.

## 6. Build the system (WSL)

Run these from the repository directory as seen from WSL (`/mnt/c/...`). The
scripts copy the device trees from `work/android12/device/canon/` into the
source, so rebuild after you change them.

```bash
# Full TV product (rayos1_tv12-eng): framework, TV Settings, DocumentsUI, HALs,
# the HWC/OMX/audio compatibility layers and the patched libhidlbase. The
# extra modules are not in PRODUCT_PACKAGES, so `files` alone does not build
# them: the OMX facade, the non-FIPS crypto backend and its probe, the
# caller-context probe, and the host tools the packager runs.
bash tools/build-android12-tv.sh files DocumentsUI \
  librayo12_omx_plugin librayo12_crypto_legacy rayo12_crypto_probe rayo12_sid_probe \
  validatekeymaps host_init_verifier mke2fs e2fsdroid

# Radio product (rayos1_tv12_radios-eng) in its own output tree.
bash tools/build-android12-radios.sh

# Native product (rayos1_12_lab): core runtime for the smoke bundle, the PID 1
# bootstrap, the property helper, and the three graphics probes that the
# packager stages from this tree.
bash tools/build-android12-native-lab.sh linker libc libm libdl libdl_android rayo12_bootstrap_probe \
  rayo12_framework_init_bootstrap rayo12_stage1_props \
  rayo12_graphicbuffer_probe rayo12_composer_probe rayo12_surface_probe
```

The TV build ends with "Images disabled". This is expected: no stock image is
generated, and the packager assembles the real images.

Audit the radio build from **Windows PowerShell**, not WSL. The audit uses
the Windows NDK `llvm-readelf.exe` and writes
`outputs/android12-radios/SOURCE-BUILD.json`, which the packager requires
when you pass `--radios-product-out`:

```powershell
python tools/audit-android12-radio-build.py --product-out '\\wsl.localhost\Ubuntu-24.04\home\<you>\android\rayo12-radios-out\target\product\rayos1_tv12_radios'
```

### Gates the packager requires

The packager refuses to build unless two device validation records exist.
Each one proves that a component ran on the real kernel.

- `outputs/android12-crypto-probe/VALIDATED.json`, from the non-FIPS crypto
  backend.
- `outputs/android12-sid-probe/VALIDATED.json`, from the legacy caller-context
  helper. Its hash must match `legacy_sid.rs`.

Both probes run against the **TV** product tree. The packager compares the
validated `librayo12_crypto_legacy.so` hash with the copy in the TV product,
and the native lab tree does not contain the crypto library or its probe.

The gate tools only run in one device state: v14 running normally, with
boot = v14 (`7a3ac489...`) and recovery = factory (`b32f971e...`). That is
the state after step 9a. On a first install, do step 9a before this
step. On an installed GabOS unit (boot = GabOS, recovery = v14), the tools
refuse with "Boot checkpoint is not the recorded v14 image". To re-run the
gates there, roll back first (step 11). These steps run bounded child
processes and write no partitions.

```powershell
python tools/package-android12-native-smoke.py --out-dir '\\wsl.localhost\Ubuntu-24.04\home\<you>\android\rayo12-out'
python tools/run-android12-native-smoke.py
python tools/run-android12-crypto-probe.py --product-out '\\wsl.localhost\Ubuntu-24.04\home\<you>\android\rayo12-tv-out\target\product\rayos1_tv12' --execute
python tools/run-android12-crypto-probe.py --product-out '\\wsl.localhost\Ubuntu-24.04\home\<you>\android\rayo12-tv-out\target\product\rayos1_tv12' --sid --execute
```

`run-android12-native-smoke.py` without `--execute` only runs the read-only
preflight. Run each crypto probe without `--execute` first to see the plan.
You only need to repeat these gates when the crypto library, Keystore helper
or `legacy_sid.rs` changes.

### Graphics probes (Windows)

```powershell
./tools/build-android12-graphics-probes.ps1
```

This builds `outputs/android12-probes/rayo12-gpu-namespace-probe` and
`librayo12_diaglog.so`. The packager stages them both. If your NDK is not
under `%LOCALAPPDATA%\Android\Sdk`, pass `-Ndk <path to ndk\25.2.9519653>`.

## 7. Build the GabOS apps (Windows PowerShell)

The first run of `build-lightweight-tv.ps1` creates a development signing key
in `local/secrets/launcher-android12-development.p12` with a random password
file. All GabOS apps use this key. Keep it private and back it up: later
updates of the installed apps must use the same key. On a new host for a
projector that already runs GabOS, copy the existing `local/secrets/` there
privately **before** the first build; otherwise a new key is created and the
rebuilt apps no longer match the installed signer.

The boot animation renderer reads the variable Roboto font from
`local/fonts/Roboto-Regular.ttf`, which is not in Git. Copy it from the
synced source (step 5), for example from WSL in the repository directory:

```bash
mkdir -p local/fonts
cp ~/android/rayo12/external/roboto-fonts/Roboto-Regular.ttf ~/android/rayo12/external/roboto-fonts/NOTICE local/fonts/
```

```powershell
./tools/build-lightweight-tv.ps1          # GabOS Home, Settings, setup, keyboard -> outputs/Rayo-Dark-TV.apk
./tools/build-gabos-app.ps1 -App media    # outputs/GabOS-Media.apk
./tools/build-gabos-diagnostics.ps1       # outputs/GabOS-Diagnostics.apk
./tools/build-gabos-share.ps1             # outputs/GabOS-Share.apk and the phone app GabOS-Share-Sender.apk
./tools/build-validation-app.ps1          # optional: outputs/Rayo-Hardware-Check.apk
python tools/make-gabos-bootanimation.py  # outputs/gabos-bootanimation.zip
```

Each app has a `.build.json` record (package and SHA-256), and the packager
checks it. `GabOS-Share-Sender.apk` is for phones. Install it on a phone with
`adb install` or by copying the file to the phone.

## 8. Package a candidate (WSL)

```bash
python3 tools/prepare-android12-framework.py \
  --source ~/android/rayo12 \
  --product-out ~/android/rayo12-tv-out/target/product/rayos1_tv12 \
  --native-product-out ~/android/rayo12-out/target/product/rayos1_12_lab \
  --radios-product-out ~/android/rayo12-radios-out/target/product/rayos1_tv12_radios \
  --local-launcher --gabos-apps --software-media --legacy-media \
  --installed-boot --watchdog-seconds 900 --preopt-filter speed \
  --data-mib 704
```

The options do the following:

- `--installed-boot` emits `rayo12-framework-boot.img` for the boot partition.
  This image starts the framework without a host and has a boot-success
  watchdog. It is required for promotion and updates. A recovery-slot image
  for the rehearsal is always emitted as well.
- `--legacy-media` (with `--software-media`) enables the factory hardware
  decoders.
- `--data-mib` sets the size of the private data image (256 to 3072 MiB;
  the default is 256). Choose it from the free userdata space. The tools
  always keep a 768 MiB reserve on userdata. **For an update of an existing
  install,** add `--reuse-data-manifest <previous candidate>/MANIFEST.json`
  and keep the **same** `--data-mib` as the installed image (704 for 1.0).
  The manifest only supplies the data UUID; the size still comes from
  `--data-mib`, and `update-android12.py` refuses a candidate whose data
  size or UUID differs from the device.

The packager never touches a device. It prints
`Offline framework candidate: /home/<you>/android/rayo12-framework-candidates/candidate-XXXX`.
That directory also holds the large `system/`, `ramdisk/` and `graphics/`
work trees. Copy only the manifest and the four images to a new directory in
the Windows repository:

```bash
c=~/android/rayo12-framework-candidates/candidate-XXXX
d=/mnt/c/<path-to-repo>/outputs/android12-framework/gabos-my-build
mkdir -p "$d"
cp "$c"/MANIFEST.json "$c"/rayo12-framework-system.raw.img "$c"/rayo12-framework-data.ext4 \
   "$c"/rayo12-framework-recovery.img "$c"/rayo12-framework-boot.img "$d"/
```

To inspect a boot image without writing anything, run:

```powershell
python tools/verify-rayo-boot.py --require-static-init outputs/android12-framework/gabos-my-build/rayo12-framework-boot.img
```

## 9. Install

The Python write tools for GabOS work the same way. Without `--execute`,
they print a plan and check the device. With `--execute`, they write, read
back, and record the run under `local/`. (`main-boot.py` uses
`--accept-experimental` for installation and keeps no run record;
`rayo.ps1` has no plan mode.) Run the plan first, read it, then run the tool again
with `--execute`. Run only one device tool at a time.

### 9a. Install the v14 rescue system as normal boot (original unit only)

You start from stock Android 7.1.2.

```powershell
powershell -File tools/rayo.ps1 -Action PrepareRuntime   # push the v14 system file and overlay to /data; no partition writes
powershell -File tools/rayo.ps1 -Action StartTrial       # write v14 to recovery (verified), boot it once
```

The rescue system is now running. Back up the previous main system and check
the plan:

```powershell
python tools/backup-previous-main.py
python tools/main-boot.py --action plan
python tools/main-boot.py --action install-experimental --accept-experimental
```

`backup-previous-main.py` stores the previous partitions in
`local/previous-main/<time>/`. `install-experimental` also checks, with
`gh`, that the `previous-main-7.1.2` release in the original repository is
published (limitation 7). It then writes boot = v14 and recovery = factory,
reads both back, and requests a normal restart by itself. Check that
`tools/rayo.ps1 -Action Status` then reports Android 8.1.0 with boot
completed. The factory 7.1.2 system partition is still on the device. See
[PRIMARY_BOOT_PLAN.md](PRIMARY_BOOT_PLAN.md).

### 9b. Rehearse GabOS in the recovery slot

From normal v14:

```powershell
python tools/trial-android12-framework.py --candidate outputs/android12-framework/gabos-my-build
python tools/trial-android12-framework.py --candidate outputs/android12-framework/gabos-my-build --execute
```

The controller works through these steps:

1. Reads a fresh factory recovery backup.
2. Pushes the system and data images to userdata, keeping the reserve.
3. Writes the trial image to recovery only, and reads it back.
4. Boots GabOS once and waits for a completed boot.
5. Returns to v14.
6. Restores the factory recovery, reads it back, and writes
   `local/android12-framework-runs/<run>/RESULT.json`.

If earlier GabOS files are already on the device, add
`--reuse-known-artifacts`. The tool only replaces a system file that matches
a candidate in `outputs/android12-framework/`, and it keeps the device's
private data. That data must have the candidate's UUID from an earlier
completed rehearsal, so package the candidate with `--reuse-data-manifest`
(step 8). A different data size also needs `--replace-data`.

If a trial is interrupted, the projector returns to v14 on its own (the
watchdog). Restore the factory recovery with:

```powershell
python tools/restore-framework-recovery.py --run local/android12-framework-runs/<run>
python tools/restore-framework-recovery.py --run local/android12-framework-runs/<run> --execute
```

### 9c. Promote GabOS to normal boot

Promotion requires a completed and restored rehearsal of the **exact same
system image**, and the system and data files on the device must still be the
rehearsed ones.

```powershell
python tools/promote-android12.py --candidate outputs/android12-framework/gabos-my-build
python tools/promote-android12.py --candidate outputs/android12-framework/gabos-my-build --execute
```

Promotion copies the factory recovery, the v14 image and the new GabOS boot
image to `/realdata/gabos-rescue/`. It then writes recovery = v14 rescue and
boot = GabOS, reads both back, restarts, and waits for a completed GabOS
boot. No other partition is written. If the projector falls back to the v14
rescue instead, the tool writes boot = v14 and recovery = factory again and
exits with an error.

### 9d. After the first boot

- Complete the setup wizard on the projector: language, Wi-Fi and look.
- Install SmartTube (and re-check Aurora) from the pinned APKs:

  ```powershell
  python tools/install-gabos-apps.py
  python tools/install-gabos-apps.py --execute
  ```

- Optional, original unit only: `tools/gabos-wifi-profile.py --capture`
  (on v14) and `--connect` (on GabOS) reuse the Wi-Fi profile.
  `tools/gabos-bluetooth-address.py` copies the v14 Bluetooth address into
  GabOS data. Both read and write single host-wide secrets (limitation 6).

## 10. Updating GabOS

The system image file cannot be replaced while GabOS runs from it, so updates
go through the rescue system. Package a new candidate with
`--installed-boot --reuse-data-manifest <installed candidate>/MANIFEST.json`
and the installed `--data-mib` (step 8), copy it to
`outputs/android12-framework/`, then run:

```powershell
python tools/update-android12.py --candidate outputs/android12-framework/gabos-my-update
python tools/update-android12.py --candidate outputs/android12-framework/gabos-my-update --execute
```

Run from GabOS, the plan only confirms that GabOS is running; the layout and
space checks happen after the restart. For a full plan, start the v14 rescue
first and run the plan from there.

The tool performs these steps:

1. Restarts into the v14 rescue and checks that the layout is boot = known
   GabOS and recovery = v14. A GabOS boot image is "known" when it belongs to
   a candidate under `outputs/android12-framework/` or appears in a record
   under `local/android12-promotion/`.
2. Checks that the device data image has the candidate's UUID.
3. Replaces and verifies the system file, keeping the 768 MiB reserve.
4. Writes the new boot image, which carries the new system checksum, and
   reads it back.
5. Restarts and waits for a completed GabOS boot.

Your private data, apps and settings are kept. An update does not require a
rehearsal: if the new image does not boot, the watchdog returns to the
rescue. From there, run `update-android12.py` again with the previous
candidate, or roll back.

## 11. Recovering

| Situation | What to do |
|---|---|
| GabOS does not finish booting | Wait. After 900 s, the watchdog restarts into the v14 rescue (root ADB, serial `0123456789ABCDEF`) |
| Go back to a previous GabOS build | From the rescue or GabOS: `python tools/update-android12.py --candidate <previous candidate> --execute` |
| Remove GabOS from boot | From GabOS or the rescue: `python tools/promote-android12.py --action rollback --execute` (boot = v14, recovery = factory), then reboot normally. The GabOS files stay on userdata |
| Return to factory Android 7.1.2 | First roll back as above and reboot into normal v14. Then: `python tools/main-boot.py --action restore-previous-boot --execute`, and reboot normally. `main-boot.py` refuses while boot holds GabOS. Prepared, not yet tested after promotion. See [RECOVERY.md](RECOVERY.md) |
| Factory recovery needed in the recovery slot, with boot = v14 | `powershell -File tools/rayo.ps1 -Action RestoreOriginalRecovery`, or `restore-framework-recovery.py` after an interrupted trial. Do not use it in the installed layout: it would overwrite the v14 rescue while boot holds GabOS. Use the rollback instead |
| The projector does not boot at all | Not covered by a tested procedure. Rockchip Loader reads and writes were tested (USB `2207:310c`), but MaskROM and emergency entry were not. Keep your backup from step 3 |

`adb reboot` from the rescue's static adbd may hang. That alone does not mean
the device has hung. Use `adb shell setprop sys.powerctl reboot`.

## Troubleshooting

- **"Use native Linux/WSL ext4"**: run the packager inside WSL, from the
  repository, with the outputs on ext4.
- **"Rebuild and verify GabOS ..." / "Rebuild and verify the lightweight
  launcher first"**: rebuild the app. Its `.build.json` checksum no longer
  matches.
- **"The board crypto backend has not passed its isolated device test"**:
  create the gate records in step 6.
- **"Insufficient physical space with the reserve"**: free space on userdata.
  Do not lower the reserve.
- **"Unexpected boot/recovery baseline" / "Not the installed layout"**: the
  partitions are not in the state the tool expects. Run `tools/rayo.ps1
  -Action Status` and the tool's plan mode. Do not write partitions by hand.

## 11. Wireless updates (GabOS 1.4 and later)

A projector that runs GabOS with GabOS Rescue updates itself from this
repository's latest GitHub release: **Settings > Updates > Check**, then
**Download and install**.

- `tools/make-gabos-ota.py` (WSL) turns an installed-boot candidate into the
  public release files: `gabos-ota.json`, its signature `gabos-ota.json.sig`
  (ECDSA P-256 key in `local/secrets/gabos-ota-signing.pem`, public part in
  `manifests/gabos-ota-signing.pub.pem`), `gabos-system.img.gz` with the data of
  every proprietary file zeroed, and `gabos-boot.img` with the factory kernel
  and second stage zeroed. No factory file is published.
- `tools/check-gabos-ota.py` rebuilds the update on the host from those files
  and the currently installed candidate, exactly as the projector will, and
  must report both images rebuilt before the release is published.
- Publish with `gh release create gabos-<version> <the four files> --latest`.
- On the projector, GabOS Settings verifies the signature, downloads and hashes
  both files, and writes a plan; `gabos-update-stage` (root) moves them to
  `/realdata/gabos-update` and restarts into GabOS Rescue;
  `gabos-apply-update.sh` copies the factory files back from the installed
  system and the kernel from the current boot partition, checks every file and
  both final images, installs them and keeps the previous version until GabOS
  completes a boot. If the new version does not finish booting, the boot
  watchdog returns to the rescue, which restores the previous one.
- An update installs only when the projector's factory files match the ones the
  release expects; otherwise it stops without changing anything, and the update
  has to be applied from a computer (`tools/update-android12.py`).
