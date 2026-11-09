#!/system/bin/sh
set -eu
[ "$(getprop ro.build.version.release)" = 8.1.0 ]
[ "$(getprop ro.rayo.trial)" = 1 ]
live=/data/local/tmp/rayo-live/lib/hw/hwcomposer.rk30board.so
backup=/data/local/tmp/rayo-live/lib/hw/hwcomposer.rayo-baseline.so
candidate=/data/local/tmp/hwcomposer.rayo_fluidity.so
test "$(md5sum "$live" | cut -d' ' -f1)" = 1dc949fda6e06c3443ae310e2037d1d8
test "$(md5sum "$candidate" | cut -d' ' -f1)" = 5b414cb2fa1666325aa1a6d10f2f0015
if [ ! -f "$backup" ]; then cp -p "$live" "$backup"; fi
test "$(md5sum "$backup" | cut -d' ' -f1)" = 1dc949fda6e06c3443ae310e2037d1d8
setprop ctl.stop zygote
setprop ctl.stop surfaceflinger
count=0
while [ "$(getprop init.svc.surfaceflinger)" != stopped ] || [ "$(getprop init.svc.zygote)" != stopped ]; do
    count=$((count+1)); [ "$count" -lt 50 ] || exit 3
    sleep 0.1
done
cp "$candidate" "$live"
chmod 644 "$live"
restorecon /system/lib/hw/hwcomposer.rk30board.so
sync
setprop ctl.start surfaceflinger
setprop ctl.start zygote
echo 'Candidate active; baseline adapter preserved'
