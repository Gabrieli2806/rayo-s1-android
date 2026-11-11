#!/usr/bin/env bash
# Rebuild only the ARM device library from an already configured TV output.
set -eo pipefail
rayo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
rayo_source=${RAYO_ANDROID12_SOURCE_DIR:-"$HOME/android/rayo12"}
rayo_out=${RAYO_ANDROID12_TV_OUT_DIR:-"$HOME/android/rayo12-tv-out"}
test -f "$rayo_out/combined-rayos1_tv12.ninja" || { echo 'Configure/build the pinned TV product first.' >&2; exit 1; }
python3 "$rayo_root/tools/apply-android12-bluetooth-compat.py" --source "$rayo_source"
cd "$rayo_source"
prebuilts/build-tools/linux-x86/bin/ninja -j"${RAYO_JOBS:-12}" \
  -f "$rayo_out/combined-rayos1_tv12.ninja" \
  -o usesphonyoutputs=yes -w dupbuild=err -w missingdepfile=err \
  "$rayo_out/target/product/rayos1_tv12/system/lib/libbluetooth.so"
