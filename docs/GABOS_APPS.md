# GabOS application integration

## Scope requested by the owner

Use native Android Files, not a custom file manager. Implement the necessary
lightweight replacements, install Aurora Store and SmartTube, and promote GabOS
only after stability is established. Removal of older active installations is
authorized conditionally on that acceptance; keep independent recovery and host
backups. Repeated permission is unnecessary, but passing evidence is necessary.

## Implemented applications

| Component | Implementation |
|---|---|
| GabOS Home | Dark TV launcher 0.5; Settings opens the native TV settings activity directly |
| Native Android TV Settings | Owner selected the real TV settings menu instead of GabOS Control/custom Home settings; Home Settings opens ACTION_SETTINGS directly |
| GabOS Diagnostics | RAM/storage/API/radio reporting, explicit speaker-tone button, private Bluetooth discovery counts and a five-second Choreographer callback probe |
| GabOS Media | Native document picker, local video/audio playback via platform codecs, and bounded 1280x720 image decoding; no network permission or background playback service |
| Android Files | AOSP Android 12 DocumentsUI, built from the pinned source with its matching permission allowlist |

Media is approximately 13 KiB; Diagnostics is approximately 17 KiB. These are APK sizes, not memory measurements. They are ordinary apps
signed with the private development identity, not extra privileged services.
`tools/build-gabos-app.ps1 -App media`,
`tools/build-gabos-diagnostics.ps1` and `tools/build-lightweight-tv.ps1` reproduce
them. `prepare-android12-framework.py --gabos-apps --local-launcher` verifies
checksum metadata and includes them in an offline system candidate.

Native Files replaces the custom Files proposal. Sharing/casting is deferred
until its protocols and codecs can actually be implemented/tested; an updater
must not be presented as functional before signed update and rollback gates exist.

## Optional third-party applications

`manifests/gabos-optional-apps.json` pins exact versions, sources, APK SHA-256 and
signing identities. `tools/install-gabos-apps.py --execute` checks those pins and
requires completed GabOS API31 with isolated loop6 data. The rescue adbd does
not provide abb_exec, so the installer uses checked push + shell Package Manager
and removes only its exact temporary APK. No signature bypass or Google services
are installed. APKs remain in the ignored private device kit.

- SmartTube stable 32.71 ARMv7 matches the publisher's GitHub release digest.
- Aurora Store 4.8.4 matches the official F-Droid repository APK byte for byte.

Aurora account/onboarding/store downloads and SmartTube streaming still require
functional checks; an installed package or a resumed activity does not prove them.

## Device checkpoint: apps-01

Run `20261111-112554-64cc4231` completed boot, returned to normal v14 and restored
factory recovery with verified readback. Both third-party packages installed with
the expected versions. Native Files reached its FilesActivity. The then-present Control prototype, Media and Diagnostics launched. Control was
subsequently removed from packaging at the owner's request. Wi-Fi remained associated with validated internet; Bluetooth
remained ON with zero crashes; netd and system_server retained their PIDs.

The original six-second 640x360 AVC/AAC test completed twice. The player selected
`c2.android.avc.decoder` and `c2.android.aac.decoder`; its dump recorded 145 video
frames and zero dropped frames. This is software decoding, not Rockchip hardware
acceleration or owner-confirmed lens/audio output. The existing flattened software
codec APEX already supplies C2 codecs; optional OMX XML integration is a separate
experiment, not a prerequisite proven missing from C2 playback.

Aurora's activity resumed but its USB capture stayed blank and cold-launch waits
timed out. AOT compilation to `speed` succeeded for Aurora; this did not establish
that its interface works. The main thread trace was idle, not an identified Java
crash. SmartTube's initial activity launch returned OK in 9507 ms. Its full speed
compile did not complete before the bounded trial ended; do not claim that compile
as an optimization pass. Baseline v14 Diagnostics measured 279 frame callbacks in
5009 ms. The temporary baseline diagnostic package was uninstalled afterward.

## Promotion blockers

Resolve Aurora's blank UI and measure app/animation responsiveness. Verify hardware
decoding, real speaker/lens output, Bluetooth profiles, power-off/cooling and cold
power cycles. The owner is unavailable for physical checks in this session.
Do not enable inactivity shutdown while shutdown causes a reboot. No older OS or
factory partition has been erased, and Android 12 has not been promoted.

## Apps-02 and hardware-media preparation

Run `20261111-114108-06941c28` also completed framework boot, returned to v14 and
restored factory recovery. It includes explicit software OMX declarations and
the frame-probe app revision. No completed Android 12 frame-probe measurement
was collected before the device disconnected; do not infer a result from the
baseline measurement.

The opt-in hardware-media candidate uses a source-built OMXPluginBase facade
that forwards only the factory OMX C API through the existing isolated legacy
namespace. Current C++ Vector/String8 objects stay in the current namespace.
Factory enumeration and roles are actually queried; only the existing AVC
component is exposed. The XML quirks/limits are copied from the checksum-pinned
v14 declaration, not invented or advertised as measured performance. The C
component/buffer ABI and real decoding still require device validation.

`tools/apply-android12-omx-compat.py` patches only the guarded plugin selection;
`--software-media --legacy-media` explicitly selects this experiment during
framework packaging. The original default loader remains unchanged when the
board property is absent. A source enumeration-only probe and
`tools/probe-gabos-experience.py` provide bounded tests; neither certifies
physical output or daily-use stability.

## Hardware-media-01 and follow-up fixes

Run `20261111-130801-3a15b63f` completed boot and normal v14 return with factory
recovery restored/readback verified. Native Files, Media and the original Control
prototype reached their expected activities; core PIDs were unchanged during the
automated experience test. The original facade queried generic OMX symbol names
and therefore failed closed. Factory ELF exports actually use `RKOMX_*`; after
correcting those names, a temporary facade/probe in private data passed factory
enumeration/AVC-role checks and component creation/free. Component size matched
the Android 12 C header (88 bytes). An initial port query used the wrong 1.1
parameter header and returned version mismatch; the probe was corrected to the
platform's actual InitOMXParams 1.0 header. That corrected port query still needs
a device run. Creating a component is not decoding a frame.

The initial five-second frame measurement started during expensive window startup
and saw only two callbacks. It is not a steady-state VSYNC result. Diagnostics
0.3 delays the measurement 15 seconds; the subsequent device test observed 461
callbacks in 5005 ms. This is callback delivery, not measured panel FPS; it is
not directly comparable to the earlier cold-start baseline or proof of smooth
rendering. Cold opens of small apps still took about eight seconds in that run.

The owner is now available and reported physical gray flickering behind the
translucent native settings panel, plus a loud transient at first audio output.
The owner selected native Android TV settings exclusively and rejected the custom
GabOS settings menu. Home 0.5 now launches native settings directly; Control is
no longer packaged. The dedicated native TV theme is opaque and direct entry/exit
avoids the dim/slide overlay animation under `ro.gabos.settings.opaque`.

An opt-in PCM16 soft start attenuates the first 100 ms after stream creation or
standby. It only reduces gain, adds no audio frames and does not change timestamps.
Host tests cover zero/stereo/negative/full-scale samples, completed ramps and
unsupported rates/channel counts/unaligned lengths. It cannot establish that
amplifier power-up noise is resolved; owner listening is required. The code is
replayed by `apply-android12-audio-soft-start.py` and enabled only by the board
property. Both TvSettings and HIDL audio 7 built successfully. Candidate
`outputs/android12-framework/gabos-settings-audio-01` contains these changes and
the corrected experimental hardware facade; device/physical checks are pending.
