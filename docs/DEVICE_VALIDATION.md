# Hardware validation workflow and November 9 findings

## Reproducible tools

- `python tools/collect-device-validation.py --label baseline`: read-only diagnostics from the known serial/version combinations. Raw reports remain in ignored `local/device-validation/` because they may contain device addresses, application names, or account metadata.
- `tools/build-kernel-probe.ps1`: ARM32 kernel-capability probe, built only. Running it uses temporary anonymous objects and reads Binder protocol versions; it does not mount filesystems or write partitions.
- `tools/build-validation-app.ps1`: builds Rayo Hardware Check, including an original six-second 640×360/24 FPS AVC baseline + AAC test clip. Requires ffmpeg or Python imageio-ffmpeg, SDK platform 26, build-tools 36.0.0, and the private launcher signing key.
- `tools/build-radio-tools.ps1`: builds the bounded radio file-capability helper, Bluetooth compatibility library, and load-only vendor-library probe using NDK 25.2.9519653.
- `tools/patch-bt-vendor.py`: accepts only the recorded factory Bluetooth library SHA-256. Changes two equal-length ELF strings without changing file size or offsets. Keep the original separately.
- `bash tools/prepare-trial-recovery.sh v14`: prepares recovery from the v12 ramdisk, new init configuration, and rebuilt helper. It never flashes the device. See the current checkpoint notes before installing.

## Audio and software video

The owner confirmed the integrated speaker's 440 Hz tone and the projected original color/motion AVC/AAC clip with sound. Initial enumeration returned zero relevant decoders because media_codecs.xml and its included software-codec XML files were absent from the active overlay. Libraries were already present. Adding the matching Android 8.1 declarations and restarting only media services enabled successful AVC/AAC playback.

Source includes are from official AOSP `android-8.1.0_r81`, frameworks/av/media/libstagefright/data, with their original notices retained. These software components do not demonstrate hardware acceleration.

## Rockchip AVC path

The factory libstagefrighthw, libOMX_Core, resource manager, VPU decoder/encoder, librk_vpuapi, libvpu, and gralloc helper passed isolated RTLD_NOW load tests against current libraries before integration. Previously absent files were added to the trial overlay while media services were stopped; no stock partition was changed.

Only the stock AVC decoder declaration was enabled alongside the software fallback. Runtime logs selected `OMX.rk.video_decoder.avc` and the six-second test reached VIDEO_COMPLETE. ACodec logged a color-range/dataspace change, so correct physical colors must be checked, not inferred from completion. Broader codec formats, HD playback, seeking, long playback, thermal load, and protected streaming remain separate tests.

## Bluetooth

The Bluetooth APK and HAL existed, but the framework feature declaration was absent, so bluetooth_manager did not start. Adding the declaration and restarting the framework exposed the next issues:

1. The stock library needs UART/rfkill/low-power-node permissions and NET_ADMIN. The current HAL remains UID bluetooth; only its root-owned executable receives a limited file-capability bind mount, analogous to Wi-Fi. General userdata mount flags are unchanged.
2. The original vendor library calls timer_settime with a null, uninitialized timer before completing setup. A temporary process-local diagnostic guard isolated this crash. That root diagnostic process was terminated; it is not the persistent implementation.
3. The integration uses libtg.so's `rayo_timerset`: invalid null handles return EINVAL; valid timers still call Bionic timer_settime unchanged. Only the known factory ELF is patched: timer_settime import becomes rayo_timerset, and libdl.so dependency becomes libtg.so; libtg.so retains a libdl.so dependency. No global libc replacement or LD_PRELOAD is required.
4. The actual controller identified the BCM40183 family. Original bcm40183b2.hcd was added; logs confirm successful firmware loading and configuration. The unpatched factory binary remains as libbt-vendor.factory.so.

The normal non-root HAL reached Bluetooth ON, and the owner confirmed nearby devices appeared. No accessory was available for pairing. Pairing, reconnect, HID input, A2DP audio, BLE behavior, low-power operation, and stability are not established merely by ON/discovery. Do not pair unknown devices or publish raw addresses/logs.

## Persistence and recovery

The first v13 reboot exposed a real race: HALs were already executing before their file capabilities were assigned. Wi-Fi/Bluetooth did not acquire those privileges until re-executed. v13 is not the release checkpoint.

v14 explicitly stops both radio HALs before capability setup and starts them after post-fs-data node permissions are ready. After a complete recovery-slot reboot, Wi-Fi UID 1010 had CapEff 0x3000 (NET_ADMIN/NET_RAW), Bluetooth UID 1002 had CapEff 0x1000 (NET_ADMIN), Wi-Fi had Android-validated internet, and Bluetooth was ON. These checks were collected before any manual post-boot repair. The owner then confirmed correct projected color/motion and sound in the repeated Rockchip-decoded sample. The factory kernel and boot-image layout remained unchanged.

Keep v12 kit v0.1 and original recovery as rollback assets. Current v14 is kit v0.2. None of these results establish MaskROM recovery, full long-term stability, or a permanent ROM installation.
