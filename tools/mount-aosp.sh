#!/usr/bin/env bash
set -euo pipefail
rayo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
rayo_image=${1:-"$rayo_root/local/legacy-workspace/work/android81/build-volume.ext4"}
rayo_mount="$rayo_root/local/aosp-volume"
test -f "$rayo_image" || { echo 'Copy the source volume from the original PC, or obtain AOSP on a Linux filesystem as documented.' >&2; exit 1; }
mkdir -p "$rayo_mount"
if ! mountpoint -q "$rayo_mount"; then
    sudo mount -o loop "$rayo_image" "$rayo_mount"
fi
test -d "$rayo_mount/aosp/.repo"
printf 'export RAYO_AOSP_DIR=%q\n' "$rayo_mount/aosp"
echo 'This mounts the existing source volume; no formatting is performed.'
