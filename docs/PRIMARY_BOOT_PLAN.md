# Main-boot proposal and previous-system backup

Date: November 9, 2026. **Prepared, not installed. The unit still uses its previous 7.1.2 boot partition and v14 through recovery.**

## Stability boundary and authorization

The owner asked to replace the previous system if v14 was stable. Core projection/input/Wi-Fi/audio and the Rockchip AVC sample have passed, with Bluetooth ON/discovery, but extended use, pairing, suspend/power, thermal behavior, and independent physical emergency entry remain unverified. This is still an experimental checkpoint. The conditional request is not treated as unconditional acceptance of those limitations. Do not mark the primary installation complete or write boot unless the owner explicitly accepts using the experimental checkpoint as the default, or the required stability gate is actually satisfied.

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

## Concrete proposed installation

The conservative proposal makes the existing 8.1 runtime the normal startup while **retaining the 7.1.2 system partition and its userdata**. It does not erase 7.1.2, repartition, wipe data, or write kernel/bootloader/resource. Runtime behavior remains the tested file-backed v14 system plus overlay/private data; this is not a newly integrated monolithic system image.

1. Require the previous-main private release to be published with matching SHA-256.
2. Check identity/root, current boot/recovery hashes, v14 graphics/Bluetooth/media components, local factory fallback images, image length, factory kernel payload, and boot load addresses.
3. Restore independent original Android recovery and verify its partition readback.
4. Write the v14 candidate to boot, verify readback, and request normal boot. This selection path itself still requires device testing; recovery-slot success is not represented as normal-boot proof.
5. Verify physical projection, boot completion, Wi-Fi internet, Bluetooth, cursor/buttons, and hardware sample playback. If the normal boot fails, restore the previous verified boot through available ADB/recovery/Loader access; do not improvise writes to other partitions.

Factory boot and recovery use identical kernel bytes and load addresses: kernel 0x60408000, ramdisk 0x62000000, second block 0x60f00000, tags 0x60088000, image page size 16,384 bytes. The proposed boot candidate is byte-identical to verified recovery v14, SHA-256 `9e7c48594fae621e74a37ea4bd427fa3284f0ab578cbf8ff94187bb654278c2d`. These checks reduce differences; they do not prove normal boot or independent physical rescue.

`python tools/main-boot.py --action plan` performs inspection without writes. The install action requires `--action install-experimental --accept-experimental` and a published backup. Its restore action writes only the verified previous boot image and requests no reboot. Those actions have not been executed during preparation. See RECOVERY.md for the tested recovery boundaries.
