# Rayo Dark v0.1: first modification

Applied November 8, 2026, on Android 7.1.2 / RK3128 / Rayo RWBT-KOR-V1.2N. Historical stage.

Integrated `/system/app/RayoDark/RayoDark.apk`, root-owned, mode 0644, SELinux system_file. Selected as user 0 default HOME; retained Canon launcher through the return button. Normal reboot completed with Rayo Dark visible/default. Android recognized a system app; the initial /data install remained as an update. System was remounted read-only. Android, kernel, bootloader, projector drivers, and Canon services were not replaced/disabled.

`system-rayo-dark-v0.1.img` adds the APK under /app/RayoDark/ to the original system image. Original backup retained. Read-only e2fsck passed; permissions/context and extracted APK equality were checked; adjacent SHA-256 saved. The 2,147,483,648-byte ext4 partition image is not complete firmware or a local-update OTA ZIP. Full-image flashing/boot was untested; equivalent installed-system APK changes were tested. HOME selection lives in userdata and may require selection after reset.

## Restore Canon HOME

The return button opens Canon immediately. To restore the default:

```text
adb shell cmd package set-home-activity --user 0 com.htc.htcrayolauncher/com.htc.activity.HomeActivity
adb shell am start -n com.htc.htcrayolauncher/com.htc.activity.HomeActivity
```

Historical `restaurar-menu-canon.ps1` checks the model, restores HOME, and leaves Rayo Dark installed. Pending at this stage: Loader entry/exit/restoration, omitted boot-area backups, updater/recovery formats/signatures, and newer Android source/driver investigation. Loader/MaskROM were not entered during this change.
