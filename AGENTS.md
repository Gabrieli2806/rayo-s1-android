# Instructions for continuing this project

## Language, status, and priorities

Maintain all repository documentation, development notes, new code comments, and project-facing technical text in **English**. Preserve exact OEM identifiers, protocol strings, artifact paths, and external interfaces. Conversation with the owner may remain in clear Spanish. Do not change the device UI language as a side effect of this policy.

Read `docs/STATUS.md`, `docs/RECOVERY.md`, and `docs/PORTABILITY.md` first. The goal is a lightweight Canon Rayo S1 distribution preserving projection, controls, and recoverability. A library loading or a correct internal screenshot does not establish hardware functionality: verify actual projection and interaction.

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

Prefer portable `tools/` entry points. Historical copies may contain original absolute paths. AOSP and Linux caches are in `local/`; pinned manifests support fresh source downloads. Transfer `local/secrets/` privately to update the installed launcher with the same signing identity. Read `docs/UPGRADE_FEASIBILITY.md` before proposing newer Android versions; none beyond 8.1 is verified on this unit.

The owner requested Android 12 TV if adaptation is modest. The source/runtime audit in `docs/ANDROID12_TV_ASSESSMENT.md` found substantial work. Do not claim an Android 12 build or port exists, flash ADT-3/TV-box images, or treat the bounded source-audit lock as a full platform manifest.

The latest owner request explicitly targets Android 12 and the original Google TV menu/UI. Follow `docs/GOOGLE_TV_12_PORT.md` and `docs/ANDROID12_NATIVE_LAB.md`; do not silently substitute a custom lookalike or another Android version. The full pinned `~/android/rayo12` source client is synchronized; the native-only Cortex-A7/Binder32 lab product builds. Actual source-built Android 12 Bionic and EGL/GLES wrappers passed disposable native/offscreen Mali tests on the original kernel. This does not prove an Android 12 OS boot, compositor, physical projection, Google integration or performance. Keep image outputs disabled for the lab product. The ADT-3 core runtime is ARM32 but uses ARMv8/Cortex-A53 instructions and SIGILLs on this Cortex-A7; compile the core for ARMv7. Google applications remain local inspection assets, not installed packages. Current Linux source/output caches live outside the repository on WSL ext4; see PORTABILITY.md.
