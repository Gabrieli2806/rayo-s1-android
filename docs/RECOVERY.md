# Recovery and write boundaries

## Verified recovery paths

Android Recovery, Rockchip Loader, partition reads, writing original recovery, readback, and matching MD5 were tested. The trial was exited repeatedly to Android 7.1.2, with stock projection confirmed by the owner. **MaskROM and physical emergency entry remain untested.**

Session Rockusb drivers/tools are preserved locally and in the private kit. Observed Loader USB ID: `2207:310c`. Do not substitute another device's loader, DDR initialization, or firmware merely because it also uses RK3128.

## This unit's partitions

Base: `/dev/block/platform/1021c000.rksdmmc/by-name/`.

| Partition | Start LBA | Sector count |
|---|---:|---:|
| uboot | 0x2000 | 0x2000 |
| trust | 0x4000 | 0x2000 |
| misc | 0x6000 | 0x2000 |
| resource | 0x8000 | 0x8000 |
| kernel | 0x10000 | 0x10000 |
| boot | 0x20000 | 0x10000 |
| recovery | 0x30000 | 0x10000 |
| backup | 0x40000 | 0x20000 |
| cache | 0x60000 | 0x40000 |
| metadata | 0xa0000 | 0x80000 |
| kpanic | 0x130000 | 0x2000 |
| system | 0x132000 | 0x400000 |
| radical_update | 0x532000 | 0x20000 |
| userdata | 0x5f2000 | remaining |

These addresses are records for this unit, not automatic detection for other devices.

Address-space clarification from the November 10 init diagnostic: these are Rockchip Loader-relative LBAs. Linux sysfs reports userdata's physical start as `0x5f4000` (the listed `0x5f2000` plus a `0x2000`-sector prefix). Recovery includes an additional enumerated `parameter` partition, so minor numbers differ from normal boot. Prefer checked by-name paths in a running OS; early bootstrap selection must verify physical start/size and the unit's filesystem UUID. Never turn these relative Loader offsets directly into unreviewed raw Linux whole-disk writes.

Current trial checkpoint is v14 in private kit v0.2. Earlier v12 remains in kit v0.1. To roll back the whole runtime, restore its matching overlay as well as recovery; changing only recovery does not restore libraries/configuration stored on userdata. Preserve current runtime before rollback and do not erase trial application data.

## Return to stock boot

After normal-boot promotion, `adb -s 0123456789ABCDEF shell setprop sys.powerctl reboot` restarts 8.1. It no longer returns to 7.1.2. First restore the verified previous boot using `python tools/main-boot.py --action restore-previous-boot --execute`, then request normal reboot. This post-promotion restore path is prepared, not yet tested. Original recovery is independently restored; physical emergency entry remains unproven. Legacy static adbd's `adb reboot` may wait indefinitely; this alone does not prove a device hang.

Normal Android may restore stock recovery through `install-recovery.sh`. Check version, completed boot, and recovery MD5; do not assume trial recovery remains installed after normal boot.

## Writing recovery

Original recovery is exactly 33,554,432 bytes. Portable tooling checks identity, version, image size, transfer, and readback before/after writing only recovery. To enter the trial from stock, stop `flash_recovery`, write/verify recovery, and reboot directly into recovery.

Loader `WL 0x30000 0x10000 recovery.img` followed by matching `RL` and comparison was tested. Review local documentation/logs before using the CLI; its working directory requires `config.ini`. Do not flash a virtual PARM read from LBA 0 as a physical storage image.

## Experimental image format

Preserve factory kernel, second RSCE block, addresses, and 16 KiB boot-image page size. Rockchip SHA-1 additionally hashes `header[32:576]`. Pad the gzip ramdisk to a four-byte length before declaring its size and hashing. Both details were necessary for loader acceptance. Boot-image packaging page size is distinct from the Linux memory page-size requirement of a newer Android release.
