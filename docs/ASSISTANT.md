# Install and modding assistant

This file is a playbook for an AI coding assistant (Claude Code or a similar
agent with shell, ADB and file access) that helps someone install GabOS on a
Canon Rayo S1, keep it updated, or change it. The person may know little about
Android internals. The assistant does the technical work, explains each step in
plain words, and asks before anything that cannot be undone.

A person can start a session with a request such as:

> Read `docs/ASSISTANT.md` in this repository and help me with my Canon Rayo S1.
> I want to install GabOS (or: update it, or: change something in it).

## 1. Rules for the assistant

These are not optional. They protect a device that has no other recovery path.

- **Back up first.** Before any write, read every partition the tools touch
  (at least boot, recovery, misc, and a full userdata file list) and record
  MD5/SHA-256 checksums on the host, in `local/` (Git-ignored). Read back
  every write and compare checksums.
- **Never erase or format storage as a diagnostic step.** Never write
  uboot, trust, kernel, resource or the factory system partition.
- **Never edit the checksum constants** in the tools to make another unit
  "match". A mismatch means the unit differs; stop and explain.
- **Never bypass the HwBinder guard**, force-load kernel modules, or flash
  an image built for another board or TV box.
- **Keep a rescue slot.** At all times one slot must hold a system that boots
  with root ADB (today the Android 8.1 v14 rescue in recovery). Never write
  boot and recovery in the same step.
- **Keep the 768 MiB userdata reserve** that the tools enforce.
- **Secrets stay local.** Signing keys, Wi-Fi passwords, account files and
  device identifiers go in `local/secrets/` and never into Git, logs or
  releases. Redact MAC addresses and serials in anything shared.
- **Ask before** each irreversible or outward-facing action: writing a
  partition, deleting old systems, publishing a release, pushing to a remote.
- **Report honestly.** "It booted" is not "it projects", and "Bluetooth is
  on" is not "pairing works". Ask the person to confirm what only they can
  see or hear (projection, colours, sound, remote control).

Repository conventions also apply: read `AGENTS.md`, then `docs/HANDOFF.md`
for the current state. Documentation and code comments are in English; talk
to the person in their language.

## 2. First contact: identify the device and the person's goal

1. Ask what they want: install GabOS on a stock projector, update an
   existing GabOS, or change/extend GabOS (modding).
2. Have them enable USB debugging if the projector runs stock Android
   (Settings, About, tap Build number seven times, then Developer options).
   Connect one projector only.
3. Run and record:
   ```
   adb devices -l
   adb shell getprop ro.build.version.release
   adb shell getprop ro.build.display.id
   adb shell getprop ro.gabos.version
   adb shell id
   adb shell ls -l /dev/block/platform/1021c000.rksdmmc/by-name/
   ```
4. Classify:

| What you see | State | Next |
|---|---|---|
| Android 7.1.2, a real serial | Stock projector | Section 3 |
| Android 8.1.0, serial `0123456789ABCDEF` | v14 rescue (or v14 as normal boot) | Section 3 or 4, depending on boot/recovery MD5 |
| Android 12, `ro.gabos.version` set | GabOS installed | Section 4 (update) or 5 (modding) |
| Anything else | Unknown | Stop and ask; do not write anything |

## 3. Installing on a stock projector

Read `docs/INSTALL.md` completely before starting, especially **Current
limitations**. Today the full path works only on the original development
unit: the boot bootstrap carries that unit's userdata UUID, and the packager
needs that unit's device kit. Be clear with the person about this.

What the assistant can always do safely on another unit:

1. **Backup** (`docs/INSTALL.md` section 3): read every partition to the
   host with checksums. This is the most valuable step; do it even if nothing
   else follows.
2. **Compare** the factory recovery, boot and kernel checksums with the ones
   listed in `docs/INSTALL.md` (limitation 2). If they match, the unit runs
   the same factory firmware and the porting work below is small. If they do
   not, stop: a different kernel is a separate porting project.
3. **Host setup, source sync and builds** (`docs/INSTALL.md` sections 1, 5,
   6, 7). These touch only the computer.

Porting work needed before a second unit can install (offer it to the
person as a project, one item at a time, each with its own test):

