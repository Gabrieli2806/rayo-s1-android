# Provenance and licenses

This repository combines adaptation work with code and references from several projects. There is no blanket license overriding their individual terms. Preserve each file's notices.

- AOSP Android 8.1: pinned `manifests/aosp-android-8.1.0_r81.xml`; Android Open Source Project, Apache 2.0 and other per-project licenses.
- Rockchip kernel: public `rockchip-linux/kernel`, release-3.10 branch, plus configuration/checks in the Linux archive. Linux and derived Binder module components retain GPLv2 and their notices.
- Binder backport: `LineageOS/android_kernel_samsung_msm8974`, revision `390ab6ec6ff941f6407f91459612887faefa86e8`; adaptation recorded in `prepare_hwbinder_port.py` and associated files.
- Rockchip HWC reference: `TinkerBoard-Android/rockchip-android-rk-hardware-libhardware`, revision `8467d9a13c64d99ade4a730d1575c2b226fa4d52`, checked against this unit's binary.
- Rockchip U-Boot: revision `1ab854d6bb36a46c10ef93f36a8040145faceb25`, used to investigate image hashing/alignment.
- Canon/Rockchip proprietary components, firmware, and backups are unit-specific private kit assets outside Git history. This repository grants no general manufacturer redistribution license.
- SmartTube and Aurora Store are independent applications. Aurora 4.8.4 came from F-Droid; provenance and signature details are retained in historical reports.
- The logo and launcher artwork belong to the owner's project; do not assume AOSP licensing applies to them.

Historical scripts and manifests retain further URLs and revisions. Host credentials and signing keys are not shared source.
