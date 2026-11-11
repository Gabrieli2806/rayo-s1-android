#!/usr/bin/env bash
set -euo pipefail
rayo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
rayo_source=${RAYO_ANDROID12_SOURCE_DIR:-"$HOME/android/rayo12"}
rayo_out="$rayo_root/outputs/android12-art-probe"
rayo_jdk="$rayo_source/prebuilts/jdk/jdk11/linux-x86/bin"
mkdir -p "$rayo_out/classes" "$rayo_out/dex"
"$rayo_jdk/javac" --release 8 -d "$rayo_out/classes" "$rayo_root/work/android12/art/RayoRuntimeProbe.java"
"$rayo_jdk/java" -cp "$rayo_source/prebuilts/r8/r8.jar" com.android.tools.r8.D8 \
  --min-api 31 --output "$rayo_out/dex" "$rayo_out/classes/"*.class
"$rayo_jdk/jar" --create --file "$rayo_out/runtime-probe.jar" -C "$rayo_out/dex" classes.dex
echo 'Built pure Java/Dex ART smoke probe from the pinned AOSP JDK and D8. No device action.'
