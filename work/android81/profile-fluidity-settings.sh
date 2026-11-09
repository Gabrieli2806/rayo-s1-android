#!/system/bin/sh
# Optional A/B settings only. No partition writes. Not run during preparation.
set -eu
if [ "$(getprop ro.build.version.release)" != 8.1.0 ] || [ "$(getprop ro.rayo.trial)" != 1 ]; then
    echo 'Requires the isolated Rayo Android 8.1 trial' >&2; exit 1
fi
action=${1:-}
profile=${2:-}
case "$profile" in
    pointer) entries='system:pointer_speed:2' ;;
    animations) entries='global:window_animation_scale:0.5 global:transition_animation_scale:0.5 global:animator_duration_scale:0.5' ;;
    *) echo 'Usage: sh profile-fluidity-settings.sh apply|restore pointer|animations' >&2; exit 2 ;;
esac
save=/data/local/tmp/rayo-fluidity-before-$profile.tsv
case "$action" in
    apply)
        if [ ! -f "$save" ]; then
            for entry in $entries; do
                space=${entry%%:*}; rest=${entry#*:}; key=${rest%%:*}
                printf '%s %s %s\n' "$space" "$key" "$(settings get "$space" "$key")"
            done > "$save"
        fi
        for entry in $entries; do
            space=${entry%%:*}; rest=${entry#*:}; key=${rest%%:*}; value=${rest#*:}
            settings put "$space" "$key" "$value"
        done ;;
    restore)
        test -f "$save"
        while read -r space key value; do
            if [ "$value" = null ]; then settings delete "$space" "$key"
            else settings put "$space" "$key" "$value"; fi
        done < "$save" ;;
    *) echo 'Explicit apply or restore is required' >&2; exit 2 ;;
esac
