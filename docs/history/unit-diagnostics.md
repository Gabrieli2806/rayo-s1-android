# Canon Rayo S1: initial unit diagnostics

Historical baseline, November 8, 2026, read through ADB before modifications. Current status is in `../STATUS.md`.

## Confirmed baseline

USB changed from MTP-only 2207:0001 to MTP+ADB 2207:0011. `su 0 id` returned root; root already existed and no exploit/root manager was installed. RK3128 has four 32-bit Cortex-A7 ARMv7 cores, declared maximum 1.2 GHz, armeabi-v7a. Android 7.1.2 API 25; Linux 3.10.104.

Build Rayo RWBT-KOR-V1.2N, product rk3128_box, system Rayo S1_USA_V1.1, built April 26, 2019. Linux RAM: 1,021,052 kB; eMMC: 7,636,800 1-KiB blocks, approximately 7.8 decimal GB. userdebug/test-keys, permissive SELinux, ro.adb.secure=0. System was read-only; active fstab lacked verify, with the verified alternative commented out. sys.secureboot=false and SecureBootCheckOk=0 did not establish complete modified-image acceptance.

Canon's 1.3.1N download at https://vn.canon/en/support/CC0000036 contains Android 4.4.4/rk312x, unlike this unit's 7.1.2/rk3128_box. It was not installed or established as a compatible restore image.

Android declares 1280×720; fb0 reports 864×480. Neither alone measures the physical DMD or proves native 720p. Canon specifies 854×480.

Root enables launcher/application/system customization with retained stock integration. New Android must preserve or replace graphics, video, projection, keystone, battery, and controls. A shared SoC does not establish firmware compatibility. Device-tree prj_lcdc (rockchip,rk312x-prj-lcdc) was saved in device-tree.tar; later investigation found it disabled. No modified ROM, fastboot, Loader, or MaskROM tests existed at this baseline.

## Backups

Original uboot/trust/misc/resource/kernel/boot/recovery/system images were saved under `respaldo-original/`; sizes and SHA-256 records were checked. Seven boot-related partition MD5 values matched the device. Boot MD5: a42af2dbc934f2fc77bff14de619f116. System: 2,147,483,648 bytes, matching MD5 db616b91117fa5ad9c6ab568c7d690aa.

These are not complete eMMC/user-data backups: some partitions, pre-uboot code, and special eMMC areas are absent. No writes, unlocking, or system modifications occurred at this baseline.

## Partition mapping

| Name | Device |
|---|---|
| uboot | mmcblk0p1 |
| trust | mmcblk0p2 |
| misc | mmcblk0p3 |
| resource | mmcblk0p4 |
| kernel | mmcblk0p5 |
| boot | mmcblk0p6 |
| recovery | mmcblk0p7 |
| backup | mmcblk0p8 |
| cache | mmcblk0p9 |
| metadata | mmcblk0p10 |
| kpanic | mmcblk0p11 |
| system | mmcblk0p12 |
| radical_update | mmcblk0p13 |
| userdata | mmcblk0p14 |

Links: `/dev/block/platform/1021c000.rksdmmc/by-name/`.
