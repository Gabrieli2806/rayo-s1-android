# Inactivity shutdown preparation

Default policy: 30 minutes of inactivity, then a 60-second warning. Input resets
the countdown. Any wake lock or active media session conservatively blocks it.
Unknown/unparseable power or media state blocks shutdown. Each service start
also waits 30 minutes, avoiding shutdown immediately after installation/restart.
A root service polls every 15 seconds; it does not require Google services or a PC.

## Current limitation

Both framework shutdown and direct `reboot -p` returned to normal v14 rather than
staying off on this unit. Therefore this service is **not enabled or imported**
into either OS. A monitor is prepared, not a verified automatic power-off feature.
Turning off Android's display is not evidence that the projector light is off.
The current plugged-in stay-awake setting is deliberately unchanged.

Source: `work/gabos-power/idle-shutdown.sh` and `init.gabos.idle.rc`.
Observation can be run with `sh idle-shutdown.sh --observe-once`; it does not
request shutdown. Deployment needs the script under `/system/etc/gabos/` and
an explicit init import. No boot/system partition write is needed for observation.

Before activation, fix and physically verify the board shutdown/cooling sequence,
validate input/media detection and visible notification in both OS versions, then
create the root-only backend acceptance file
`/data/local/gabos-idle/verified-backend` containing `framework-shutdown-v1`.
Set `persist.gabos.idle.enabled=1` only after those checks. Set it to `0` to disable.
The root-owned `shutdown-attempted` latch survives reboot and blocks repeated
shutdown attempts; inspect a failed attempt before explicitly clearing it.
A paused media session may conservatively prevent shutdown until its app closes.
The notification command still requires device UI validation; log output is not
proof that its countdown is visible. HDMI/external input must be checked before
release because Android media-session state may not describe external playback.

## Observation check on this unit

On November 11, the script ran once through root ADB on completed Android 8.1
v14. It parsed an inactivity age of 5314150 ms, reported `enabled=0` and
`poweroff_unverified`, and exited without requesting shutdown. Shell syntax
validation passed. Countdown, media/input cancellation and actual power-off
remain unverified. Only a temporary script was copied; no init import, launcher
replacement, persistent enable property, or partition modification was made.
