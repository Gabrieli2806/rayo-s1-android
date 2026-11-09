#!/usr/bin/env bash
set -eo pipefail
rayo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
rayo_aosp=${RAYO_AOSP_DIR:-"$rayo_root/local/aosp-volume/aosp"}
test -f "$rayo_aosp/build/envsetup.sh" || { echo 'Set RAYO_AOSP_DIR to the mounted/synced AOSP checkout.' >&2; exit 1; }
python3 "$rayo_root/tools/apply-aosp-patches.py" --aosp "$rayo_aosp"
mkdir -p "$rayo_aosp/device/canon"
cp -a "$rayo_root/work/android81/device/canon/." "$rayo_aosp/device/canon/"
export OUT_DIR=${RAYO_OUT_DIR:-/var/tmp/rayo-s1-build/out}
rayo_compat="$(dirname "$OUT_DIR")/host-compat"
mkdir -p "$rayo_compat"
cp -L "$rayo_aosp"/prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.15-4.8/sysroot/usr/lib/lib{ncurses,tinfo}.so.5 "$rayo_compat/"
export LD_LIBRARY_PATH="$rayo_compat${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PATH="$rayo_aosp/prebuilts/python/linux-x86/2.7.5/bin:$PATH"
export JAVA_HOME="$rayo_aosp/prebuilts/jdk/jdk8/linux-x86"
export LC_ALL=C TERM=dumb
export ART_BUILD_HOST_DEBUG=false ART_BUILD_TARGET_DEBUG=false USE_DEX2OAT_DEBUG=false
export ANDROID_DATA="$OUT_DIR/host/linux-x86/rayo-android-data"
mkdir -p "$ANDROID_DATA"
cd "$rayo_aosp"
source build/envsetup.sh
lunch rayos1_oreo-userdebug
if [ "$#" -eq 0 ]; then set -- libsurfaceflinger hwcomposer.rayo_fluidity rayo_hwbinder_probe; fi
m -j"${RAYO_JOBS:-8}" "$@"
