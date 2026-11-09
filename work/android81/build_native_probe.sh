#!/usr/bin/env bash
set -eo pipefail
workspace=/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81
cd "$workspace/build-root/aosp"
export PATH="$PWD/prebuilts/python/linux-x86/2.7.5/bin:$PATH"
export JAVA_HOME="$PWD/prebuilts/jdk/jdk8/linux-x86"
export LC_ALL=C
export TERM=dumb
export OUT_DIR=/var/tmp/codex-rayo-android81/work/out
mkdir -p /var/tmp/codex-rayo-android81/work/host-compat
cp -L prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.15-4.8/sysroot/usr/lib/lib{ncurses,tinfo}.so.5 /var/tmp/codex-rayo-android81/work/host-compat/
export LD_LIBRARY_PATH=/var/tmp/codex-rayo-android81/work/host-compat${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
source build/envsetup.sh
lunch rayos1_abi-userdebug
m -j8 linker rayo_gpu_probe rayo_abi_probe libc libdl liblog libc++ libcutils libutils libbinder libhardware libion libui libgui libEGL libGLESv1_CM libGLESv2 libhidlbase libhidltransport libhwbinder libhardware_legacy libsync
