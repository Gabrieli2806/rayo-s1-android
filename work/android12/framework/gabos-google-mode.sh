#!/system/bin/sh
# Runtime controller for Google TV Home and optional Google Services on GabOS 12.
set -u

ACTION="${1:-status}"
ARG="${2:-}"

has_package() {
    pm path "$1" >/dev/null 2>&1
}

ensure_setup_flags() {
    settings put secure user_setup_complete 1 >/dev/null 2>&1 || true
    settings put secure tv_user_setup_complete 1 >/dev/null 2>&1 || true
}

# Default permission exceptions are applied by the platform only on a first
# boot or fingerprint change. The retained private data predates the Google
# packages, so grant the same user-revocable runtime permissions explicitly.
grant_runtime_permissions() {
    for PERMISSION in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION             ACCESS_BACKGROUND_LOCATION READ_CONTACTS WRITE_CONTACTS GET_ACCOUNTS             READ_PHONE_STATE READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE             RECORD_AUDIO BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE; do
        pm grant com.google.android.gms "android.permission.$PERMISSION" >/dev/null 2>&1 || true
    done
    for PERMISSION in READ_CONTACTS GET_ACCOUNTS READ_PHONE_STATE; do
        pm grant com.google.android.gsf "android.permission.$PERMISSION" >/dev/null 2>&1 || true
    done
    for PERMISSION in GET_ACCOUNTS READ_PHONE_STATE READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE; do
        pm grant com.android.vending "android.permission.$PERMISSION" >/dev/null 2>&1 || true
    done
}

apply_gms_state() {
    MODE="$1"
    if ! has_package com.google.android.gms; then
        return 0
    fi
    if [ "$MODE" = "1" ] || [ "$MODE" = "on" ] || [ "$MODE" = "true" ]; then
        setprop persist.gabos.gms.enabled 1
        grant_runtime_permissions
        pm enable com.android.vending >/dev/null 2>&1 || true
        pm enable com.google.android.gsf >/dev/null 2>&1 || true
        pm enable com.google.android.gms >/dev/null 2>&1 || true
        pm enable com.google.android.partnersetup >/dev/null 2>&1 || true
    else
        setprop persist.gabos.gms.enabled 0
        pm enable com.google.android.gsf >/dev/null 2>&1 || true
        pm disable-user --user 0 com.google.android.gms >/dev/null 2>&1 || true
        pm disable-user --user 0 com.google.android.partnersetup >/dev/null 2>&1 || true
        pm disable-user --user 0 com.android.vending >/dev/null 2>&1 || true
    fi
}

apply_launcher_mode() {
    LMODE="$1"
    ensure_setup_flags
    if ! has_package com.google.android.apps.tv.launcherx; then
        setprop persist.gabos.launcher.mode gabos_light
        cmd package set-home-activity local.rayo.launcher/.MainActivity >/dev/null 2>&1 || true
        return 0
    fi
    if [ "$LMODE" = "gabos_light" ]; then
        setprop persist.gabos.launcher.mode gabos_light
        pm enable local.rayo.launcher >/dev/null 2>&1 || true
        pm enable local.rayo.launcher/.MainActivity >/dev/null 2>&1 || true
        cmd package set-home-activity local.rayo.launcher/.MainActivity >/dev/null 2>&1 || true
    elif [ "$LMODE" = "google_tv_account" ]; then
        setprop persist.gabos.launcher.mode google_tv_account
        apply_gms_state 1
        pm enable com.google.android.apps.tv.launcherx >/dev/null 2>&1 || true
        pm enable com.google.android.apps.tv.launcherx/.home.HomeActivity >/dev/null 2>&1 || true
        pm disable com.google.android.apps.tv.launcherx/.home.VanillaModeHomeActivity >/dev/null 2>&1 || true
        cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity >/dev/null 2>&1 || true
    else
        setprop persist.gabos.launcher.mode google_tv
        pm enable com.google.android.apps.tv.launcherx >/dev/null 2>&1 || true
        pm enable com.google.android.apps.tv.launcherx/.home.VanillaModeHomeActivity >/dev/null 2>&1 || true
        pm disable com.google.android.apps.tv.launcherx/.home.HomeActivity >/dev/null 2>&1 || true
        cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.VanillaModeHomeActivity >/dev/null 2>&1 || true
    fi
}

case "$ACTION" in
    boot)
        # The projector is driven by a pointer and a D-pad; select the GabOS
        # keyboard, which accepts both, whenever the launcher provides it.
        if ime list -a -s 2>/dev/null | grep -q '^local.rayo.launcher/.KeyboardService$'; then
            ime enable local.rayo.launcher/.KeyboardService >/dev/null 2>&1 || true
            ime set local.rayo.launcher/.KeyboardService >/dev/null 2>&1 || true
        fi
        GMS_PROP="$(getprop persist.gabos.gms.enabled)"
        if [ -z "$GMS_PROP" ]; then
            GMS_PROP="$(getprop ro.gabos.gms.default)"
        fi
        apply_gms_state "${GMS_PROP:-0}"
        L_PROP="$(getprop persist.gabos.launcher.mode)"
        if [ -z "$L_PROP" ]; then
            L_PROP="$(getprop ro.gabos.launcher.default)"
        fi
        apply_launcher_mode "${L_PROP:-google_tv}"
        setprop sys.gabos.google_mode.ready 1
        ;;
    gms)
        apply_gms_state "${ARG:-0}"
        exec "$0" status
        ;;
    launcher)
        apply_launcher_mode "${ARG:-google_tv}"
        am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
        exec "$0" status
        ;;
    status)
        GMS_ENABLED="$(getprop persist.gabos.gms.enabled)"
        LAUNCHER_MODE="$(getprop persist.gabos.launcher.mode)"
        HOME_RES="$(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null | tail -n 1)"
        GMS_PID="$(pidof com.google.android.gms 2>/dev/null || true)"
        GTV_PID="$(pidof com.google.android.apps.tv.launcherx 2>/dev/null || true)"
        echo "launcher_mode=${LAUNCHER_MODE:-unset}"
        echo "gms_enabled=${GMS_ENABLED:-unset}"
        echo "resolved_home=${HOME_RES:-unknown}"
        echo "gtv_launcher_pid=${GTV_PID:-none}"
        echo "gms_pid=${GMS_PID:-none}"
        ;;
    *)
        echo "Usage: gabos-google-mode {boot|status|gms <on|off>|launcher <google_tv|google_tv_account|gabos_light>}" >&2
        exit 2
        ;;
esac
