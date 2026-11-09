# Android and recovery plan: Canon Rayo S1

Historical review, November 8, 2026. Current results supersede this plan; see ../STATUS.md.

## Position at this stage

Stock customization was practical: root, backups, modified images, Android Recovery, and Loader communication existed. Newer Android projection was not yet proven. No newer Android was installed during this investigation; stock 7.1.2 completed boot afterward. Logo orientation was corrected by rotating both resource images and the Android-maintained image 180 degrees.

RK3128, four 32-bit ARMv7 Cortex-A7 cores, declared 1.2 GHz; approximately 1 GB RAM; 7,457 MiB eMMC reported by Rockchip. Original Linux 3.10.104, API 25, userdebug. No active ro.treble.enabled; /vendor links to /system/vendor, no separate vendor partition or proven Treble/GSI compatibility. Device tree contains rockchip,rk312x-prj-lcdc (later found disabled). System partition is 2 GiB although stock filesystem occupies approximately 1 GiB; respect the actual partition boundary.

## Recovery tests at this stage

| Test | Result |
|---|---|
| Android to recovery using ADB | Passed; owner confirmed projected menu |
| ADB in recovery | Passed; recovery banner, temporary serial 0123456789ABCDEF |
| adb root in recovery | Protected block reads and MD5 available |
| Read boot/recovery in recovery | Matched original backup MD5 |
| Recovery menu to bootloader | Owner selected; Windows detected 2207:310C |
| Windows driver | Rockusb 5.14; valid Microsoft Windows Hardware Compatibility Publisher catalog |
| Rockchip mode query | Mode=Loader; Test Device OK |
| Storage query | Samsung eMMC, 7,457 MiB, readable partition table |
| Loader boot read | Complete 32 MiB matched ADB backup |
| Loader RD exit | Android returned to Rayo Dark |
| Direct adb reboot bootloader | Repeated successful Loader communication |
| Loader resource read after logo fix | Complete 16 MiB matched v0.3 |
| Second RD reboot | sys.boot_completed=1; Rayo Dark visible |

Boot MD5 a42af2dbc934f2fc77bff14de619f116; recovery MD5 b32f971e7dfce13c708d264c6221844d; resource v0.3 MD5 cc4ff45af66c33e8ff64c1076bcabbe3.

At this stage tests covered entry, diagnostics, reads, and exit, not intentional failure or complete Loader write restoration. MaskROM and Canon-specific physical entry with damaged Android/recovery were untested. Unsupported RSM query proves neither locked nor unlocked state.

## Tools and backups

Windows upgrade_tool.exe 2.23 from Vicharak; driver from Radxa. ZIPs retained under historical recuperacion/herramientas/ and driver log under recuperacion/.

- https://docs.radxa.com/en/rock4/rock4d/low-level-dev/tool_rkdevtool
- https://dl.radxa.com/tools/windows/DriverAssitant.zip
- https://github.com/vicharak-in/rockchip-linux-tools/tree/master/windows
- https://wiki.t-firefly.com/en/Firefly-RK3128/Boot_Mode.html

Original system/boot/recovery/kernel/resource/uboot/trust/misc remain in respaldo-original/. First 4 MiB physical eMMC reads through Android and separate initial Loader reads were saved. Loader's initial view begins with PARM and differs from physical Android reads: protocol-reserved views differ. Never interchange these copies or write the physical first-4-MiB backup using WL 0. Boot/resource partition reads did match byte for byte. Historical parameter-desde-loader.txt was extracted.

No complete eMMC/user-app-data dump exists. Back up userdata/needed apps before any change requiring data reset.

## Historical Android target assessment

| Target | Assessment at that stage |
|---|---|
| Customized 7.1.2 | Verified base preserving drivers |
| Lightweight 8.1 | First proposed port, not yet confirmed then |
| Lightweight 9 | Next study after projection/graphics/video/HAL compatibility |
| Low-RAM 10–12 | Larger investigation without a working compatible board base |
| 13 and later | Poor initial target for 1 GB; Android 13 Go specifies 2 GB minimum |

Google's Go table lists 1 GB minimum for 11/12 Go and 2 GB for 13 Go. These mobile references do not certify projector compatibility/performance. Go is a system configuration, not a universal ROM. Reference: https://developer.android.com/guide/topics/androidgo . No maximum release was established; RAM alone does not determine it. Boot without video acceleration/projector controls does not satisfy the goal.

## Source investigation

1. Public Rockchip release-3.10/develop-4.4 include RK3128 references, without proof of Canon/Hotack projection changes: https://github.com/rockchip-linux/kernel
2. A 4.19.232 Makefile/RK3128 tree was found, without working Canon proof: https://github.com/nhandt64/android_kernel_rockchip_rk3128
3. Rejected porternguyen/android_device_rockchip_rk3128 as a working base: WIP, vendor/xiaomi/mido, msm8953, Xiaomi fingerprint, and incompatible core_64_bit configuration. References: https://github.com/porternguyen/android_device_rockchip_rk3128/blob/main/device.mk and https://github.com/porternguyen/android_device_rockchip_rk3128/blob/main/full_rk3128.mk
4. Modern GSI vendor-interface compatibility is not demonstrated; do not flash generic ARM GSI. Reference: https://source.android.com/docs/core/tests/vts/gsi

## Proposed sequence

Freeze stock restore kit and user-data backups; document physical Loader entry without Android; preserve bootloader/recovery during initial work. Inventory stock graphics/video/audio/Wi-Fi/Bluetooth and projector paths for brightness, battery, shutdown, keystone. Build a real ARMv7 8.1 AOSP/Lineage device tree, initially preserving kernel/DTB where compatible and documenting missing patches. Validate boot/ADB/physical projection, accelerated video, audio, radios, battery, shutdown/suspend; compare RAM and SmartTube with stock. Evaluate 9/later only afterward. Do not replace bootloader to fix Android without demonstrated need.

No generic ROM was downloaded or newer Android flashed during this historical investigation.
