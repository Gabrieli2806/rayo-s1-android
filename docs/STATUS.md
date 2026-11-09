# Reference checkpoint — November 9, 2026

## Hardware

Canon Rayo S1: RK3128 quad-core Cortex-A7 ARMv7, approximately 1 GB RAM, 8 GB eMMC, Mali-400, AP6330 Wi-Fi/Bluetooth. The factory Linux 3.10.104 kernel is preserved. Stock Android 7.1.2 remains available through normal boot.

## Current trial

AOSP Android 8.1.0 `android-8.1.0_r81`, product `rayos1_oreo-userdebug`. Experimental v12 ramdisk in recovery; 445,988,864-byte system image file on userdata; additional changes in a private overlay. Launcher: `local.rayo.launcher/.MainActivity`.

```text
normal userdata /data/local/tmp/rayo81-system.raw.img
normal userdata /data/rayo81-trial/data/        -> trial /data
normal userdata /data/rayo81-trial/cache/       -> trial /cache
trial /data/local/tmp/rayo-live/               -> active libraries/configuration
```

Original boot and system partitions are preserved. Normal boot remains 7.1.2 and may restore stock recovery.

## Verified on the device

- Complete 8.1 boot and projected menu with correct colors/orientation.
- Boot logo confirmed correct by the owner.
- Integrated ekt2101 panel buttons and clicks.
- Median raw motion-event intervals approximately 11.4 ms on both stock and Oreo.
- DispSync initialization eliminated reliance on one-second waits; the owner confirmed clearly smoother cursor response.
- Wi-Fi enabled, connected, and internet validated; also confirmed by the owner.
- Battery level and charging state readable; battery runtime is not validated.
- SmartTube 32.71 and Aurora Store 4.8.4 installed. Aurora opens; downloading/installing an app through it remains untested.

## Pending

Bluetooth is not fully integrated: its main Android service and tests are missing. Audio, microphone, hardware video decoding/playback, battery runtime, load temperature, suspend, and extended use remain unverified. Do not claim final stability or a working Android version beyond 8.1. The build retains debugging and permissive SELinux.

## Identified components

| Component | Identification |
|---|---|
| `trial-recovery-v12.img` | MD5 `de1b01995022f1ae2819d135b0d51d69`; SHA-256 `4c5fe84a7973e47b297b72afedd4abe5e7bebee52bff0c395ffa3ebe7bba0419` |
| `rayo81-system.raw.img` | MD5 `ac1bc01a07c33e93ff079063bd647acf` |
| Active SurfaceFlinger | MD5 `ab536d6a6826341488f51b65ee0e31c0` |
| Active HWC adapter | MD5 `5b414cb2fa1666325aa1a6d10f2f0015` |
| Correct Oreo animation | MD5 `cb105e7eebf180da65221738ed4dd2b8` |
| Stock recovery | MD5 `b32f971e7dfce13c708d264c6221844d` |
| Preserved kernel | SHA-256 `e98f7640163344ccc5ae196310a4d3d03738adcbd29d055a3e66f1256ce6af94` |

Pointer speed is 2; previous value 0 was saved. The 0.5 animation-scale profile was not enabled. Google services were not installed.
