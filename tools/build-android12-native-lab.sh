#!/usr/bin/env bash
set -eo pipefail
rayo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
rayo_source=${RAYO_ANDROID12_SOURCE_DIR:-"$HOME/android/rayo12"}
test -f "$rayo_source/build/envsetup.sh" || { echo 'Complete the tagged Android 12 source sync first.' >&2; exit 1; }
test -d "$rayo_source/prebuilts/clang/host/linux-x86" || { echo 'Android 12 toolchain sources are not synchronized.' >&2; exit 1; }
python3 "$rayo_root/tools/apply-android12-legacy-build-fixes.py" --source "$rayo_source"
mkdir -p "$rayo_source/device/canon"
cp -a "$rayo_root/work/android12/device/canon/rayos1_12_lab" "$rayo_source/device/canon/"
cp "$rayo_root/work/android81/device/canon/rayos1_fluidity/hwc_adapter.cpp" "$rayo_source/device/canon/rayos1_12_lab/hwc_adapter_legacy.cpp"
cp "$rayo_root/work/android81/device/canon/rayos1_fluidity/rk_hwc_abi.h" "$rayo_source/device/canon/rayos1_12_lab/rk_hwc_abi.h"
export OUT_DIR=${RAYO_ANDROID12_OUT_DIR:-"$HOME/android/rayo12-out"}
export LC_ALL=C TERM=dumb
cd "$rayo_source"
source build/envsetup.sh
lunch rayos1_12_lab-eng
if [ "$#" -eq 0 ]; then
  set -- linker libc libm libdl libdl_android rayo12_bootstrap_probe
fi
m -j"${RAYO_JOBS:-12}" "$@"
printf '\nNative lab targets only. No partition image or device write requested.\n'
