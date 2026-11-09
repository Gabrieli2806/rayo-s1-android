# Migration verification — 2026-11-09

- Workspace/output copies: Robocopy reported no failures; the AOSP volume is an independent sparse copy.
- All eight modified AOSP projects pass reverse patch checks against the copied, read-only mounted source volume.
- Portable packaging reproduces recovery v12 byte for byte: SHA-256 `4c5fe84a7973e47b297b72afedd4abe5e7bebee52bff0c395ffa3ebe7bba0419`.
- Rayo Dark was rebuilt and its signature verified from the new folder.
- PowerShell scripts parsed successfully; Bash scripts passed `bash -n`. Read-only ADB queries confirmed Android 8.1, trial flag 1, and completed boot flag 1.
- Device kit ZIP entries passed CRC verification. Its internal manifest records file sizes, SHA-256, and MD5; the archive SHA-256 is pinned in `manifests/device-kit.json`.
- Linux cache: complete gzip decompression and CRC verification succeeded, covering 39,330,508,800 bytes. One WSL/DrvFS reader reported an I/O error, while complete Windows verification succeeded. If this recurs during transfer, copy the archive to native ext4 before extraction.
- The copied source volume passed all five `e2fsck -fn` phases without errors or modifications.
- A fresh GitHub clone was checked. The published release asset's remote SHA-256 matches the local descriptor.

No projector writes or reboots were performed during migration. Backup validation does not replace pending hardware tests in STATUS.md. A complete AOSP rebuild was not repeated; source patches and known recovery packaging were verified.

## Subsequent hardware work, after migration

Later work did write/read back recovery v13 and v14 and perform recovery-slot reboots; the no-write statement above applies only to the migration phase. v13 revealed a radio-HAL capability race. v14 corrected startup ordering, and a fresh full boot had correct radio UIDs/capabilities, validated Wi-Fi internet, Bluetooth ON, and owner-confirmed Rockchip AVC/AAC projection/audio. Original boot/system/kernel remained preserved. Pairing and extended stability are still pending. See DEVICE_VALIDATION.md.
