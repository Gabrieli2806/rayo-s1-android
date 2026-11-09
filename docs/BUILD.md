# Building

## Environment used

Windows, WSL2, Ubuntu 24.04, and AOSP `android-8.1.0_r81`. Sources live on case-sensitive ext4. The original session used a sparse 256 GiB volume and native Linux OUT_DIR. Around 100 GiB free is a practical starting point for sources/builds; actual needs vary by targets and caches.

Legacy tools require AOSP's bundled Python 2.7.5 and JDK 8. Project scripts use host Python 3. Bundled sysroot libncurses/libtinfo 5 libraries provide compatibility for old compilers. Additional host paths and versions are retained in historical backups.

## Sources

With the copied volume: run `bash tools/mount-aosp.sh`, then set `RAYO_AOSP_DIR` to `<repo>/local/aosp-volume/aosp`.

Without it: prepare a case-sensitive Linux directory, install the official `repo` tool, and initialize AOSP using tag `android-8.1.0_r81`. Before synchronization, copy this project's `manifests/aosp-android-8.1.0_r81.xml` to `.repo/manifests/rayo-pinned.xml` and select it with `repo init -m rayo-pinned.xml`. Its relative remote `..` corresponds to official manifest repository `https://android.googlesource.com/platform/manifest`. Do not synchronize directly into an ordinary case-insensitive NTFS directory.

Per-project patches and their JSON series live in `patches/aosp/`. `python3 tools/apply-aosp-patches.py --aosp "$RAYO_AOSP_DIR"` checks whether each patch is pending or already applied; it does not force application to divergent sources.

`bash tools/build-linux.sh libsurfaceflinger hwcomposer.rayo_fluidity validatekeymaps` configures the environment, copies device trees, and builds those targets. It does not flash the device. Other AOSP targets can be supplied explicitly. Defaults build native components rather than a complete installation.

## Verified build details

- The ART mmap fix affects the **Linux host**, allowing dex2oat on newer kernels; it is not a device runtime change.
- Legacy compiler Python wrappers were adjusted to select the appropriate interpreter.
- Host dex2oat requires an existing `ANDROID_DATA` directory.
- Original `systemimage` packaging encountered Python 2 zlib problems. make_ext4fs/mkuserimg packaged the compiled tree, followed by ext4 validation. This packaging limitation did not establish an incomplete framework build.
- Final ramdisk and proprietary component integration are separate steps. `trial-root-v01/` does not contain all binaries/policies; the private kit and source volume preserve them.
- Active overlay changes must be integrated when building a complete image. The standalone system file does not represent the latest working runtime by itself.

## Kernel and HwBinder

Public Rockchip 3.10 sources and a LineageOS Binder backport were used. Revisions, structure-layout checks, and configurations are preserved in scripts, JSON, commit records, and the Linux archive. The kit contains the module tested with the original kernel. Audit imports, banner, and layouts before loading any rebuilt module.

## Launcher

`tools/build-launcher.ps1` uses the current PC's SDK and Java. Install platform android-26 and build-tools 36.0.0, or provide suitable parameters. Signing reads `local/secrets/` or environment variables; no signing password is published. Output: `outputs/Rayo-Dark-TV.apk`.

Successful compilation does not establish working projection, responsiveness, or connectivity; validate those on hardware while preserving known components.

## Reproduce the known recovery

Extract `local/device-kit/runtime/trial-root-v12.tar.gz` into a Linux directory using tar, preserving symlinks. Run `python3 tools/pack-recovery.py --ramdisk <directory> --output <new-image.img>`. The default base is `local/device-kit/original/recovery.img`. The tool rejects an unexpected base and refuses to overwrite an existing output. This ramdisk must reproduce the v12 SHA-256 in STATUS.md. Packing does not write to the projector.

## Current v14 integration

Install NDK 25.2.9519653 and run `tools/build-radio-tools.ps1`. Then `bash tools/prepare-trial-recovery.sh v14` extracts the preserved v12 ramdisk into a new Linux staging directory, copies the current init configuration and helper, and packs a new image without flashing. Existing outputs are never overwritten. To reproduce the released image exactly, extract the kit's `runtime/trial-root-v14.tar.gz` and pass that to the packer; compare against the v14 SHA-256 in STATUS.md.

`tools/patch-bt-vendor.py --input <factory-libbt-vendor.so> --output <new-file>` accepts only the specific original SHA-256. The patched file needs libtg.so; `build-radio-tools.ps1` links it with libdl as a retained dependency. Keep factory bytes separately. Original bcm40183b2.hcd and the original Rockchip OMX/VPU libraries come from this unit's private backup, never from another TV-box image.

The product tree now declares the Bluetooth features, media XML, and a libtg build target. A full AOSP rebuild still requires the separate proprietary-component/ramdisk integration documented above; adding these declarations does not itself reconstruct the final overlay. No full AOSP rebuild was repeated for v14. Tested components were built with the recorded NDK and integrated into the isolated trial.

The active XML enables the stock AVC component plus software fallbacks; `media_codecs.software.xml` preserves the software-only baseline. Source includes retain their AOSP notices. Other hardware codec formats remain undeclared until tested.
