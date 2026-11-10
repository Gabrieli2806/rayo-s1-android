# Reference checkpoint — November 10, 2026

## Android 12 native research update

The full pinned Android 12 checkout and AOSP TV product file build completed. Cortex-A7/Binder32 native runtime libraries execute on this device's original kernel. Android 12 EGL/GLES offscreen rendering, local allocator/mapper and the new GraphicBuffer client using the existing 8.1 allocator transport passed; the factory HWC loaded without opening display hardware. The fifth temporary recovery-slot diagnostic reached source-built Android 12 init, loaded policy 30 and responded over USB with release 12 / SDK 31. Its complete check did not pass because the reused rescue ADB dropped to UID 2000. The 180-second watchdog returned to v14 and original independent recovery was restored and hash-verified. Normal boot remains 8.1 v14. No full Android 12 framework/Google TV boot or physical Android 12 projection is verified. See [ANDROID12_NATIVE_LAB.md](ANDROID12_NATIVE_LAB.md).

## Hardware

Canon Rayo S1: RK3128 quad-core Cortex-A7 ARMv7, approximately 1 GB RAM, 8 GB eMMC, Mali-400, AP6330 Wi-Fi/Bluetooth. The factory Linux 3.10.104 kernel is preserved. Android 8.1 v14 is the normal boot. The previous Android 7.1.2 system is preserved and can be used again by restoring its verified boot image.

## Current trial

AOSP Android 8.1.0 `android-8.1.0_r81`, product `rayos1_oreo-userdebug`. Experimental v14 image in boot; original independent recovery restored; 445,988,864-byte system image file on userdata; additional changes in a private overlay. Launcher: `local.rayo.launcher/.MainActivity`. Kit v0.2 captures this state; kit v0.1 preserves the earlier v12 checkpoint.

```text
normal userdata /data/local/tmp/rayo81-system.raw.img
normal userdata /data/rayo81-trial/data/        -> trial /data
normal userdata /data/rayo81-trial/cache/       -> trial /cache
trial /data/local/tmp/rayo-live/               -> active libraries/configuration
```

The previous system partition is preserved; boot now contains v14. Original independent recovery is restored. Normal reboot starts 8.1. Returning to 7.1.2 requires restoring the verified previous boot first. The owner explicitly accepted this experimental default and confirmed the post-installation physical checks. See PRIMARY_BOOT_PLAN.md and manifests/primary-boot-install.json.

## Verified on the device

- Complete 8.1 boot and projected menu with correct colors/orientation.
- Boot logo confirmed correct by the owner.
- Integrated ekt2101 panel buttons and clicks.
- Median raw motion-event intervals approximately 11.4 ms on both stock and Oreo.
- DispSync initialization eliminated reliance on one-second waits; the owner confirmed clearly smoother cursor response.
- Wi-Fi enabled, connected, and internet validated; also confirmed by the owner.
- Battery level and charging state readable; battery runtime is not validated.
- SmartTube 32.71 and Aurora Store 4.8.4 installed. Aurora opens; downloading/installing an app through it remains untested.
- Speaker audio physically confirmed with a generated 440 Hz tone.
- Six-second 640×360 AVC/AAC motion/color sample physically confirmed with sound. Runtime selected OMX.rk.video_decoder.avc, not the software fallback. Also confirmed after the full v14 reboot.
- Bluetooth framework/HAL integrated, firmware loaded, ON automatically after v14 reboot; owner confirmed nearby devices appear. HAL remains UID 1002 with only NET_ADMIN; no temporary root HAL remains.
- Wi-Fi automatically reconnected with Android-validated internet after v14 reboot. HAL UID 1010 retained NET_ADMIN/NET_RAW. No manual post-boot capability repair was used for the successful v14 test.

## Pending

Bluetooth pairing/reconnect/HID/A2DP/BLE and power behavior remain untested; the owner had no accessory available. Microphone, broader video formats/resolutions, HD/long playback, seeking, SmartTube playback, protected streaming, battery runtime, load temperature, suspend, and extended use remain unverified. Do not claim final stability or a working Android version beyond 8.1. The build retains debugging and permissive SELinux.

## Identified components

| Component | Identification |
|---|---|
| `trial-recovery-v14.img` | MD5 `7a3ac489b05f0f2e9cde16280551511a`; SHA-256 `9e7c48594fae621e74a37ea4bd427fa3284f0ab578cbf8ff94187bb654278c2d` |
| Earlier `trial-recovery-v12.img` | MD5 `de1b01995022f1ae2819d135b0d51d69`; SHA-256 `4c5fe84a7973e47b297b72afedd4abe5e7bebee52bff0c395ffa3ebe7bba0419` |
| `rayo81-system.raw.img` | MD5 `ac1bc01a07c33e93ff079063bd647acf` |
| Active SurfaceFlinger | MD5 `ab536d6a6826341488f51b65ee0e31c0` |
| Active HWC adapter | MD5 `5b414cb2fa1666325aa1a6d10f2f0015` |
| Correct Oreo animation | MD5 `cb105e7eebf180da65221738ed4dd2b8` |
| Stock recovery | MD5 `b32f971e7dfce13c708d264c6221844d` |
| Preserved kernel | SHA-256 `e98f7640163344ccc5ae196310a4d3d03738adcbd29d055a3e66f1256ce6af94` |
| Patched BT vendor library | MD5 `a098a2c5f41758f00198e25fe6550c1a`; SHA-256 `eae7d30b7ef73a7471c505085980b6b8a5e955e2fac238e8e6960dc5e83d0e0f` |
| Active libtg.so | MD5 `727917f74ddb111c7aea0b16733127e9` |
| Stock video plugin libstagefrighthw.so | MD5 `a63974573f2eebaa48445ff606643376` |
| Active codec configuration | MD5 `0014166bd070664d7df9da0f4016c61a` |

Pointer speed is 2; previous value 0 was saved. The 0.5 animation-scale profile was not enabled. Google services were not installed.

One post-v14 diagnostic snapshot reported about 621,468 KiB available including reclaimable cache, with 182,984 KiB immediately free. This is a point-in-time measurement, not an app workload benchmark. See DEVICE_VALIDATION.md for integration details and ANDROID12_TV_ASSESSMENT.md for the separate Android 12 research result.

## Normal-boot promotion

Boot v14 and original recovery were written/read back and a normal reboot completed Android 8.1.0 (ro.bootmode=emmc). Wi-Fi internet and Bluetooth ON were verified. The owner confirmed the repeated hardware-decoded video, sound, colors, and panel. Cold power-on, long-use validation, pairing and independent physical rescue are still pending. The previous 7.1.2 system was not erased.

## Android 12 / original Google TV research

The owner now targets Android 12 with the original Google TV Home/menu. Offline reference inspection confirmed ARM32 packages; a selected AOSP 12 source subset and an ARM32/API31 build-only factory HWC adapter probe were prepared. The existing static ABI assertions passed against real Android 12 headers. See GOOGLE_TV_12_PORT.md for evidence and remaining gates. This does not change device validation: v14 remains the working OS, with no Android 12 or Google applications installed during this research.
