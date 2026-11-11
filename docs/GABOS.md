# GabOS

GabOS is the owner's name for the lightweight custom system on this Canon Rayo
S1 (RK3128 Cortex-A7/ARMv7, Mali-400, about 1 GB RAM and 8 GB eMMC).
Current identity: `GabOS 0.1-dev`, Android 12/API 31. The device/manufacturer,
kernel version, Android API and compatibility flags must stay truthful.
Internal `rayo` paths, package names and hardware properties remain unchanged
to preserve working updates, driver selection and rollback guards.

`ro.gabos.name`, `ro.gabos.version`, the build display ID and device model identify
the offline system candidate. The launcher is labeled GabOS Home, version 0.4;
its package/signing identity stays compatible with the development installation.
The owner's G remains the brand artwork. No device change is performed by naming
or packaging the offline candidate.

## Hardware adaptation and performance

Keep the existing Cortex-A7/Binder32 build, legacy Mali/HWC isolation, ION client
targets, audio ABI guards, caller-context compatibility and low-RAM profile.
The selected Home has no Internet permission or Google services. Preserve the
physical display path and measure actual frame timing rather than raising CPU
frequencies or changing framebuffer geometry to guess at lag.

Disabled JIT is a diagnostic workaround for a previously observed ART crash,
not a proven performance optimization. Re-enable it only in a separate guarded
trial after identifying the failure and comparing app latency/RAM/frame timing.
The current debug/permissive build and software-key limitations are not a
production security configuration. Review debug access and remaining security
compatibility before calling a daily-use release stable.

Hardware stability cannot be certified offline. Wi-Fi, Bluetooth, media, power,
cold boots, repeated navigation and prolonged use must pass the acceptance
checks in [ANDROID12_NEXT_STEPS.md](ANDROID12_NEXT_STEPS.md). That document also
records the **pending recovery restoration verification**, which comes first
when the projector is reconnected. Normal v14 remains the protected baseline.

## Factory application inventory

`tools/inventory-factory-apps.py` reads the checksum-verified private factory
system without mounting it or accessing a device. It inventories 62 APKs across
system, privileged, bundled-persistent and ecloud directories. This includes
background components, not 62 user-facing apps. Exact package names, versions,
labels, locations and hashes are in `manifests/factory-app-inventory.json`.
Some OEM resource references cannot be resolved by standalone aapt; those
warnings are recorded without inventing a label. Later user-installed apps,
including SmartTube and Aurora, are outside this factory inventory.

## Useful first-party replacements

| Original functions/packages | Proposed GabOS application | Priority and limits |
|---|---|---|
| Rayo Launcher (`com.htc.htcrayolauncher`) | GabOS Home | Already implemented; dark TV layout, app icons and Settings access |
| Hotack Settings (`com.hotack.hotackkoreasettings`), RockKeystone (`com.rockchip.keystone`) | GabOS Control | High priority after OS stability; unify projector settings only after verifying each vendor control/API |
| FileExplorer (`com.android.rockchip`), ApkInstaller (`com.android.apkinstaller`), Downloads | GabOS Files | USB/local browsing and package installation through Android's existing installer; no custom signature bypass |
| Video (`android.rk.RockVideoPlayer`), Music (`com.android.music`), Gallery (`com.android.gallery3d`) | GabOS Media | Simple video/music/photo UI; reuse tested codecs, with hardware decoding validated separately |
| DeviceTest (`com.rockchip.devicetest`) | GabOS Diagnostics | RAM/storage, radio state and explicit display/audio/input checks; distinguish reported values from measured hardware behavior |
| AirPlay, EDLNA, EShareServer, EShare and Mirroring (`com.ecloud.*`, `com.rockchip.wfd`) | GabOS Share | Later research; each receiving/control protocol and decoder dependency needs its own implementation and tests |
| System Update (`android.rockchip.update.service`) | GabOS Updater | Last stage; signed packages, explicit compatibility checks and rollback, after recovery is proven |

Other confirmed factory apps include Lightning browser, Canon Camera Connect
(`jp.co.canon.ic.cameraconnect`), WPS Office, clock and calculator. Recreating a
full office suite, browser or Canon camera protocol is not part of the minimal
OS plan; existing maintained applications can provide those functions.

Implement Home, Control/Diagnostics and Files first. Keep one small application
per useful role instead of adding many always-running services. Add media and
sharing once the relevant lower-level hardware paths pass. Promotion to permanent
boot follows those checks; keep independent factory/rollback backups afterward.
