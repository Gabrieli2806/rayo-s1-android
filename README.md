# Rayo S1 — experimental Android 8.1

AOSP `android-8.1.0_r81` adaptation for **this Canon Rayo S1 unit**: RK3128 ARMv7, Mali-400, and approximately 1 GB RAM. Includes Rayo Dark TV, the Rockchip compositor adapter, HwBinder, input and connectivity fixes, tools, and documentation for continuing on another PC.

**Verified on November 9, 2026, checkpoint v14:** Android 8.1 boots, projects correctly, supports the integrated buttons, and reconnects Wi-Fi with validated internet. The owner confirmed speaker audio and a projected AVC/AAC sample using the Rockchip decoder after a complete reboot. Bluetooth enables automatically and the owner confirmed nearby devices appear; pairing remains untested because no accessory was available. Aurora Store 4.8.4 is installed. Extended stability, power, and broader media validation remain pending.

**Android 8.1 v14 is now the normal startup.** It uses the tested system image file, overlay, and private data on userdata. The previous 7.1.2 system partition remains preserved and the original independent recovery is restored. The previous boot/system are backed up in the private `previous-main-7.1.2` release. ADB still reports `recovery` because of its static recovery-derived adbd; identify Android and boot configuration rather than inferring the selected partition from that banner.

## Repository language

Documentation, development notes, new code comments, and the project foundation are maintained in **English**. Existing OEM identifiers, artifact names, and external interfaces retain their exact spelling. Conversation with the owner may remain in Spanish. This policy does not change the device UI language.

## Layout

| Path | Contents |
|---|---|
| `work/android81/device/` | Product configuration, graphics adapters, native probes |
| `work/android81/hwbinder-module/` | Adapted Binder module and ABI checks |
| `work/android81/trial-root-v01/` | Latest ramdisk source/configuration; binaries are in the private kit |
| `work/android81/*.py`, `*.sh`, `*.ps1` | Historical tools and experiments retained for investigation |
| `work/rayo-tv/` | Rayo Dark TV sources and resources |
| `work/rayo-launcher/`, `work/rayo-helper/` | Initial launcher and inspector |
| `work/rayo-validation/`, `work/probes/` | Hardware validation app and bounded native probes |
| `assets/branding/` | Owner-provided logo source |
| `manifests/`, `patches/` | Pinned revisions and AOSP changes |
| `tools/` | Portable entry points |
| `docs/` | Current status, build, recovery, portability, and upgrade assessment |
| `local/` | Complete backups, firmware, AOSP volume, Linux cache, private keys; **excluded from Git** |

## Continue on another PC

1. Clone this private repository using an authorized account.
2. Install Git, GitHub CLI, Python 3, and Android Platform Tools. Building also requires WSL2/Ubuntu, SDK/NDK, and Java; see [BUILD.md](docs/BUILD.md).
3. Download the device kit using `powershell -File tools/download-device-kit.ps1`, or transfer `local/` from the original PC. Device-specific binaries are release assets rather than Git history.
4. Run `powershell -File tools/rayo.ps1 -Action Doctor`, then `-Action Status` with the projector connected.
5. Read [STATUS.md](docs/STATUS.md), [RECOVERY.md](docs/RECOVERY.md), and [PORTABILITY.md](docs/PORTABILITY.md) before writing anything.

To start the known trial from stock Android with the kit available: `powershell -File tools/rayo.ps1 -Action StartTrial`. This checks the version and existing trial files before writing only recovery. It does not initialize a new system or erase user data by default.

## Essential references

- [Android 12 and the original Google TV UI: inspected reference and port preparation](docs/GOOGLE_TV_12_PORT.md)

- [Current status and pending validation](docs/STATUS.md)
- [Build instructions](docs/BUILD.md)
- [Backup and recovery](docs/RECOVERY.md)
- [Moving to another PC](docs/PORTABILITY.md)
- [Upgrade feasibility and hardware limits](docs/UPGRADE_FEASIBILITY.md)
- [Android 12 TV source audit and porting requirements](docs/ANDROID12_TV_ASSESSMENT.md)
- [Hardware validation and v14 integration](docs/DEVICE_VALIDATION.md)
- [Previous-system backup and proposed primary boot](docs/PRIMARY_BOOT_PLAN.md)
- [Provenance and licenses](docs/THIRD_PARTY.md)
- [Migration verification](docs/VALIDATION.md)
- [Agent instructions](AGENTS.md), also linked from [agent.md](agent.md)

Legacy scripts retain original-session absolute paths. Prefer `tools/` and the current guides; consult legacy scripts as historical records. Reports in `docs/history/` describe earlier stages and do not supersede `docs/STATUS.md`.

This is experimental firmware for this unit, not a stable or universal RK3128 ROM. Google services are not integrated. Original firmware, proprietary binaries, APKs, and signing keys are kept separate from versioned source.

The current private kit is `device-kit-v0.2` (v14). The original v12 kit remains available as `device-kit-v0.1`; preserve both. If an older `local/device-kit` exists, retain it under a different name before downloading the new kit. Android 12 TV has been investigated, not built or installed; the audit found a substantial board/kernel/graphics port rather than a small firmware swap.
