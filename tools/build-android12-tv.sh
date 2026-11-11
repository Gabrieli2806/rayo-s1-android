#!/usr/bin/env bash
set -eo pipefail
rayo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
rayo_source=${RAYO_ANDROID12_SOURCE_DIR:-"$HOME/android/rayo12"}
test -f "$rayo_source/build/envsetup.sh" || { echo 'Synchronize the full pinned platform first.' >&2; exit 1; }
python3 "$rayo_root/tools/apply-android12-legacy-build-fixes.py" --source "$rayo_source"
python3 "$rayo_root/tools/apply-android12-network-lab.py" --source "$rayo_source"
python3 "$rayo_root/tools/apply-android12-adb-compat.py" --source "$rayo_source"
python3 "$rayo_root/tools/apply-android12-graphics-compat.py" --source "$rayo_source"
python3 "$rayo_root/tools/apply-android12-processgroup-compat.py" --source "$rayo_source"
python3 "$rayo_root/tools/apply-android12-audio-compat.py" --source "$rayo_source"
python3 "$rayo_root/tools/apply-android12-crypto-lab.py" --source "$rayo_source"
python3 "$rayo_root/tools/apply-android12-keystore-compat.py" --source "$rayo_source"
python3 "$rayo_root/tools/apply-android12-bluetooth-compat.py" --source "$rayo_source"
mkdir -p "$rayo_source/device/canon"
cp -a "$rayo_root/work/android12/device/canon/rayos1_12_lab" "$rayo_source/device/canon/"
cp -a "$rayo_root/work/android12/device/canon/rayos1_tv12" "$rayo_source/device/canon/"
cp -a "$rayo_root/work/android12/device/canon/rayos1_tv12_radios" "$rayo_source/device/canon/"
cp "$rayo_root/work/android81/device/canon/rayos1_fluidity/hwc_adapter.cpp" "$rayo_source/device/canon/rayos1_12_lab/hwc_adapter_legacy.cpp"
cp "$rayo_root/work/android81/device/canon/rayos1_fluidity/rk_hwc_abi.h" "$rayo_source/device/canon/rayos1_12_lab/rk_hwc_abi.h"
export OUT_DIR=${RAYO_ANDROID12_TV_OUT_DIR:-"$HOME/android/rayo12-tv-out"}
export LC_ALL=C TERM=dumb
cd "$rayo_source"
source build/envsetup.sh
rayo_product=${RAYO_ANDROID12_PRODUCT:-rayos1_tv12-eng}
case "$rayo_product" in
  rayos1_tv12-eng|rayos1_tv12_radios-eng) ;;
  *) echo 'Unsupported Rayo product' >&2; exit 1 ;;
esac
lunch "$rayo_product"
if [ "$#" -eq 0 ]; then
  set -- services SystemUI TvSettings surfaceflinger init adbd linkerconfig netd bpfloader
fi
m -j"${RAYO_JOBS:-12}" "$@"
printf '\nTV framework preparation only. Images disabled; no device installation performed.\n'
