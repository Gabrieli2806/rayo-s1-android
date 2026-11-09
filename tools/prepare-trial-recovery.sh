#!/usr/bin/env bash
set -euo pipefail
rayo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
rayo_version=${1:-v14}
[[ "$rayo_version" =~ ^v[0-9]+$ ]] || { echo 'Expected a checkpoint name such as v14.' >&2; exit 1; }
rayo_base="$rayo_root/local/device-kit/runtime/trial-root-v12.tar.gz"
rayo_helper="$rayo_root/outputs/probes/rayo-radio-caps"
rayo_output="$rayo_root/outputs/firmware/trial-recovery-$rayo_version.img"
test -f "$rayo_base" || { echo 'Download the v0.1 device kit first.' >&2; exit 1; }
test -f "$rayo_helper" || { echo 'Run tools/build-radio-tools.ps1 first.' >&2; exit 1; }
test ! -e "$rayo_output" || { echo 'Refusing to overwrite existing recovery output.' >&2; exit 1; }
rayo_stage=$(mktemp -d "/var/tmp/rayo-s1-$rayo_version.XXXXXX")
mkdir -p "$rayo_stage/root" "$rayo_root/outputs/firmware" "$rayo_root/local/portable"
tar -xzf "$rayo_base" -C "$rayo_stage/root"
cp "$rayo_root/work/android81/trial-root-v01/init.rk30board.rc" "$rayo_stage/root/init.rk30board.rc"
cp "$rayo_helper" "$rayo_stage/root/sbin/rayo-wifi-caps"
chmod 755 "$rayo_stage/root/sbin/rayo-wifi-caps"
python3 "$rayo_root/tools/pack-recovery.py" --ramdisk "$rayo_stage/root" --output "$rayo_output"
tar -czf "$rayo_root/local/portable/trial-root-$rayo_version.tar.gz" -C "$rayo_stage/root" .
echo "Preserved Linux staging directory: $rayo_stage"
echo 'Prepared only; no device writes or reboot.'
