#!/usr/bin/env bash
set -eo pipefail
workspace=/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81
cd "$workspace/build-root/aosp"
export PATH="$PWD/prebuilts/python/linux-x86/2.7.5/bin:$PATH"
export JAVA_HOME="$PWD/prebuilts/jdk/jdk8/linux-x86"
export LD_LIBRARY_PATH=/var/tmp/codex-rayo-android81/work/host-compat${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export LC_ALL=C
export TERM=dumb
export ART_BUILD_HOST_DEBUG=false
export ART_BUILD_TARGET_DEBUG=false
export USE_DEX2OAT_DEBUG=false
export OUT_DIR=/var/tmp/codex-rayo-android81/work/out
export ANDROID_DATA="$OUT_DIR/host/linux-x86/rayo-android-data"
mkdir -p "$ANDROID_DATA"
source build/envsetup.sh
lunch rayos1_oreo-userdebug
m -j8 libsurfaceflinger

