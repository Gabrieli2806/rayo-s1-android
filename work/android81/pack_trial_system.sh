#!/usr/bin/env bash
set -euo pipefail
workspace=/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81
product=/var/tmp/codex-rayo-android81/work/out/target/product/rayos1_oreo
export PATH="/var/tmp/codex-rayo-android81/work/out/host/linux-x86/bin:$PATH"
mkuserimg.sh "$workspace/trial-system-v01" "$workspace/rayo81-system.raw.img" \
    ext4 system 1073741824 \
    "$product/obj/ETC/file_contexts.bin_intermediates/file_contexts.bin"
e2fsck -fn "$workspace/rayo81-system.raw.img"
resize2fs -M "$workspace/rayo81-system.raw.img"
e2fsck -fn "$workspace/rayo81-system.raw.img"
sha256sum "$workspace/rayo81-system.raw.img"
sync
