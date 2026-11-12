# Instructions for continuing this project

## Language, status, and priorities

Maintain all repository documentation, development notes, new code comments, and project-facing technical text in **English**. Preserve exact OEM identifiers, protocol strings, artifact paths, and external interfaces. Conversation with the owner may remain in clear Spanish. Do not change the device UI language as a side effect of this policy.

Read `docs/HANDOFF.md`, `docs/ANDROID12_NEXT_STEPS.md`, `docs/STATUS.md`, `docs/RECOVERY.md`, and `docs/PORTABILITY.md` first. The goal is a lightweight Canon Rayo S1 distribution preserving projection, controls, and recoverability. A library loading or a correct internal screenshot does not establish hardware functionality: verify actual projection and interaction.

## Organization and authorization

- Work inside this repository. Keep binaries, dumps, credentials, backups, and large files in Git-ignored `local/` or `outputs/`.
- User authorization and conversation preferences take precedence. Do not request repeated confirmation for already authorized work; complete checks and prepare reviewable changes.
- Do not treat boot, system, uboot, trust, kernel, or resource as routine test partitions. Current v14 uses normal boot and private files on userdata, with original independent recovery restored. The previous system is retained. Further permanent/system-layout changes require their own plan and recovery checks.
- Never erase or format storage as a diagnostic step. Review image, paths, and context before running historical flashing scripts.
- Never upload signing keys, passwords, Wi-Fi credentials, tokens, or account files to Git, tracked logs, or releases.

## Device identification

- Stock: ADB serial `3GKL2KGCA400000000`, Android 7.1.2.
- Trial: serial `0123456789ABCDEF`; ADB reports `recovery`, but the OS is Android 8.1.0.
- Check `adb devices`, `getprop ro.build.version.release`, `id`, and partition paths before changes.
- Backups and addresses belong to this unit. Do not assume compatibility with another projector or any other RK3128 board.

## Technical constraints

- Preserve the original kernel for the established trial. HwBinder guards its kernel banner and structure layouts; never bypass those checks or force-load it. A new kernel is a separate porting project.
- `/system/lib/egl/libGLES_mali.so` and its vendor path must resolve to the same library, rather than two independent loaded copies.
- Preserve Rockchip `libion.so`; generic libion lacks extensions used by proprietary components.
- Factory HWC has a private ABI: four extra callbacks, different lists, 208-byte layers. Preserve the adapter and static checks; do not directly call it with AOSP layouts.
- HWC1 SurfaceFlinger requires `mPrimaryDispSync.init(hasSyncFramework, dispSyncPresentTimeOffset)`. Its absence caused one-second waits and cursor lag. The known configuration uses `debug.sf.no_hw_vsync=0`.
- Stock `PRJ_*` entries invalidate Generic.kl in AOSP. Validate maps with the Android 8.1 parser and capture actual events before assigning unknown keys.
- Wi-Fi requires original `fw_RK903_ag.bin`, AP6330 configuration, HAL NET_ADMIN/NET_RAW capabilities, and wpa_supplicant started on wlan0. The userdata-backed trial needs the documented limited capability treatment.
- Boot images use Rockchip's extended SHA-1 and a gzip ramdisk padded to four-byte length. Generic Android packaging must preserve these requirements.
- Normal 7.1.2 boot automatically restores stock recovery. Stop `flash_recovery`, write and verify trial recovery, then reboot directly into recovery.
- Current checkpoint is v14, promoted to normal boot after explicit acceptance of its experimental status. Normal reboot now starts 8.1, not 7.1.2. To return to 7.1.2 restore its verified boot image first; see PRIMARY_BOOT_PLAN.md. Do not ask for repeated acceptance of the completed promotion. Stop wifi_hal_legacy/bluetooth-1-0 before applying limited executable file capabilities, and start them only after permissions/capabilities are ready. v13 exposed a boot race: already-running HALs did not inherit newly assigned file capabilities.
- Bluetooth uses the exact factory-library import patch and libtg.so null-timer guard, original bcm40183b2.hcd, feature declarations, and UID bluetooth with NET_ADMIN. Preserve the factory library separately. Do not restore the temporary root/LD_PRELOAD diagnostic as the production path.
- The Rockchip AVC sample was physically confirmed after reboot, with OMX.rk.video_decoder.avc selected in logs. Do not generalize this to every codec, resolution, DRM service, or prolonged playback. Bluetooth ON/discovery is not pairing verification.

