#!/system/bin/sh
# Run manually in the isolated trial, while moving only the integrated panel.
set -eu
[ "$(getprop ro.build.version.release)" = 8.1.0 ]
[ "$(getprop ro.rayo.trial)" = 1 ]
report=/data/local/tmp/rayo-fluidity-measure-$(date +%Y%m%d-%H%M%S)
mkdir -p "$report"
getprop > "$report/properties.txt"
cat /proc/meminfo > "$report/memory.txt"
dumpsys cpuinfo > "$report/cpu.txt"
dumpsys input > "$report/input.txt"
dumpsys gfxinfo local.rayo.launcher framestats > "$report/launcher-frames.txt"
dumpsys SurfaceFlinger --latency > "$report/display-latency.txt"
cat /sys/class/graphics/fb0/disp_info > "$report/lcd.txt"
panel=
for entry in /sys/class/input/event*/device/name; do
    if [ "$(cat "$entry")" = ekt2101 ]; then
        event=${entry%/device/name}; event=${event##*/}
        panel=/dev/input/$event
        break
    fi
done
if [ -n "$panel" ]; then
    echo 'Move the integrated touchpad and press its three buttons during the next 8 seconds.'
    getevent -lt "$panel" > "$report/panel-events.txt" &
    capture_pid=$!
    sleep 8
    kill "$capture_pid" 2>/dev/null || true
    wait "$capture_pid" 2>/dev/null || true
fi
echo "$report"
