#!/usr/bin/env bash
# Build offline in a separate output tree; never replace the passing TV product.
set -eo pipefail
rayo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export RAYO_ANDROID12_PRODUCT=rayos1_tv12_radios-eng
export RAYO_ANDROID12_TV_OUT_DIR=${RAYO_ANDROID12_RADIOS_OUT_DIR:-"$HOME/android/rayo12-radios-out"}
exec bash "$rayo_root/tools/build-android12-tv.sh" \
  android.hardware.wifi@1.0-service wpa_supplicant \
  android.hardware.bluetooth@1.0-service android.hardware.bluetooth@1.0-impl \
  librayo12_bt_timer_compat
