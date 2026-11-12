# GabOS continuation handoff — November 11, 2026

## Read first

Canonical repository: `C:\src\personal\custom-android\rayo-s1-android`.
The chat working directory may be unrelated; set the repository working directory
explicitly for shell tools. Conversation is Spanish; repository documentation,
comments and implementation notes are English. Read `AGENTS.md`, this document,
`ANDROID12_NEXT_STEPS.md`, `RECOVERY.md` and `GABOS_APPS.md`.

Normal boot is still Android 8.1 v14. Android 12/API31 is GabOS 0.1-dev in guarded
recovery-slot trials, on the unchanged factory 3.10.104 kernel. No old system has
been erased. The owner authorized future promotion/removal of older active
installations conditional on demonstrated stability. Do not ask for duplicate
permission, but do not turn unresolved bugs into a stable-release claim.

## GabOS Android 12 is the normal boot (November 12, 2026)

`gabos-lite-03` (Google-free, Home 0.6, GabOS Keyboard, restyled Media 0.2 and
Diagnostics 0.4, HIDL delay removal, 30-minute audio standby) was rehearsed in
run `20261112-135918-42a50670` (boot completed, normal return, factory recovery
restored), then promoted by `tools/promote-android12.py`
(`local/android12-promotion/`):

| Partition | Content | MD5 |
|---|---|---|
| boot | GabOS Android 12 installed-boot image | `04d49f41bef0bea2d601aec27fe1240f` |
| recovery | Android 8.1 v14 rescue system | `7a3ac489b05f0f2e9cde16280551511a` |

The boot image starts the framework without a host and runs a boot-success
watchdog (900 s): if Android 12 does not report a completed boot it restarts into
the recovery slot, which now boots v14 with root ADB. Promotion boot and one
additional reboot both returned Android 12 with `ro.bootmode=emmc`, the boot-ok
marker, stable system_server, GabOS Home focused and validated Wi-Fi.

Rescue and rollback:
- From v14 (rescue) or GabOS: `python tools/promote-android12.py --action rollback --execute`
  writes boot = v14 and recovery = factory from `/realdata/gabos-rescue/`.
- The factory recovery is no longer installed in the recovery slot. Its verified
  copies are in `local/device-kit/original/`, every run record and on the device
  at `/realdata/gabos-rescue/factory-recovery.img`.
- Still preserved, not removed: Android 7.1.2 system partition, v14 system/data
  files on userdata, kernel, uboot, trust, resource.

Measured in the rehearsal (ActivityManager TotalTime, cold / warm): TV Settings
1153 / 280 ms, Files 2267 / 411 ms, Media 649 / 117 ms, Diagnostics 778 / 170 ms.
Before the HIDL change, first frames took 7.2 to 7.5 s. Memory after boot:
about 499 of 997 MiB available. Not yet checked: the audio transient with the
longer standby (owner listening), SmartTube playback, Bluetooth accessories,
real power-off, long sessions. Removing the older systems is the next stage and
needs its own layout plan (the 2 GiB system partition and userdata space).

## Current direction: lightweight GabOS without Google (owner decision after run gtv-02)

Run `20261111-211628-cf5e53bf` booted `gabos-gtv-02` (Google TV Home, GSF, Play
services, Play Store; 1200 MiB system, 704 MiB grown data, 3600 s watchdog).
Measured over USB, not through the lens unless stated:

- Boot completed about 60 s after the framework trigger; system_server stayed
  up for the whole session. The owner confirmed the menus looked correct.
- Every host-compiled package reported `status=speed`; the launcher's recorded
  class-loader context matched the host one.
- Google check-in succeeded and Home showed its profile chooser. No account was
  added. Google packages used roughly 750 MiB PSS on the 1 GB board.
