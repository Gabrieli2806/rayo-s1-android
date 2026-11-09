#!/usr/bin/env bash
set -eo pipefail
workspace=/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81
aosp="$workspace/build-root/aosp"
kernel=/var/tmp/codex-rayo-android81/work/kernel-sources/rockchip-3.10
output=/var/tmp/codex-rayo-android81/work/kernel-sources/rk-build
export PATH="$aosp/prebuilts/python/linux-x86/2.7.5/bin:$PATH"
export LD_LIBRARY_PATH=/var/tmp/codex-rayo-android81/work/host-compat${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export ARCH=arm
export CROSS_COMPILE="$aosp/prebuilts/gcc/linux-x86/arm/arm-linux-androideabi-4.9/bin/arm-linux-androideabi-"
mkdir -p "$output"
if [ ! -f "$output/.config" ]; then
    make -C "$kernel" O="$output" rockchip_defconfig
fi
make -C "$kernel" O="$output" HOSTCFLAGS='-O2 -fcommon' -j8 modules_prepare
make -C "$kernel" O="$output" HOSTCFLAGS='-O2 -fcommon' M="$workspace/kernel-module-lab" -j8 modules

make -C "$kernel" O="$output" HOSTCFLAGS='-O2 -fcommon' M="$workspace/hwbinder-module" -j8 modules