- Replace the compiled-in userdata UUID in
  `work/android12/device/canon/rayos1_12_lab/early_init_bootstrap.c` with a
  per-unit record written into the ramdisk by the packager.
- Make the tools read the unit's own factory checksums from a per-unit
  profile in `local/` instead of the constants (keep refusing on mismatch
  against that profile).
- Rebuild the device kit archives from the new unit's own backup.
- Recreate a rescue system for the recovery slot from the new unit's files,
  and test that it boots, projects and gives root ADB before anything else.

Only after a rehearsal in the recovery slot (`tools/trial-android12-framework.py`)
has booted, projected and returned cleanly may GabOS be promoted to normal boot
(`tools/promote-android12.py`).

## 4. Updating an installed GabOS

1. Check the layout: boot MD5 is a known GabOS image, recovery MD5 is the v14
   rescue (`7a3ac489...`). `tools/update-android12.py` checks this too.
2. Build the apps (`docs/INSTALL.md` section 7) and package a candidate
   (section 8). Keep the data image UUID: use `--reuse-data-manifest` with the
   installed candidate's manifest.
3. Install with `python tools/update-android12.py --candidate <dir> --execute`.
   It restarts into the rescue, replaces the system file, writes and reads
   back the boot image, and waits for a completed GabOS boot. If GabOS does not
   finish booting, its watchdog returns to the rescue within 15 minutes.
4. Ask the person to check projection, sound, Wi-Fi and their remote.
5. Record the result in `docs/HANDOFF.md`.

App-only changes (launcher, GabOS apps) can be tried first without a new
image: `adb push` the APK and `adb shell pm install -r`. The next image must
still include them.

## 5. Modding guide

Where things live, and how to test each kind of change:

| Change | Files | Build | Test |
|---|---|---|---|
| Home, GabOS Settings, keyboard, setup wizard | `work/rayo-tv/`, shared look in `work/gabos-ui/GabosUi.java` | `tools/build-launcher.ps1` | `pm install -r`, then screenshots and the person's eyes |
| GabOS apps (Media, Diagnostics, Share) | `work/gabos-media`, `work/gabos-diagnostics`, `work/gabos-share` | `tools/build-gabos-app.ps1`, `tools/build-gabos-share.ps1` | Same |
| Preinstalled third-party apps | `manifests/gabos-optional-apps.json`, `manifests/gabos-partner-apps.json` | Packager | New candidate |
| First-boot settings and grants | `work/android12/framework/gabos-boot.sh` | Packager | New candidate, check `logcat` and `settings get` |
| System properties, staged files, build type | `tools/prepare-android12-framework.py` | Packager | New candidate |
| Video decoders | `work/android12/device/canon/rayos1_12_lab/omx_plugin_bridge.cpp` | `tools/build-android12-tv.sh librayo12_omx_plugin` | Diagnostics "Video decoders" test, then real playback |
| Boot bootstrap, watchdog | `work/android12/device/canon/rayos1_12_lab/early_init_bootstrap.c` | Native lab build | Recovery-slot rehearsal only |

Constraints that come from the hardware and must stay (see `AGENTS.md`):
original 3.10 kernel with its HwBinder guards, Rockchip Mali and ION, the
factory HWC adapter, ARMv7 32-bit binaries, 1 GB of RAM, and a 1280x720 DLP
panel. Prefer small, measurable changes and check memory (`dumpsys meminfo`)
after each one.

Good first projects for a new contributor:

- A GabOS-styled page for one more native setting (see
  `work/rayo-tv/SystemPages.java` for the pattern).
- A new tile or theme option in GabOS Home.
- A codec, input-map (`.kl`/`.idc`) or remote-control improvement, measured
  with `getevent` and logs.

## 6. When something goes wrong

- GabOS does not finish booting: wait; the watchdog restarts into the rescue.
  Then reinstall the previous candidate with `tools/update-android12.py`, or
  roll back with `python tools/promote-android12.py --action rollback --execute`.
- The rescue does not start: the factory recovery and every factory image are
  on the host (`local/device-kit/original/`) and in `/realdata/gabos-rescue/`;
  see `docs/RECOVERY.md`.
- A tool refuses with a checksum or layout message: do not work around it.
  Read the message, compare with the backup records, and explain to the person
  what differs.