- Google TV Home does **not** run without Play services ("Google Play Services
  not available", permanent loading skeleton). With Play services enabled and
  no account/Play Store its "basic TV" mode worked, but still needs Play
  services and shows a fixed sign-in banner. It has no local profile.

The owner then chose: **no Google packages at all**, a GabOS launcher redesigned
to look like that basic mode with an offline profile name, and stability and
low memory first. If that is good, promote it to primary and remove the other
systems step by step. The Google TV section below is kept as tested history;
`--google-tv/--google-services/--play-store` remain available but are not the
target.

Changes made for that direction:

- **GabOS Home 0.6** (`work/rayo-tv/MainActivity.java`): "Home" page with a
  Settings tile (native TV Settings), Files, banner-style app tiles, a greeting
  card and a local name chip. The name is stored only in the app's preferences.
  About 15 MiB PSS in the live session versus about 320 MiB for Google's Home.
- **GabOS Keyboard** (`KeyboardService.java`, same APK): the D-pad-only Leanback
  keyboard could not be used with the projector pointer (owner report: stuck on
  one key). The new keyboard accepts pointer clicks and D-pad. It was installed
  in the live session; the owner typed a name with it and a D-pad plus pointer
  test over USB entered characters. The packager now drops LeanbackIME when the
  launcher carries the keyboard, and the boot script selects it.
- **Cold-start stall root cause**: libhidl sleeps one second in every
  `getService()` on products that do not enforce the VINTF manifest. Each
  graphical process made about seven such lookups, matching the observed 7.2 to
  7.5 s first frames ("Davey") and the earlier eight-second cold opens.
  `tools/apply-android12-hidl-compat.py` removes only that fixed delay under
  `ro.rayo.hidl.no_legacy_wait`; the packager declares the registered
  configstore service and passthrough mapper 2.0 and refuses unpatched
  libhidlbase copies. **The effect is not yet measured on the device.**
- The controller no longer aborts when a USB read fails during reboot. In run
  gtv-02 that race stopped it before the framework trigger; the same preflight
  (PID 1, mounts, init MD5) was then done by hand.
- `tools/archive-device-staging.py` moved 44 superseded lab artifacts from
  userdata to `local/device-staging-archive/` after per-item checksum
  comparison (829 to 1710 MiB free before gtv-02). No installed system, its
  data, or the on-device copies of the normal boot image were touched.
- Aurora Store rendered and was browsed in that session (earlier it was blank);
  one crash was an application-side duplicate-key error in its list UI.

Next candidate, **not yet booted**: `outputs/android12-framework/gabos-lite-01`
(880 MiB system, same 704 MiB data UUID retained on the device, 3600 s watchdog,
no Google packages).

```powershell
python -u tools/trial-android12-framework.py --candidate outputs/android12-framework/gabos-lite-01 --reuse-known-artifacts --execute
```

Gates before promotion: cold-start timing with the HIDL change, no boot
regressions from it, keyboard in native Settings (Wi-Fi password), owner lens
checks, repeated boots, then a written promotion/rollback plan. The device
clock shows UTC; the time zone is unset.

## Google TV direction (tested history; superseded by the section above)

The owner now wants a stable Android 12 with the Google TV Home, working Google
services, and eventually GabOS as the only installed system. Earlier notes in
this and other documents that say "do not add Google services" are historical.
Native Android TV Settings with transparency, native Files and the guarded trial
lifecycle remain required. Promotion/removal of older systems is still gated on
demonstrated stability.

Prepared offline, **not yet booted on the device**:
`outputs/android12-framework/gabos-gtv-01` (WSL staging
`~/android/rayo12-framework-candidates/candidate-_j9d9ubc`).

- Original, unmodified and not re-signed ADT-3 Google TV 12 packages: Home
  (`launcherx`), its stubs and TV system library, Google Services Framework,
  Play services (Pano), the TV Play Store and PartnerSetup, with the reference
  privileged allowlists. `tools/prepare-android12-framework.py --google-tv
  --google-services` stages them. The reference resource overlays are not
  packaged (see the comment in the packager); Katniss/Assistant, SetupWraith and
  Cast are not included.
- `/system/bin/gabos-google-mode` (started once at boot-complete) selects the
  Home activity and Play-services state from `persist.gabos.launcher.mode` /
  `persist.gabos.gms.enabled`: `google_tv_account` (default with services),
  `google_tv` (apps-only Home) or `gabos_light`. GabOS Home stays installed as
  the fallback. Its component switching is untested on the device.
- No certification bypass, fingerprint spoofing or device-type feature is used.
  An uncertified device normally must be registered by the owner with their own
  account at Google's uncertified-device page using the GSF Android ID before
  sign-in works. The agent does not configure a personal account.
- Host AOT: `tools/android12_dexpreopt.py` compiles every packaged application
  with the source-built host dex2oat (`speed`) against the packaged boot image.
  The eng product only compiled the boot image/system server and the board runs
  with JIT disabled, so applications were previously interpreted. Whether the
  runtime accepts each odex (class-loader context match) must be confirmed with
  `dumpsys package dexopt` on the device; cold-start gains are unmeasured.
- The system image is now sized from its contents (1200 MiB here, 800 MiB
  minimum). The private data image is 1024 MiB, grown offline by
  `tools/grow-android12-data.py` from the copy pulled in run
  `20261111-200245-2009d634` with the same UUID/features. The controller
  replaces the device file only with `--replace-data` and only if the device
  copy still has that run's exact MD5; the new file is staged and renamed after
  checksum verification.
- The bootstrap now reads the data size (256 MiB-3 GiB, exact match required)
  and the independent watchdog window (`/rayo12-watchdog.cfg`, 600-14400 s,
  default/fallback 600) from the ramdisk. This candidate uses 3600 s so Google
  first-run setup and owner checks fit in one session; return early with
  `adb shell setprop sys.powerctl reboot`.

Physical userdata is 4409 MB in total. The new images need the controller's
printed space check to pass with the unchanged 768 MiB reserve; if it does not,
shrink the data image or drop preopt for the largest packages rather than
lowering the guard or deleting older systems early.

First device session (owner connects the projector on normal v14):

```powershell
. ./local/setup/enter-project-env.ps1
python tools/cleanup-android12-staging.py --framework            # plan only first
python -u tools/trial-android12-framework.py --candidate outputs/android12-framework/gabos-gtv-01 --reuse-known-artifacts --replace-data            # plan/space check
python -u tools/trial-android12-framework.py --candidate outputs/android12-framework/gabos-gtv-01 --reuse-known-artifacts --replace-data --execute
```

Then check, in order: boot completes with stable system_server; `gabos-google-mode
status`; `dumpsys package dexopt` for odex acceptance; Home projected; Play
services/GSF processes without crash loops; RAM (`dumpsys meminfo`); only then
owner registration/sign-in and Play Store.

### Runs after the previous handoff

- `20261111-183038-8dc4616f` (`gabos-full-redraw-01`): framework booted, but the
  controller did not observe the normal return. Factory recovery was restored
  afterwards with `tools/restore-framework-recovery.py` (see its
  `RECOVERY-RESTORATION.json`). The cause of the missed return was not recorded.
- `20261111-191901-5a2f5249`: returned and restored; framework gate not reached.
- `20261111-200245-2009d634`: fresh boot of `gabos-full-redraw-01` completed,
  returned to v14, factory recovery restored. This is the latest verified device
  state on record; re-verify hashes before any new trial.

## Latest user decisions (before the Google TV decision)

- Home 0.5 Settings must open the actual Android TV Settings, without a custom
  GabOS settings page/app. The Control prototype was removed from packaging.
- Preserve the native settings panel's transparency and the visible content
  behind it. An opaque-window workaround was explicitly rejected and reverted.
- Retain native Android Files (DocumentsUI). Do not create a custom file manager.
- GabOS Media and Diagnostics are the useful minimal app replacements. Defer
  casting protocols and signed updater until real implementations/recovery exist.
- Keep performance as a priority and do not add Google services. Aurora Store
  4.8.4 and SmartTube 32.71 ARMv7 are installed in isolated Android 12 data with
  original signatures. Installed/resumed does not mean fully functional.
- The owner is available again for physical checks, and reported gray flicker
  behind the transparent settings panel and a loud transient at first audio.
- Finish the bounded trial, update the handoff/pending list and commit/push.

## Current prepared candidate

`outputs/android12-framework/gabos-full-redraw-01`

WSL staging source:
`/home/gabri/android/rayo12-framework-candidates/candidate-ynrfib7g`.

This is the final offline, checksum-verified candidate; it has not had a fresh
boot as an immutable image. The previous candidate was tested and runtime
properties were then changed as described below.

It restores upstream TV transparency/transitions. The opt-in
`debug.rayo.graphics.client_blend=1` makes HWC2On1 request client composition for
all non-target layers when fractional plane alpha or a skipped null-buffer dim
layer is present. Original alpha/blending values and framebuffer-target allocation
remain unchanged. This is a proposed composition fix requiring physical checking;
it is not the rejected opaque background. The owner confirmed that this GPU
composition change alone still showed gray/flickering behind Settings.

A subsequent runtime test set both `debug.hwui.use_buffer_age=false` and
`debug.hwui.use_partial_updates=false`, then force-stopped/reopened TV Settings.
The new USB capture correctly showed dimmed Home behind the panel instead of
solid gray. These exact properties are now baked into this offline candidate.
The last owner question about physical confirmation was still pending when this
handoff was prepared. Do not claim it resolved by the lens based on the capture.
The combined test does not isolate which property is necessary; it may increase
GPU work. Measure full-redraw cost and isolate buffer-age/partial-update/client-
composition switches before claiming performance improvement.

The candidate also includes the board-scoped PCM16 100 ms soft start. It reduces
initial PCM gain after stream creation/standby, without inserting frames or
changing timestamps. Host sample tests and HIDL 7 build passed; amplifier noise
is not proven resolved until owner listening. Native HAL format/route inspection
is preferable to guessing codec mixer/register writes if it persists.

The experimental source OMX facade queries actual `RKOMX_*` factory exports in
the isolated legacy namespace. A native probe passed enumeration, AVC role,
component creation/free, C struct size (88 bytes) and two video ports using the
platform's actual parameter header version 1.0. Android's codec registry now lists
`OMX.rk.video_decoder.avc` as hardware with rank 1. Real frame decoding, colors,
sound and sustained performance are still separate gates. Do not confuse codec
registration or component creation with playback.

## Verified history and limitations

- Radio-05 `20261111-093343-b66a3207`: automatic Wi-Fi internet validation and
  off/on reconnection; BT ON with zero crashes and fresh discovery of five
  devices; repeated Home/Settings/Back; verified return/recovery restoration.
- Apps-01 `20261111-112554-64cc4231`: both optional apps installed; native Files
  and small GabOS apps opened; core PIDs stable; original 640x360 AVC/AAC sample
  completed twice using C2 software decoders. Player dump recorded 145 frames,
  zero dropped. Physical output was not checked in that run.
- Apps-02 `20261111-114108-06941c28`, media-01
  `20261111-130801-3a15b63f`, opaque settings/audio trial
  `20261111-133021-416f763b`: normal return and factory recovery restoration
  verified. The opaque UI candidate is superseded/rejected.
- Aurora's activity resumed but its USB capture stayed blank and cold launch
  waits timed out. AOT `speed` succeeded for Aurora; its UI is still unverified.
  SmartTube's initial cold activity launch succeeded in 9507 ms; streaming remains
  unverified. Full SmartTube AOT did not finish before a trial ended.
- Initial frame probe was contaminated by expensive cold-window startup. A
  warmed probe delivered 461 callbacks in 5005 ms. This is not panel FPS or proof
  of smooth rendering. Small app cold opens took roughly eight seconds.
- Shutdown through both framework and `reboot -p` returned to v14 after a brief
  USB disconnect. Inactivity shutdown is prepared but deliberately disabled;
  do not enable an automatic reboot loop. Physical power-off remains pending.
- Bluetooth scanning is not pairing/HID/A2DP/BLE validation; current audio policy
  is primary-only. Debug/permissive and legacy security constraints remain.

## Device guards and trial lifecycle

ADB serial `0123456789ABCDEF` can report `recovery` in either OS. Identify release,
completed boot and hashes, not that banner. By-name base:
`/dev/block/platform/1021c000.rksdmmc/by-name/`.

- Normal boot MD5: `7a3ac489b05f0f2e9cde16280551511a`.
- Factory recovery MD5: `b32f971e7dfce13c708d264c6221844d`.
- Private Android 12 data is loop6, 256 MiB, UUID
  `a5851dd3-71e3-4f0d-aa46-45d14ba8986b`; system is 800 MiB read-only loop7.
- The independent C watchdog returns to normal after 600 seconds. The host
  controller starts framework only after PID1/storage/identity checks, observes
  Settings with stable system_server for 45 seconds, then verifies normal v14
  and restores factory recovery with readback. Do not interrupt that restoration.
- Physical MaskROM/emergency entry remains untested. Do not erase/format or flash
  unrelated RK3128/TV images. Keep original recovery and independently verified
  host backups even after eventual promotion.
- Space is tight on physical userdata and isolated data. The trial preserves a
  768 MiB physical reserve plus staging overhead. Use only the guarded cleanup
  for checksum-known duplicate staging pairs; it keeps two device recovery copies.
  Do not lower guards or delete old OS files just to make an unvalidated trial fit.

## Useful commands

```powershell
. ./local/setup/enter-project-env.ps1
adb devices
adb shell getprop ro.build.version.release
adb shell getprop sys.boot_completed
python tools/cleanup-android12-staging.py --framework --execute
python -u tools/trial-android12-framework.py --candidate outputs/android12-framework/gabos-full-redraw-01 --reuse-known-artifacts --execute
```

After the controller reports stable Settings, use bounded probes and owner checks:

```powershell
python tools/collect-gabos-radio-state.py --tag next-check
python tools/probe-gabos-experience.py --execute --tag next-experience
adb shell /system/bin/rayo12_omx_plugin_probe /system/lib/librayo12_omx_plugin.so --create
```

The experience probe changes foreground apps; do not run it while the owner is
interacting. It reports frame callbacks and local sample playback, not physical
acceptance. The controller/watchdog has a fixed ten-minute window; do not launch
long AOT compilation near its end. For early normal return, use
`adb shell setprop sys.powerctl reboot` and let the controller restore recovery.

## Build/portability

WSL Ubuntu-24.04, user `gabri`:
- Source `~/android/rayo12` (pinned `android-12.0.0_r34`).
- TV output `~/android/rayo12-tv-out`.
- Native output `~/android/rayo12-out`.
- Radio output `~/android/rayo12-radios-out`.

Host tools are installed by the local setup script; Java 17 builds small apps,
AOSP uses its bundled Java 11. SDK 26, build-tools 36.0.0, NDK 25.2.9519653.
Key replay/build entry points:

```powershell
./tools/build-lightweight-tv.ps1
./tools/build-gabos-app.ps1 -App media
./tools/build-gabos-diagnostics.ps1
wsl bash tools/build-android12-tv.sh TvSettings libhwc2on1adapter android.hardware.audio@7.0-impl librayo12_omx_plugin rayo12_omx_plugin_probe libstagefright_omx
```

Packaging uses `--local-launcher --gabos-apps --software-media --legacy-media`
plus explicit TV/native/radio product paths and the known private-data manifest.
See the exact commands in local preparation logs or the scripts' argument help.
Do not build in WSL `/tmp`; durable candidates belong under the home directory.

Git excludes APKs, system/recovery images, source/output caches, private reports
and signing credentials. Another PC needs a private transfer of `local/` and the
selected `outputs/` (including compatibility validation records), or a full
rebuild plus the original private device kit. The older device-kit release is
not this new Android 12 checkpoint. Never upload active trial userdata, Wi-Fi
profiles, signing passwords/keys or raw private logs to Git/releases.

Two pre-existing unrelated dirty files must remain untouched/uncommitted:
`tools/build-launcher.ps1` and `work/android81/fix_host_python.py`.

## Final device state and trial result

Trial `20261111-134210-12720cc5` completed boot, stable Settings observation,
normal v14 return and full factory recovery restoration/readback. Latest explicit
ADB verification reported Android 8.1.0, boot-completed 1, and the exact normal
boot/factory recovery MD5s listed above. No older system was removed or new primary
boot installed. The device remained on v14; shutdown is still not fixed.

During that trial Wi-Fi was associated with validated internet; Bluetooth was ON
with zero crashes; system_server/netd PIDs remained 415/268 in the collected radio
snapshot. The native OMX probe passed creation/free and two video ports again.
The owner confirmed the first client-composition attempt still showed gray and
flickered transparent with pointer movement. The later full-redraw runtime test
corrected the internal capture; physical confirmation is pending. No new listening
result was obtained for the PCM soft start, and no hardware-decoded clip was
physically accepted. Keep those issues open.

Local evidence is under `local/google-tv-reference/` and
`local/android12-framework-runs/20261111-134210-12720cc5/RESULT.json`. In particular,
`gabos-full-redraw-settings-usb.png` shows the corrected internal composition;
`gabos-transparent-settings01-later-usb.png` shows the prior solid-gray result.
Those raw captures/reports are private and excluded from Git.

For a different PC, retain the previous device-system candidate
`outputs/android12-framework/gabos-transparent-settings-01` as well as the final
`gabos-full-redraw-01` and completed run records. The guarded reuse tool verifies
the currently stored system against a known prior image and the retained data
UUID against a completed/restored trial. Do not bypass that check if only the
final offline image was copied. See `ANDROID12_NEXT_STEPS.md` for priorities.
