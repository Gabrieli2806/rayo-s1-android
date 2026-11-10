# Continuing on another PC

## Complete transfer, without downloading AOSP again

Copy **this entire folder, including `local/`**. Git does not transfer that directory. It contains:

- `local/legacy-workspace/`: original workspace copy, including scripts, experiments, images, backups, and APKs.
- `local/legacy-workspace/work/android81/build-volume.ext4`: independent sparse ext4 volume, 256 GiB logical size, containing synchronized sources. Physical allocation is much smaller; preserve sparseness when copying.
- `local/portable/linux-native-build.tar.gz`: native Linux work archive, including OUT_DIR, built tools, and kernel sources used. This historical cache is optional for a fresh build.
- `local/portable/rayo81-live-overlay.tar.gz`: export of the active device libraries and configuration.
- `local/secrets/`: launcher signing key and local password file. Keep these outside Git and transfer privately to retain the installed application's signing identity.
- `local/documentation-before-english/`: original documentation snapshot retained privately for historical comparison.

Use enough free space and a sparse-aware Windows copy utility, such as modern Robocopy with `/SPARSE:Y`. Avoid FAT32's file-size limit. Do not copy an ext4 image while it is mounted and being modified.

Install WSL2/Ubuntu and host tools. `bash tools/mount-aosp.sh` mounts the copied volume without formatting it. Recommended AOSP location: `local/aosp-volume/aosp`.

To recover the historical Linux cache, extract `linux-native-build.tar.gz` into `/var/tmp/codex-rayo-android81/`, preserving its `work/` subdirectory. Build files often contain original absolute paths; cache regeneration may be necessary. Prefer a fresh OUT_DIR after changing paths or machines.

## GitHub and fresh downloads

Clone the private repository and run `tools/download-device-kit.ps1`. The private release includes this unit's images, original backups, Rockchip tools, and current overlay. The script verifies SHA-256 before extraction.

Obtain AOSP again through the pinned manifest in `manifests/`; see [BUILD.md](BUILD.md). Upstream sources and compiled caches do not belong in Git history.

The signing key is not in the release. Without it, Android development and installation of the kit's pre-signed APKs remain possible; updating the launcher with its existing identity requires privately transferring `local/secrets/`.

## This kit is not a final ROM

The kit resumes this experiment on **the same unit**. It does not copy user accounts or Wi-Fi passwords. Existing trial applications/settings stay on the device; a clean installation requires separate initialization and validation. Do not erase userdata for a PC migration.

## New Android 12 research workspace

The fresh host's partial Android 12 checkout is on native WSL ext4 at `~/android/rayo12`, not inside Git or the Windows `local/` directory. It can be recreated from the tagged manifest/source selection and selected-project snapshot in `manifests/`. It is not a complete source sync or a board ROM. See GOOGLE_TV_12_PORT.md. The official reference image, extracted Google packages, and locally built probes are in ignored `local/`/`outputs/`; transfer them separately or reproduce the inspection using the recorded Google archive/hash. No signing key is included in that reference inventory.
