param([string]$Sdk=$env:ANDROID_HOME,[string]$NdkVersion='25.2.9519653')
$ErrorActionPreference='Stop'
$rayoRoot=Split-Path -Parent $PSScriptRoot
if(-not $Sdk){$Sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$rayoToolchain=Join-Path $Sdk ('ndk/'+$NdkVersion+'/toolchains/llvm/prebuilt/windows-x86_64')
$rayoCompiler=Join-Path $rayoToolchain 'bin/clang.exe'
if(-not (Test-Path $rayoCompiler)){throw 'Install the requested Android NDK first'}
$rayoOutput=Join-Path $rayoRoot 'outputs/probes'
New-Item -ItemType Directory -Force $rayoOutput | Out-Null
$rayoCommon=@('--target=armv7a-linux-androideabi21',('--sysroot='+ (Join-Path $rayoToolchain 'sysroot')),'-O2','-Wall','-Wextra','-Werror')
& $rayoCompiler @rayoCommon -fPIE -pie (Join-Path $rayoRoot 'work/android81/rayo_wifi_caps.c') -o (Join-Path $rayoOutput 'rayo-radio-caps')
if($LASTEXITCODE -ne 0){throw 'Radio capability helper build failed'}
& $rayoCompiler @rayoCommon -shared -fPIC '-Wl,-soname,libtg.so' '-Wl,--no-as-needed' (Join-Path $rayoRoot 'work/android81/device/canon/rayos1_oreo/bt_timer_compat.c') -ldl -o (Join-Path $rayoOutput 'libtg.so')
if($LASTEXITCODE -ne 0){throw 'Bluetooth timer compatibility library build failed'}
& $rayoCompiler @rayoCommon -fPIE -pie (Join-Path $rayoRoot 'work/probes/load_libraries.c') -ldl -o (Join-Path $rayoOutput 'rayo-load-libraries')
if($LASTEXITCODE -ne 0){throw 'Library load probe build failed'}
& $rayoCompiler @rayoCommon -fPIE -pie (Join-Path $rayoRoot 'work/probes/bt_timer_compat_check.c') -ldl -o (Join-Path $rayoOutput 'rayo-bt-timer-check')
if($LASTEXITCODE -ne 0){throw 'Bluetooth timer check build failed'}
Get-FileHash (Join-Path $rayoOutput 'rayo-radio-caps'),(Join-Path $rayoOutput 'libtg.so'),(Join-Path $rayoOutput 'rayo-load-libraries') -Algorithm SHA256
Write-Output 'Built only. No device installation or settings changes.'
