#!/system/bin/sh
# GabOS 1.0 boot-completion setup. Started once by init (service gabos_boot)
# when sys.boot_completed=1. Every step is idempotent and failures are ignored
# so a missing package never blocks the rest.
set -u

LAUNCHER=local.rayo.launcher
SHARE=local.gabos.share

has_package() {
    pm path "$1" >/dev/null 2>&1
}

# Projected 720p is read from a distance: default to a 1.2x interface scale
# (density 192) unless the owner already chose another density.
if [ -z "$(settings get secure display_density_forced 2>/dev/null | grep -v null)" ]; then
    wm density 192 >/dev/null 2>&1 || true
fi

# There is no platform setup wizard; the GabOS welcome flow lives in Home.
# The framework ignores the Home key until setup is marked complete.
settings put secure user_setup_complete 1 >/dev/null 2>&1 || true
settings put secure tv_user_setup_complete 1 >/dev/null 2>&1 || true

if has_package "$LAUNCHER"; then
    # GabOS Settings changes the inactivity timeout, brightness and the
    # interface configuration (language) for the owner.
    appops set "$LAUNCHER" WRITE_SETTINGS allow >/dev/null 2>&1 || true
    pm grant "$LAUNCHER" android.permission.CHANGE_CONFIGURATION >/dev/null 2>&1 || true
    # The setup wizard lists Wi-Fi networks; Android 12 returns scan results
    # only with location access and the location setting on (no GPS here).
    pm grant "$LAUNCHER" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
    if [ "$(settings get secure location_mode 2>/dev/null)" = "0" ]; then
        settings put secure location_mode 3 >/dev/null 2>&1 || true
    fi
    # GabOS Settings' storage page reads the shared media folder sizes.
    pm grant "$LAUNCHER" android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
    # Its Bluetooth page calls profile connect/disconnect and removeBond, which
    # are hidden platform methods (allowed for the privileged launcher). Exempt
    # only the Bluetooth classes from the hidden-API check, and only when no
    # other exemption has been set.
    case "$(settings get global hidden_api_blacklist_exemptions 2>/dev/null)" in
        ""|null) settings put global hidden_api_blacklist_exemptions "Landroid/bluetooth/" >/dev/null 2>&1 || true ;;
    esac
    # GabOS Home is the only Home activity.
    cmd package set-home-activity "$LAUNCHER/.MainActivity" >/dev/null 2>&1 || true
    # The projector is driven by a pointer and a D-pad; select the GabOS
    # keyboard, which accepts both, whenever the launcher provides it.
    if ime list -a -s 2>/dev/null | grep -q "^$LAUNCHER/.KeyboardService\$"; then
        ime enable "$LAUNCHER/.KeyboardService" >/dev/null 2>&1 || true
        ime set "$LAUNCHER/.KeyboardService" >/dev/null 2>&1 || true
    fi
fi

# Local photo/video receiver, discoverable by the GabOS Share sender apps.
if has_package "$SHARE"; then
    # ShareService opens the approval prompt and the viewer from the
    # background. Android 12 only allows that for overlay holders; with
    # targetSdk 25 the overlay permission is not granted at install (and the
    # low-RAM default is ignored on non-leanback builds), so the owner would
    # otherwise have to find the overlay setting. Saving to Pictures/Movies needs the
    # storage runtime permissions (targetSdk 25 does not get them at install).
    appops set "$SHARE" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
    pm grant "$SHARE" android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
    pm grant "$SHARE" android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
    am startservice --user 0 -n "$SHARE/.ShareService" >/dev/null 2>&1 || true
fi

# GRemote: the phone remote's pointer is an accessibility service. Enable it
# once (keeping any other enabled services) and exempt it from idle limits so
# it keeps answering the phone. The owner can still turn it off in Settings.
GREMOTE=com.gabrieli2806.gremote.tv
GREMOTE_SERVICE="$GREMOTE/.PointerService"
if has_package "$GREMOTE" && [ "$(settings get secure gabos_gremote_defaulted 2>/dev/null)" != "1" ]; then
    CURRENT="$(settings get secure enabled_accessibility_services 2>/dev/null)"
    case "$CURRENT" in
        ""|null) LIST="$GREMOTE_SERVICE" ;;
        *"$GREMOTE_SERVICE"*) LIST="$CURRENT" ;;
        *) LIST="$CURRENT:$GREMOTE_SERVICE" ;;
    esac
    settings put secure enabled_accessibility_services "$LIST" >/dev/null 2>&1 || true
    settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true
    settings put secure gabos_gremote_defaulted 1 >/dev/null 2>&1 || true
fi
for PACKAGE in "$GREMOTE" "$SHARE"; do
    has_package "$PACKAGE" && dumpsys deviceidle whitelist +"$PACKAGE" >/dev/null 2>&1 || true
done

# Friendly network name (GabOS Share, Bluetooth) instead of the build product.
NAME="$(settings get global device_name 2>/dev/null)"
case "$NAME" in
    ""|null|*Development*|*rayos1*) settings put global device_name "GabOS Rayo S1" >/dev/null 2>&1 || true ;;
esac

setprop sys.gabos.boot.ready 1
