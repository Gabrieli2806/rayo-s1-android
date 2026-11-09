param([string]$Sdk=$env:ANDROID_HOME,[string]$NdkVersion='25.2.9519653')
$ErrorActionPreference='Stop'
$rayoRoot=Split-Path -Parent $PSScriptRoot
if(-not $Sdk){$Sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$rayoToolchain=Join-Path $Sdk ('ndk/'+$NdkVersion+'/toolchains/llvm/prebuilt/windows-x86_64')
$rayoCompiler=Join-Path $rayoToolchain 'bin/clang.exe'
if(-not (Test-Path $rayoCompiler)){throw 'Install the requested Android NDK version first'}
$rayoOutput=Join-Path $rayoRoot 'outputs/probes/rayo-kernel-capabilities'
New-Item -ItemType Directory -Force (Split-Path $rayoOutput) | Out-Null
& $rayoCompiler --target=armv7a-linux-androideabi21 ('--sysroot='+ (Join-Path $rayoToolchain 'sysroot')) -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie (Join-Path $rayoRoot 'work/probes/kernel_capabilities.c') -o $rayoOutput
if($LASTEXITCODE -ne 0){throw 'ARM32 probe compilation failed'}
& (Join-Path $rayoToolchain 'bin/llvm-readelf.exe') -h $rayoOutput
if($LASTEXITCODE -ne 0){throw 'ELF validation failed'}
Get-FileHash $rayoOutput -Algorithm SHA256
Write-Output 'Built only; nothing uploaded to or run on the device.'
