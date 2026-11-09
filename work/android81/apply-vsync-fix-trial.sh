#!/system/bin/sh
set -eu
[ "$(getprop ro.build.version.release)" = 8.1.0 ]
[ "$(getprop ro.rayo.trial)" = 1 ]
live=/data/local/tmp/rayo-live/lib/libsurfaceflinger.so
backup=/data/local/tmp/rayo-live/lib/libsurfaceflinger.rayo-baseline.so
candidate=/data/local/tmp/libsurfaceflinger-vsync-fix.so
test "$(md5sum "$live" | cut -d' ' -f1)" = 6e5f72cc5496d740c5fb5d73e94b9c14
test "$(md5sum "$candidate" | cut -d' ' -f1)" = 422f98bdb40359237652a54fce5ceeed
if [ ! -f "$backup" ]; then cp -p "$live" "$backup"; fi
test "$(md5sum "$backup" | cut -d' ' -f1)" = 6e5f72cc5496d740c5fb5d73e94b9c14
setprop ctl.stop zygote
setprop ctl.stop surfaceflinger
count=0
while [ "$(getprop init.svc.surfaceflinger)" != stopped ] || [ "$(getprop init.svc.zygote)" != stopped ]; do
    count=$((count+1)); [ "$count" -lt 50 ] || exit 3
    sleep 0.1
done
cp "$candidate" "$live"
chmod 644 "$live"
restorecon /system/lib/libsurfaceflinger.so
setprop debug.sf.no_hw_vsync 1
sync
setprop ctl.start surfaceflinger
setprop ctl.start zygote
echo 'Software vsync thread enabled; factory composition preserved'
