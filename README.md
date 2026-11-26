# GabOS for the Canon Rayo S1

GabOS is a lightweight Android 12 (API 31) system for the Canon Rayo S1 pocket
projector (Rockchip RK3128, quad Cortex-A7, Mali-400, about 1 GB RAM, 8 GB eMMC).
It replaces the factory Android 7.1.2 interface with a small TV-style system
that has no Google services, and it keeps a separate rescue system available
on the projector.

Current release: **GabOS 1.0** (November 13, 2026). This is a hobby project
for one projector. It is not a stable consumer ROM. Read
[What works and what does not](#what-works-and-what-does-not) and
[docs/INSTALL.md](docs/INSTALL.md) before you change anything on a projector.

GabOS by [g2806.net](https://g2806.net).

The development history (Android 8.1 trials, early Android 12 labs, Google TV
experiments) lives in the `archive/research-history` branch; `main` keeps only
what GabOS 1.0 needs.

## Features

**GabOS Home.** A dark, Google-TV-like home screen with a greeting card, a
battery ring, a Settings tile, native Android Files, and banner tiles for the
installed apps. It has no `INTERNET` permission and no account (it reads
Wi-Fi and network state for the status line and the setup wizard). After
three minutes on Home without input, a clock screensaver appears.

**GabOS Settings.** The Settings tile asks whether to open GabOS Settings
(projector, light, sound, look) or the native Android TV Settings (Wi-Fi,
Bluetooth, apps). GabOS Settings provides:

- Projector controls through the factory optical-engine driver: brightness,
  colour tone, placement (table, ceiling, and the two rear-projection options,
  with a flipped picture to match), and manual keystone.
- Power and light: turn the light source fully off after a period without use
  (5 min to 1 h, or never), turn the light off with the screen, and an
  "audio only" mode that turns the light off while sound keeps playing. The
  touch panel or any key turns the light back on.
- Volume, plus shortcuts to Wi-Fi, Bluetooth, date and time, Diagnostics and
  Android settings.
- About: GabOS version, optical engine version, and battery level.

**Appearance.** Dark or light mode and an accent colour: ten presets, or a
custom hue chosen with a slider. The choice is shared with every GabOS app
through a theme provider in Home.

**First-boot setup.** A short wizard covers welcome, language, Wi-Fi (scan,
password entry, or more options in Android settings), and look. Any step can
be skipped and changed later.

**English and Spanish.** Home, GabOS Settings, the keyboard, Media,
Diagnostics, Hardware Check and Share include both languages. You can choose
the language in the setup wizard.

**GabOS Keyboard.** An on-screen keyboard that works with both the
touch-panel pointer and the D-pad. The stock Leanback keyboard could not be
used with the pointer.

**GabOS Share and the phone app.** The projector runs a small receiver on the
local Wi-Fi network. On a phone, the GabOS Share app (`work/gabos-share-sender`,
built as `outputs/GabOS-Share-Sender.apk`, Android 5.0 or newer) finds the
projector automatically, or you can type its address. You can also share to it
from the phone's gallery. Without the app, any browser on the same network can
open the projector's address. Every transfer must be accepted on the
projector. **Show** plays the photos or videos once and keeps nothing. **Save**
stores them in `Pictures/GabOS` and `Movies/GabOS`. The protocol is described
in [work/gabos-share/PROTOCOL.md](work/gabos-share/PROTOCOL.md).

**GabOS Media and Diagnostics.** Media is a simple local video, music and
photo player. Diagnostics reports RAM, storage and radio state, and runs
explicit display, audio, input and video-decoder tests. Hardware Check (the
earlier validation app) is also included.

**Hardware video decoding.** A source-built facade loads the factory Rockchip
OMX decoders in an isolated legacy namespace. These decoders are exposed with
the factory limits: AVC (H.264), HEVC (H.265), VP8, MPEG-4, MPEG-2 and H.263.
They are ranked ahead of the software decoders. Measured on the device:
SmartTube played 720p AVC with `OMX.rk.video_decoder.avc` and 0 dropped frames
in 25 s. The Diagnostics decoder test decoded 720p and 1080p AVC, VP8 and H.263
to a real surface with no codec-service crashes. With software decoding, the
same stream dropped 22 to 44% of its frames.

**Apps.** Aurora Store 4.8.4 (the pinned F-Droid build, original signature, so
it updates itself) is preinstalled. You can use it to install ordinary Android
apps without Google services. SmartTube 32.71 (ARMv7) is supported and
installed by `tools/install-gabos-apps.py`. Apps that require Google Play
services do not work. Google TV Home was tested and rejected: it needs Play
services, which use about 750 MiB of the 1 GB RAM.

**Boot.** A GabOS wordmark boot animation plays, the interface scale is set for
a projected 720p image, and the touch panel and keys wake the projector.

## What works and what does not

Measured on the original unit:

| Area | State |
|---|---|
| Projection, colours, orientation, pointer | Working through the factory HWC and Mali driver behind compatibility adapters |
| Built-in keys, touch panel as pointer, D-pad | Working; touch-panel scrolling and click behaviour still under review |
| Wi-Fi | Connects with validated internet; reconnects after reboot |
| Bluetooth | Turns on and discovers devices. Pairing, HID, A2DP and BLE are **not** verified |
| Speaker audio | Working; a brief pop when audio starts may still occur (anti-pop stream and soft start included, final listening check pending) |
| AVC hardware video, SmartTube 720p | Working (see above) |
| HEVC hardware video | Enabled in 1.0 with the factory decoder; newer and less tested than AVC |
| VP9, MPEG-4/MPEG-2 hardware | VP9 is software only. MPEG-4 and MPEG-2 are exposed but have no on-device decode test |
| Native TV Settings, Files, Aurora Store | Working; native Settings keeps its transparent panel |
| Memory | About 499 of 997 MiB available after boot |
| Cold start of small apps | Under about 0.8 s cold for Media and Diagnostics, about 1.2 s for TV Settings, about 2.3 s for Files |

Known limits:

- **Power-off reboots.** A shutdown request restarts the projector instead of
  turning it off. Use the "light off" options in GabOS Settings, or the
  hardware power switch. Automatic idle shutdown is prepared but disabled.
- **CPU speed.** The kernel holds the CPU at 816 to 912 MHz, even with the
  performance governor.
- **Security.** The build is an `eng` build with permissive SELinux, root ADB,
  and software-only Keymaster and Gatekeeper. Do not treat it as a secure
  daily device for sensitive accounts.
- **No Google services**, by design.
- **Not yet checked:** long sessions, repeated cold power cycles, battery
  runtime, temperature under load, protected (DRM) streaming, the microphone,
  and Bluetooth accessories.
- **One unit.** The installation tools currently contain values specific to the
  original projector. See "Current limitations" in
  [docs/INSTALL.md](docs/INSTALL.md#current-limitations).

## Safety model

GabOS never repartitions or formats the eMMC.

- **Normal boot** (`boot` partition) holds the GabOS boot image. The factory
  kernel is kept byte for byte; only the ramdisk is new.
- **Recovery slot** (`recovery` partition) holds the **Android 8.1 "v14"
  rescue system**, a full Android with root ADB.
- GabOS's system image and its private data image are **files on the userdata
  partition**. The bootstrap checks the system image's SHA-256 on every boot
  before mounting it read-only. The factory Android 7.1.2 `system`, `kernel`,
  `uboot`, `trust` and `resource` partitions are not written.
- A **boot-success watchdog** in the GabOS ramdisk restarts into the recovery
  slot if Android 12 has not reported a completed boot within 900 seconds.
  The rescue system then starts by itself.
- Verified copies of the factory recovery and the v14 rescue image are kept on
  the host and on the projector (`/realdata/gabos-rescue/`).
  `python tools/promote-android12.py --action rollback --execute` writes boot =
  v14 and recovery = factory again. A further documented step returns to the
  factory 7.1.2 boot.
- Every GabOS write tool checks the current partition checksums first,
  writes, and reads back. Trials, promotion and updates also keep a run
  record under `local/`. It refuses to run if anything is unexpected.

Not proven: recovery from bootloader damage, MaskROM entry, or a physical
emergency procedure. See [docs/RECOVERY.md](docs/RECOVERY.md).

## Wireless updates

GabOS 1.4 and later update themselves from this repository's releases
(**Settings > Updates**). Releases contain no factory files: the projector
restores them from its own installed copies and verifies everything against a
signed manifest. See [docs/INSTALL.md](docs/INSTALL.md#11-wireless-updates-gabos-14-and-later).

## Install or mod it with an AI assistant

[docs/ASSISTANT.md](docs/ASSISTANT.md) is a playbook for an AI coding
assistant with shell and ADB access (for example Claude Code). It tells the
assistant how to identify your projector, back it up, install or update
GabOS, and change it, with the safety rules this device needs. Open the
repository in the assistant and ask:

> Read `docs/ASSISTANT.md` and help me with my Canon Rayo S1.

## Documentation

- [docs/ASSISTANT.md](docs/ASSISTANT.md): guided install, update and modding
  with an AI assistant.
- [docs/INSTALL.md](docs/INSTALL.md): build and install from zero, with the
  current limitations.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): boot chain, bootstrap, legacy
  namespace, graphics, media, images and the update path.
- [docs/HANDOFF.md](docs/HANDOFF.md) and
  [docs/ANDROID12_NEXT_STEPS.md](docs/ANDROID12_NEXT_STEPS.md): development
  state and open work.
- [docs/RECOVERY.md](docs/RECOVERY.md): partition layout and recovery paths.
- [docs/GABOS.md](docs/GABOS.md), [docs/GABOS_APPS.md](docs/GABOS_APPS.md),
  [docs/GABOS_RADIO_BRINGUP.md](docs/GABOS_RADIO_BRINGUP.md): product, app and
  radio notes.
- [docs/BUILD.md](docs/BUILD.md): the Android 8.1 build that produced the v14
  rescue system.
- [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md): provenance and licences.
- [AGENTS.md](AGENTS.md): rules for anyone (human or agent) continuing the work.

## Repository layout

| Path | Contents |
|---|---|
| `tools/` | Build, packaging, install, update and rollback entry points |
| `work/android12/` | GabOS device products, PID 1 bootstrap, init scripts, input maps, audio policy |
| `work/rayo-tv/` | GabOS Home, GabOS Settings, setup wizard, GabOS Keyboard |
| `work/gabos-ui/` | Shared UI kit compiled into every GabOS app |
| `work/gabos-media/`, `work/gabos-diagnostics/` | GabOS Media and Diagnostics |
| `work/gabos-share/`, `work/gabos-share-sender/` | Share receiver (projector) and sender (phone) |
| `work/rayo-validation/` | Hardware Check |
| `work/android81/` | Android 8.1 rescue sources; HWC ABI adapter, HwBinder module and key maps still used by GabOS |
| `manifests/` | Pinned AOSP manifests, optional app pins, device-kit descriptor |
| `patches/` | AOSP patches (8.1 rescue and Android 12 network) |
| `local/`, `outputs/` | Backups, factory blobs, images, keys and build outputs. **Excluded from Git** |

## Credits

- GabOS, its apps, artwork and the G logo: Gabrieli2806,
  [g2806.net](https://g2806.net).
- Android Open Source Project (`android-12.0.0_r34`, and `android-8.1.0_r81`
  for the rescue system).
- Rockchip's public 3.10 kernel sources. The Binder backport is from
  LineageOS (`android_kernel_samsung_msm8974`). The Rockchip HWC reference is
  from the TinkerBoard Android project.
- [SmartTube](https://github.com/yuliskov/SmartTube) and
  [Aurora Store](https://gitlab.com/AuroraOSS/AuroraStore) are independent
  projects, used unmodified.
- The boot wordmark uses Roboto (Apache 2.0) from the Android source.

Exact revisions are in [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md) and
`manifests/`.

## Licence notes

- There is no single licence for the whole repository yet. Each file keeps its
  upstream notices: AOSP code is Apache 2.0, and Linux and Binder module code
  is GPLv2. The GabOS apps and artwork belong to the project owner.
- **Factory components are never redistributed.** The Canon/Rockchip
  proprietary libraries, firmware, kernel and partition images (Mali, HWC,
  OMX/VPU, audio, Wi-Fi/Bluetooth firmware, the factory kernel and the
  HwBinder module built against it) are not in Git and not in public releases.
  The build takes them from **your own projector's backup**. The private
  `device-kit` release in this repository is the original unit's backup, and
  it is not for distribution.
- Built GabOS images contain those factory components. Do not publish them.
  Signing keys, Wi-Fi credentials and device data stay in `local/` and are
  never committed.
