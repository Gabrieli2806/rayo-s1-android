# Android 8.1 experimental boot history

Intermediate checkpoint, superseded by ../../STATUS.md.

AOSP android-8.1.0_r81 completed boot and opened Rayo Dark. USB screenshot showed menu/icons/battery at 100%; SmartTube 32.71 ARMv7 was installed in private trial data, with playback untested.

Initial full boot exposed stock compositor failures and missing minimal-image components. Intermediate tests used basic AOSP composition, 8.1 graphics adapters, one Mali instance, and buffer-allocation adaptation to avoid incompatible cross-process framebuffer handles. Fonts, libcompiler_rt, and software keymaster were added. Later tests returned to factory HWC through an adapter; original kernel/Mali/gralloc retained.

The initial photo showed repeated segments/washed-out colors despite correct internal screenshot. LCD reported RGB888/virtual width 960 versus Android 32-bit buffers/width 1280. Temporary format changes removed projection; stock boot was requested for recovery/comparison. This failed stage was not functional projection.

Original recovery was written/read through Loader and matched backup. Stock 7.1.2 booted and owner confirmed projection returned; recovery restored automatically, MD5 b32f971e7dfce13c708d264c6221844d. MaskROM/physical emergency entry remained untested.

Trial uses recovery and a userdata system file/private data; stock boot/system/kernel retained. Experimental HwBinder adds hwbinder/vndbinder, preserving original binder. Scatter-gather/domain separation tests passed, not long-term service validation.

Factory HWC has four extra callbacks shifting methods 16 bytes, 208-byte layers, and non-AOSP list header. Adapter translates layouts with static checks, preserving binary. It booted with Rayo Dark/SmartTube; LCD reported width 1280, ARGB888, position 0,0, scale 0.67/0.66 matching stock. Historical menu-adaptador-grafico.png captured it. Owner confirmed correct projected menu/colors. Trial lockscreen was disabled; persistent HwBinder permission fix prepared, unattended boot still pending.

ABI reference: https://github.com/TinkerBoard-Android/rockchip-android-rk-hardware-libhardware/blob/8467d9a13c64d99ade4a730d1575c2b226fa4d52/include/hardware/hwcomposer.h . Offsets were checked against this unit's binary; public code did not replace it.

Pending then: repeated boots/physical checks, controls, Wi-Fi network permissions, Bluetooth feature declaration, audio/video, discharge/runtime. Recovery v09 was written/verified for next boot: MD5 22e80fadf6772275ca55de28820074f7; SHA-256 cc5bb69a2c2b98ad1fbde463bf1af332c06e53c809c193e5a396df144d7dc01a. It was not a final distribution image.
