#!/usr/bin/env bash
set -euo pipefail
workspace=/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81
build_root="$workspace/build-root"
mountpoint -q "$build_root" || { echo 'Build filesystem is not mounted'; exit 1; }
mkdir -p "$build_root/aosp" "$build_root/tools"
curl --fail --location --proto '=https' https://storage.googleapis.com/git-repo-downloads/repo -o "$build_root/tools/repo"
export GIT_CONFIG_COUNT=2
export GIT_CONFIG_KEY_0=user.name
export GIT_CONFIG_VALUE_0='Rayo Local Build'
export GIT_CONFIG_KEY_1=user.email
export GIT_CONFIG_VALUE_1='rayo-build@localhost'
cd "$build_root/aosp"
python3 "$build_root/tools/repo" init -u https://android.googlesource.com/platform/manifest -b android-8.1.0_r81 --depth=1 --no-clone-bundle
python3 "$build_root/tools/repo" manifest -o "$workspace/manifest-source.xml"
echo 'Official Android 8.1 source manifest initialized.'
if [[ "${1:-}" == '--sync' ]]; then
    python3 "$build_root/tools/repo" sync -c -j2 --no-clone-bundle --no-tags --fail-fast >"$workspace/source-sync.log" 2>&1
    python3 "$build_root/tools/repo" manifest -r -o "$workspace/manifest-pinned.xml"
    echo 'Android 8.1 sources synchronized and revisions pinned.'
fi
