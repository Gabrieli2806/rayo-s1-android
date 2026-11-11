# First Android 12 OS trial plan

Status: November 10, 2026. Temporary trials have reached Android 12 System UI, projected wallpaper and owner-confirmed cursor movement. TV Settings still needs its missing Wi-Fi framework service and no real launcher is installed. See [the framework trial record](ANDROID12_FRAMEWORK_TRIAL.md) for current evidence. This is not a stable installation candidate; normal boot remains Android 8.1 v14.

## Protected baseline

- Keep v14 in normal boot, its read-only system image, private data and live overlay unchanged. Do not promote Android 12 to boot during the first trial.
- Keep the original kernel/DT resources, bootloader, trust and partition layout unchanged. Never wipe/format userdata, system, cache or another partition for diagnosis.
- Fresh boot/recovery copies were read from this unit and matched their recorded MD5 and size on November 10: `local/android12-rollback/20261110-010008-22223e62/MANIFEST.json`. Boot SHA-256 is `9e7c48594fae621e74a37ea4bd427fa3284f0ab578cbf8ff94187bb654278c2d`; recovery remains the independent factory image. Reproduce with `tools/backup-android12-rollback.py` before the actual trial.
- Retain the verified private kits and the previous 7.1.2 backup as independent host copies. MaskROM/physical emergency entry is still untested; do not imply guaranteed recovery from arbitrary bootloader/storage damage.

## Trial storage and root

The live 8.1 `/data` is a private bind under physical userdata. Its original physical filesystem is accessible at `/realdata`. Confirmed mount/path inspection showed that `/data/local/tmp/rayo81-system.raw.img` does not exist in this view; **do not infer physical image paths from the live `/data` alias**.

Proposed physical paths, not created yet:

```text
/realdata/local/tmp/rayo12-system.raw.img
/realdata/rayo12-trial/data/
/realdata/rayo12-trial/cache/
```

An eventual recovery ramdisk would mount physical userdata at `/realdata`, attach the new system image read-only on a separately checked loop device, and bind only the new 12 data/cache directories. It must never reuse or initialize the 8.1 data directory. Measure actual free blocks and image size before upload; retain space for new runtime data, diagnostics and recovery operations. Last measured free space was about 2.4 GiB, not an unlimited staging area.

Android 12 needs its own init, root configuration, flattened APEX/runtime preparation and linker namespaces. Reusing the 8.1 init scripts unchanged is not a port. The current TV product builds source components with image outputs disabled; root/OEM integration is incomplete. The tested child-process library mount namespace is a native experiment, not a substitute for the OS root design.

## Before writing a trial image

1. Complete and inspect the actual TV product output, including ARMv7 instruction attributes for native/APEX content and protocol-7 Binder/HwBinder. Keep all existing kernel/layout guards.
2. Package OEM graphics in a consistent legacy namespace. Preserve the single Mali inode and Rockchip libion; use the factory HWC ABI adapter plus upstream HWC2On1. Native pbuffer/local mapper/HWC-load tests have passed, but composer callbacks, transport and projected composition remain unverified.
3. Prepare root/init, early USB rescue access and persistent bounded boot logs. Validate Android 12 RC syntax with its built host verifier. Configure old-kernel cgroups, SELinux policy compatibility, radio capabilities and USB deliberately. A fallback watchdog is only useful if its startup and reboot path have actually been tested.
4. Package a new recovery-sized image using the verified original kernel/second payload, original load addresses, 16 KiB packaging pages, four-byte gzip padding and Rockchip extended SHA-1. Re-parse and independently verify the resulting image and retained kernel bytes. A built library or system tree alone is insufficient.
5. Recheck the known serial, current OS/id, physical partition paths, normal boot and original recovery hashes, image hashes/sizes and sufficient userdata headroom. Preserve a fresh independent recovery copy. Verify the normal-boot return method for the chosen recovery entry mechanism; do not assume a failed recovery trial will automatically select normal boot.
6. Stage and verify the new system file and new private directories first. Writing recovery, then immediately entering that trial, is a separate guarded step. `trial-android12-stage1.py` now implements a bounded **init-only diagnostic**, with preflight, fresh recovery backup, exact readback, bounded observation and original-recovery restoration. It is not an installer for the full TV ROM. The first two runs stopped safely at the identity guard and returned to verified v14; see ANDROID12_NATIVE_LAB.md.

## Recovery-slot test and acceptance

The intended first OS test temporarily uses the recovery slot while v14 remains in boot. During that interval the factory recovery is backed up, not simultaneously present in the same slot. Restore and verify it after returning to v14. Never describe the temporary slot replacement as preserving an active independent recovery in place.

Start with early ADB/logs, framework boot and actual projected output/colors/orientation. Confirm interaction physically. Then validate Wi-Fi enable/scan/connect/internet, original input/buttons, audio, AVC and Bluetooth using the established distinctions in AGENTS.md. The restored legacy-network backend needs functional firewall/accounting checks; a successful compile is insufficient. Failed gates return to normal v14 and retain logs; no wiping or permanent boot promotion is part of this test.

Integrate original Google TV Home and the smallest validated supporting service set after board boot/projection. Keep original Google signatures/assets private, handle platform-signed Settings/SystemUI contracts correctly, and measure RAM/frame timing/storage on this 1 GB device. No current test establishes Google TV usability or final stability.
