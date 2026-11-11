#!/system/bin/sh
# Root init service; observation is safe before the board power-off path is fixed.
MODE=${1:---observe-once}
BASE=/data/local/gabos-idle
INTERVAL=15
THRESHOLD=1800
WARNING=60
case "$MODE" in --observe-once|--observe|--run) ;; *) exit 2;; esac
mkdir -p "$BASE" || exit 1
chmod 700 "$BASE"
started=$(cut -d. -f1 /proc/uptime)
warned=0
while :; do
    now=$(cut -d. -f1 /proc/uptime)
    power=$(dumpsys power 2>/dev/null)
    media=$(dumpsys media_session 2>/dev/null)
    idle=$(printf '%s\n' "$power" | sed -n 's/.*mLastUserActivityTime[^=]*=[0-9][0-9]* (\([0-9][0-9]*\) ms ago).*/\1/p' | sort -n | head -n 1)
    reason=unknown
    eligible=0
    if [ "$(getprop sys.boot_completed)" = 1 ] &&
       printf '%s\n' "$power" | grep -q 'mWakefulness=Awake' &&
       printf '%s\n' "$media" | grep -q 'MEDIA SESSION SERVICE' &&
       [ -n "$idle" ]; then
        reason=activity
        if [ "$idle" -ge "$((THRESHOLD * 1000))" ] && [ "$((now - started))" -ge "$THRESHOLD" ]; then
            reason=idle
            eligible=1
        fi
        # Any wake lock is conservatively treated as work; media metadata is not logged.
        if ! printf '%s\n' "$power" | grep -q 'mWakeLockSummary=0x0$' ||
           printf '%s\n' "$media" | grep -Eq 'active=true|state=(3|4|5|6|8|9|10|11)(,| )'; then
            reason=playback_or_wakelock
            eligible=0
        fi
    fi
    enabled=$(getprop persist.gabos.idle.enabled)
    [ "$MODE" = --run ] && [ "$enabled" = 1 ] || eligible=0
    backend=$(cat "$BASE/verified-backend" 2>/dev/null)
    if [ "$backend" != framework-shutdown-v1 ]; then
        eligible=0
        reason=poweroff_unverified
    fi
    printf 'mode=%s reason=%s idle_ms=%s enabled=%s\n' "$MODE" "$reason" "${idle:-unknown}" "${enabled:-0}"
    if [ "$eligible" = 1 ] && [ ! -e "$BASE/shutdown-attempted" ]; then
        if [ "$warned" = 0 ]; then
            warned=$now
            log -t GabOSIdle 'Shutdown in 60 seconds unless activity resumes'
            cmd notification post -t 'GabOS automatic shutdown' gabos-idle 'Shutting down in one minute. Use a control to cancel.' >/dev/null 2>&1
        elif [ "$((now - warned))" -ge "$WARNING" ]; then
            # Survives reboot so a broken board power path cannot create a reboot loop.
            printf '%s\n' "$now" > "$BASE/shutdown-attempted" || exit 1
            sync
            am start -a com.android.internal.intent.action.REQUEST_SHUTDOWN --ez android.intent.extra.KEY_CONFIRM false
            exit $?
        fi
    else
        warned=0
    fi
    [ "$MODE" = --observe-once ] && exit 0
    sleep "$INTERVAL"
done
