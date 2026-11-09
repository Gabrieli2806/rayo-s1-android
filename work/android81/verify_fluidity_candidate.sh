#!/usr/bin/env bash
set -euo pipefail
work=/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81
out=/var/tmp/codex-rayo-android81/work/out
export LD_LIBRARY_PATH="$out/host/linux-x86/lib64:/var/tmp/codex-rayo-android81/work/host-compat"
validator="$out/host/linux-x86/bin/validatekeymaps"
set +e
"$validator" "$work/stock-system/usr/keylayout/Generic.kl" > "$work/fluidity-original-keymap-validation.txt" 2>&1
original_status=$?
set -e
printf 'Original Generic.kl validation exit: %s\n' "$original_status"
if [ "$original_status" -eq 0 ]; then
    echo 'Expected the observed OEM key labels to fail Oreo parsing' >&2
    exit 1
fi
"$validator" "$work/fluidity-v01/keylayout/Generic.kl" "$work/fluidity-v01/keylayout/rk29-keypad.kl" > "$work/fluidity-candidate-keymap-validation.txt" 2>&1
echo 'Candidate keymaps: all passed the Android 8.1 parser'
bash -n "$work/profile-fluidity-settings.sh" "$work/measure-fluidity.sh"
cp "$out/target/product/rayos1_oreo/system/lib/hw/hwcomposer.rayo_fluidity.so" "$work/fluidity-v01/"
echo 'Candidate library exported; no device access performed'
