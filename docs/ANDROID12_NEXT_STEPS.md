# Android 12 continuation checklist

Read [HANDOFF.md](HANDOFF.md) first for exact device guards, artifact paths,
commands, private-cache requirements and the owner's latest UI decisions.

## Priority 0: Google TV candidate `gabos-gtv-01`

The owner's current target is a stable Android 12 with Google TV Home and working
Google services, then GabOS as the only system. See the Google TV section of
[HANDOFF.md](HANDOFF.md). Open gates, none passed yet:

1. Device space check and first guarded boot of `gabos-gtv-01` (1200 MiB system,
   1024 MiB grown data, 3600 s watchdog).
2. Runtime acceptance of host-compiled odex files; cold/warm launch timing versus
   the interpreted baseline.
3. Google TV Home projected and navigable; fallback to GabOS Home works.
4. Play services/GSF/Play Store stable without crash loops; RAM headroom on 1 GB.
5. Owner-side uncertified-device registration, sign-in, Play Store install/update.
6. Decide Katniss (search/Assistant) and a small GabOS framework overlay
   (notification listener on low-RAM, TV account picker, network location) only
   after measuring the base set.
7. Promotion layout: Android 12 system on the 2 GiB `system` partition and data
   directly on userdata needs its own plan, host backup verification and
   rollback path before any older system is removed.

The earlier priorities below still apply to the same candidate (it keeps the
full-redraw, client-blend, soft-start and legacy-media changes).

## Priority 1: validate the final full-redraw candidate

Final offline candidate `outputs/android12-framework/gabos-full-redraw-01` preserves
native Android TV transparency and enables a targeted GPU/client-composition
fallback for fractional-alpha/dim frames. Home 0.5 Settings opens native TV
settings directly; the custom settings page/Control app is removed. Keep native
Files. The owner explicitly rejected the earlier opaque-window workaround.

The prior trial `20261111-134210-12720cc5` returned to v14 and restored factory
recovery with readback. The final candidate has not had a fresh immutable boot.
Its two HWUI full-redraw properties corrected the internal runtime capture,
but physical confirmation and performance comparison are pending.

Verify normal boot and full factory recovery readback at the end of each new test. Never end a
handoff with a temporary recovery slot silently assumed restored. Historical
failed RESULT files remain failures; use a separate verified restoration record
when repairing an interrupted run.

## Priority 2: physical settings and audio regressions

- From GabOS Home, open native Settings repeatedly. Verify Home stays visible
  through the left transparent area without gray flashes; test Back and submenus.
- The owner confirmed the GPU fallback alone still flickered gray. Combined
  `debug.hwui.use_buffer_age=false` / `debug.hwui.use_partial_updates=false`,
  followed by restarting TV Settings, corrected the internal capture. Start
  with a physical retest; do not restore an opaque theme.
- Confirm the fallback activates in composer logs and compare the runtime
  `debug.rayo.graphics.client_blend` switch only during a guarded trial. The
  fallback keeps alpha values unchanged; it may add GPU work on affected frames.
- Listen at moderate volume to first output and output after standby. The new
  PCM16 100 ms soft start passed host sample tests but cannot prove the amplifier
  power-up transient is fixed. Inspect actual HAL routing/driver startup if not.

## Priority 3: real media and third-party apps

- Run the original AVC/AAC clip, identify the actual selected decoder and require
  real frames/completion. Hardware registry/creation/ports are not playback proof.
- Verify lens colors/motion and speaker audio; then seek, repeat and test longer
  playback while measuring RAM/temperature. Retain software fallback for comparison.
- Retest Aurora with full redraw first: its blank UI may share the old buffer
  damage/alpha problem, but that is unverified. Resolve its blank capture/cold-start delay. Check whether it is a real
  display problem versus capture/security/initialization behavior; do not re-sign
  or modify third-party APKs to hide a failure. Store onboarding/download/install
  remains unverified. No personal account is configured by the agent.
- Verify SmartTube browsing and actual streaming/codec choice. It is installed;
  initial activity launch is not playback proof.

## Priority 4: runtime performance and remaining hardware

- Measure warm and cold launch latency separately. Tiny app cold opens were
  around eight seconds in a diagnostic run. Frame callback count is not panel FPS.
- Disabled JIT remains a workaround for an earlier ART crash. Do not call it an
  optimization or re-enable it globally without a separate guarded test.
- Wi-Fi startup/internet/reconnect and Bluetooth startup/discovery passed. Still
  test suspend/reconnect and actual Bluetooth accessory pairing/HID/audio/BLE.
  The current primary-only audio policy does not establish A2DP routing.
- Fix real shutdown/cooling and verify battery, suspend and physical power cycles.
  Both current shutdown methods returned to v14; inactivity shutdown stays disabled.
- Review debug/permissive/legacy security behavior before a release; do not claim
  production security or hardware-backed cryptography from the software backend.

## Promotion and removal are still conditional

The owner authorized making GabOS primary and removing older active systems
once stable. First pass the above regression checks, repeated boots and extended
use, with independent recovery/host backups verified. Prepare a concrete layout
and rollback plan; current private data is only 256 MiB and gets tight during AOT.
Any expansion must update/revalidate the explicit size/UUID/reserve guards rather
than bypass them. Do not erase factory partitions or the only recovery copies.
Physical MaskROM entry remains untested. The current ten-minute trial is not a
stable primary installation, even if boot completes.

## Minimal application scope

Keep GabOS Home, Media and Diagnostics plus native Android TV Settings/Files,
Aurora and SmartTube. Do not recreate a full browser/office suite. Casting/Share
and a signed Updater are later work requiring genuine protocol/update/rollback
implementations, not placeholder success screens.
