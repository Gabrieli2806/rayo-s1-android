# Main-boot proposal and previous-system backup

Date: November 9, 2026. **Installed and verified: v14 is now the normal boot image; original independent recovery is restored. The previous 7.1.2 system partition is retained.**

## Stability boundary and authorization

The owner initially conditioned replacement on stability. After the remaining limitations were explained and the backup/proposal were complete, the owner explicitly accepted making experimental 8.1 the default. The guarded installation was then executed. Do not request the same acceptance again for this completed change. Core projection/input/Wi-Fi/audio and the Rockchip AVC sample passed, with Bluetooth ON/discovery; extended use, pairing, suspend/power, thermal behavior, cold-start validation, and independent physical emergency entry remain pending. This is not a final stable release.

## Previous Android 7.1.2 preserved

`tools/backup-previous-main.py` read original-main boot/system/kernel/resource/uboot/trust/misc while 8.1 was running from its separate file-backed system. Every read matched the device MD5 and expected size. Original independent recovery was copied from the already verified private kit. No userdata, Wi-Fi passwords, account data, or host signing keys are included.

The new snapshot includes prior launcher/logo modifications rather than only factory files:

| Image | Bytes | MD5 |
|---|---:|---|
| boot.img | 33,554,432 | a42af2dbc934f2fc77bff14de619f116 |
| system.img | 2,147,483,648 | 10bf2f08ddb0a1395dbf60ced19e8475 |
| kernel.img | 33,554,432 | d3c8f218c8fab93a66b8f83c03ce21eb |
| resource.img | 16,777,216 | cc4ff45af66c33e8ff64c1076bcabbe3 |
| recovery.img | 33,554,432 | b32f971e7dfce13c708d264c6221844d |

The private release `previous-main-7.1.2` contains `rayo-s1-previous-main-7.1.2.zip`, 399,884,997 bytes, SHA-256 `cda9be2262016b602dd7b65624be00b1034458fc72e679f8e615b73c47a0fcfd`. See `manifests/previous-main-backup.json`; the internal manifest includes every file's SHA-256/MD5 and provenance. ZIP entries passed CRC verification. Preserve the factory backups in the earlier device kits too.

This is not a full eMMC dump. In particular, do not restore misc routinely: it was captured while the recovery-slot trial was active. No MaskROM/physical recovery guarantee is implied.

A separate read-only e2fsck 1.47 inspection reported only an unset padding area at the end of the inode bitmap. The raw snapshot was not repaired or changed: it intentionally matches the source partition byte for byte, including that metadata condition. Transfer/hash verification must not be described as a clean-filesystem result.

## Installed configuration and procedure

The installed configuration makes the existing 8.1 runtime the normal startup while **retaining the 7.1.2 system partition and its userdata**. It does not erase 7.1.2, repartition, wipe data, or write kernel/bootloader/resource. Runtime behavior remains the tested file-backed v14 system plus overlay/private data; this is not a newly integrated monolithic system image.

1. Require the previous-main private release to be published with matching SHA-256.
2. Check identity/root, current boot/recovery hashes, v14 graphics/Bluetooth/media components, local factory fallback images, image length, factory kernel payload, and boot load addresses.
3. Restore independent original Android recovery and verify its partition readback.
4. Write the v14 candidate to boot, verify readback, and request normal boot. This was completed and tested; recovery-slot success was not substituted for the normal-boot test.
5. Verify physical projection, boot completion, Wi-Fi internet, Bluetooth, cursor/buttons, and hardware sample playback. If the normal boot fails, restore the previous verified boot through available ADB/recovery/Loader access; do not improvise writes to other partitions.

Factory boot and recovery use identical kernel bytes and load addresses: kernel 0x60408000, ramdisk 0x62000000, second block 0x60f00000, tags 0x60088000, image page size 16,384 bytes. The proposed boot candidate is byte-identical to verified recovery v14, SHA-256 `9e7c48594fae621e74a37ea4bd427fa3284f0ab578cbf8ff94187bb654278c2d`. These checks reduce differences; they do not prove normal boot or independent physical rescue.

`python tools/main-boot.py --action plan` performs inspection without writes. The install action requires `--action install-experimental --accept-experimental` and a published backup. It was executed after the owner's acceptance. `--action restore-previous-boot --execute` writes only the verified previous boot image and requests no reboot; this restore action has not been tested following promotion. Restoration accepts the identified 8.1 runtime or the identified original recovery with root ADB and matching original recovery hash; it does not establish a physical way to enter recovery. See RECOVERY.md for the tested recovery boundaries.

## Actual installation results

- Boot written/read back: MD5 `7a3ac489b05f0f2e9cde16280551511a` (v14).
- Recovery written/read back: MD5 `b32f971e7dfce13c708d264c6221844d` (original).
- Normal reboot requested; Android 8.1.0 returned with sys.boot_completed=1 and ro.bootmode=emmc.
- The static recovery-derived adbd still advertises recovery and serial 0123456789ABCDEF. That is not evidence of recovery-slot boot: original recovery is restored and cannot run this full 8.1 system.
- Wi-Fi had Android-validated internet and Bluetooth was ON.
- The Rockchip-decoded AVC/AAC clip completed; the owner confirmed projection/colors/sound/panel all worked after normal boot.
- Previous system, userdata, kernel, bootloader, and resource were not overwritten. No original-system deletion or partition resizing was performed.

The owner asked when the old system could be deleted. No deletion is authorized or scheduled here: first validate sustained operation and independent recovery. Merely clearing the separate system partition would not enlarge userdata. A future integrated-system/storage-layout change is a separate operation.
