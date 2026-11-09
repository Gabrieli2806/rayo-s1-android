# Instructions for continuing this project

## Language, status, and priorities

Maintain all repository documentation, development notes, new code comments, and project-facing technical text in **English**. Preserve exact OEM identifiers, protocol strings, artifact paths, and external interfaces. Conversation with the owner may remain in clear Spanish. Do not change the device UI language as a side effect of this policy.

Read `docs/STATUS.md`, `docs/RECOVERY.md`, and `docs/PORTABILITY.md` first. The goal is a lightweight Canon Rayo S1 distribution preserving projection, controls, and recoverability. A library loading or a correct internal screenshot does not establish hardware functionality: verify actual projection and interaction.

## Organization and authorization

- Work inside this repository. Keep binaries, dumps, credentials, backups, and large files in Git-ignored `local/` or `outputs/`.
- User authorization and conversation preferences take precedence. Do not request repeated confirmation for already authorized work; complete checks and prepare reviewable changes.
- Do not treat boot, system, uboot, trust, kernel, or resource as routine test partitions. The known experiment uses recovery and private files on userdata. Permanent installation requires a specific plan and recovery checks.
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

## Validation

Back up and checksum components before replacing them. Stop consumers before overwriting mapped libraries. Graphics changes require boot/process checks plus physical projection, colors, orientation, and cursor response. Wi-Fi checks must distinguish enabled interface, scan, connection, and validated internet. Do not claim Bluetooth or video functionality without testing.

## Handoff

Prefer portable `tools/` entry points. Historical copies may contain original absolute paths. AOSP and Linux caches are in `local/`; pinned manifests support fresh source downloads. Transfer `local/secrets/` privately to update the installed launcher with the same signing identity. Read `docs/UPGRADE_FEASIBILITY.md` before proposing newer Android versions; none beyond 8.1 is verified on this unit.