## Validation

Back up and checksum components before replacing them. Stop consumers before overwriting mapped libraries. Graphics changes require boot/process checks plus physical projection, colors, orientation, and cursor response. Wi-Fi checks must distinguish enabled interface, scan, connection, and validated internet. Do not claim Bluetooth or video functionality without testing.

## Handoff

Prefer portable `tools/` entry points. Historical copies may contain original absolute paths. AOSP and Linux caches are in `local/`; pinned manifests support fresh source downloads. Transfer `local/secrets/` privately to update the installed launcher and GabOS apps with the same signing identity. Read `docs/UPGRADE_FEASIBILITY.md` for hardware constraints and `docs/STATUS.md` / `docs/ANDROID12_NEXT_STEPS.md` for the newer measured checkpoints.

## Current Android 12 work

GabOS Android 12/API31 has booted on the original kernel with Home/Settings, working radio startup and isolated private data. Native colors and earlier Home navigation were physically confirmed; newer media/application checks remain separate. It is not a stable release or the primary boot. The owner accepted a lightweight original GabOS UI without Google services, and selected native Android Files instead of a custom file manager. See `docs/GABOS_APPS.md`.

The full pinned source is in WSL `~/android/rayo12`, with separate TV/native/radio outputs. Compile ARMv7/Cortex-A7/Binder32; the ADT-3 ARMv8 runtime is incompatible. Keep the legacy Mali/ION namespace, original kernel/HwBinder guards and independent watchdog. Never flash an unrelated TV-box image.

The owner has authorized installing Aurora/SmartTube and useful app replacements, then promoting Android 12 and removing older active installations only after stability is established. Do not request duplicate permission, but do not bypass failed acceptance checks or erase the preserved factory partitions/only recovery copies prematurely. Real shutdown currently reboots; Aurora's interface has shown blank output; physical media, accessory profiles, power cycles and prolonged stability remain pending. Use the guarded temporary trials and verify the normal v14 return and full recovery readback after each run.

## Latest native settings correction

The owner wants the actual Android TV Settings, with working transparency and visible background content. Do not restore a custom GabOS settings page or an opaque background as a purported transparency fix: the owner explicitly rejected that workaround. Home 0.5 launches native Settings directly. The current trial uses `debug.rayo.graphics.client_blend` to request complete client composition for affected alpha/dim frames while retaining alpha values. Physical flicker and first-audio transient checks remain separate; see the final result in `docs/HANDOFF.md`. Keep the handoff/pending list and README current, then commit/push only project changes.

The final offline continuation image is `outputs/android12-framework/gabos-full-redraw-01`. The GPU fallback alone did not fix gray flicker; the owner confirmed that failure. A runtime test with both `debug.hwui.use_buffer_age=false` and `debug.hwui.use_partial_updates=false` and a TV Settings restart corrected the internal capture. Those flags are now baked offline; fresh-boot, lens and performance validation remain pending. The last device trial returned to v14 and restored factory recovery/readback. Follow `docs/HANDOFF.md` rather than assuming the final offline image was installed or declaring the pop/media/power issues fixed.

## Lightweight direction (latest)

After the Google TV trial (run `20261111-211628-cf5e53bf`) the owner chose a
GabOS **without any Google packages**: Google TV Home needs Play services, which
cost about 750 MiB on this 1 GB board. Target: the redesigned GabOS Home (basic
Google-TV-like layout, offline profile name), the GabOS pointer/D-pad keyboard,
native TV Settings and Files, stability and low memory first, then promotion to
primary and stepwise removal of the older systems. Do not modify or re-sign
Google APKs to fake an offline profile. Keep `ro.rayo.hidl.no_legacy_wait`
paired with complete HAL declarations. Space on userdata is tight; never lower
the reserve guard. Follow the first section of `docs/HANDOFF.md`.
