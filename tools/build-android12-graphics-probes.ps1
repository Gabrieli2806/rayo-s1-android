param(
    [string]$Ndk = "$env:LOCALAPPDATA/Android/Sdk/ndk/25.2.9519653",
    [string]$OutputDirectory = "$PSScriptRoot/../outputs/android12-probes"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolchain = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$source = Join-Path $root 'work/android12/device/canon/rayos1_12_lab'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
& "$toolchain/armv7a-linux-androideabi31-clang.cmd" -Wall -Wextra -Werror "$source/gpu_probe.c" -ldl -o "$OutputDirectory/rayo12-gpu-namespace-probe"
if ($LASTEXITCODE -ne 0) { throw 'GPU probe build failed' }
& "$toolchain/armv7a-linux-androideabi26-clang.cmd" -Wall -Wextra -Werror -static "$source/private_mount_probe.c" -o "$OutputDirectory/rayo12-private-mount-probe"
if ($LASTEXITCODE -ne 0) { throw 'Private mount helper build failed' }
Write-Output 'Built ARMv7 offscreen probes. No device action performed.'
