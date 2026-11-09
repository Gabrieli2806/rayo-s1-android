# Device responsiveness and Wi-Fi tests — November 9, 2026

Android 8.1 completed boot through recovery v12. Owner confirmed working integrated buttons and correct logo. ekt2101 median raw-event interval was approximately 11.4 ms on both stock/Oreo, ruling out reduced raw frequency as the main difference.

HWC1 SurfaceFlinger lacked mPrimaryDispSync.init(...), producing repeated one-second VSYNC waits. Equivalent HWC2 initialization was added/rebuilt. Hardware synchronization remains debug.sf.no_hw_vsync=0; owner confirmed clearly improved response. Output remains 1280×720, declared 59 Hz; that does not establish 59 FPS in all apps.

Wi-Fi enabled/connected/internet validated by Android and owner. Aurora Store 4.8.4 code 76 installed from F-Droid and opened; no GMS; store app-download/install untested.

Active changes: compatible Generic/rk29-keypad; HWC list reuse/physical-density fallback; pointer speed 2 with previous 0 saved; G animation without extra rotation; DispSync initialization; original fw_RK903_ag.bin/Wi-Fi config; wpa_supplicant on wlan0; HAL network permissions and Wi-Fi feature declaration. NET_ADMIN/NET_RAW require a limited executable-specific mount because trial vendor binaries are userdata-backed/nosuid. General userdata options remain unchanged; integrate properly for a full image.

Animation scale 0.5 was not applied. Prior components retained for rollback; original kernel/boot/system preserved. Trial uses recovery/private userdata.

| Component | Identification |
|---|---|
| Recovery v12 | MD5 de1b01995022f1ae2819d135b0d51d69 |
| Recovery v12 | SHA-256 4c5fe84a7973e47b297b72afedd4abe5e7bebee52bff0c395ffa3ebe7bba0419 |
| Corrected SurfaceFlinger | MD5 ab536d6a6826341488f51b65ee0e31c0 |
| Fluidity HWC | MD5 5b414cb2fa1666325aa1a6d10f2f0015 |
| Aurora certificate | SHA-256 5c83c7672b929955dc0a1db89a5e6ae4389e2eae7ec939956041694e5815f532 |

Aurora source: https://f-droid.org/packages/com.aurora.store/ , com.aurora.store_76.apk. Bluetooth hardware exists but main Android service is not integrated. Audio, accelerated video, battery runtime, OEM-specific controls, prolonged use remain pending. Debug access/permissive SELinux remain experimental.
