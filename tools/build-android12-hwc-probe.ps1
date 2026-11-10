param(
    [Parameter(Mandatory=$true)][string]$Source,
    [string]$Sdk=$env:ANDROID_HOME,
    [string]$NdkVersion='25.2.9519653'
)
$ErrorActionPreference='Stop'
$rayoRoot=Split-Path -Parent $PSScriptRoot
if(-not $Sdk){$Sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$rayoToolchain=Join-Path $Sdk ('ndk/'+$NdkVersion+'/toolchains/llvm/prebuilt/windows-x86_64')
$rayoCompiler=Join-Path $rayoToolchain 'bin/clang++.exe'
if(-not (Test-Path $rayoCompiler)){throw 'Install the recorded Windows NDK first'}
$rayoIncludeRoots=@(
    'hardware/libhardware/include','system/core/libcutils/include',
    'system/core/libsystem/include','system/logging/liblog/include',
    'frameworks/native/libs/nativebase/include','frameworks/native/libs/nativewindow/include',
    'frameworks/native/libs/arect/include','system/core/libutils/include'
)
foreach($rayoDirectory in $rayoIncludeRoots){
    if(-not (Test-Path (Join-Path $Source $rayoDirectory))){throw ('Missing Android 12 source directory: '+$rayoDirectory)}
}
$rayoOutput=Join-Path $rayoRoot 'outputs/android12-probes'
New-Item -ItemType Directory -Force $rayoOutput | Out-Null
$rayoLibrary=Join-Path $rayoOutput 'hwcomposer.rayo12_abi_probe.so'
$rayoArguments=@(
    '--target=armv7a-linux-androideabi31',('--sysroot='+ (Join-Path $rayoToolchain 'sysroot')),
    '-std=c++14','-shared','-fPIC','-O2','-fno-exceptions','-fno-rtti','-nostdlib++',
    '-DTARGET_BOARD_PLATFORM_RK312X','-Wall','-Wextra','-Werror',
    '-Wl,-soname,hwcomposer.rayo12_abi_probe.so'
)
foreach($rayoDirectory in $rayoIncludeRoots){$rayoArguments+=('-I'+(Join-Path $Source $rayoDirectory))}
& $rayoCompiler @rayoArguments (Join-Path $rayoRoot 'work/android81/device/canon/rayos1_fluidity/hwc_adapter.cpp') -llog -ldl -o $rayoLibrary
if($LASTEXITCODE -ne 0){throw 'Android 12 header/ARM32 adapter build failed'}
$rayoReadelf=Join-Path $rayoToolchain 'bin/llvm-readelf.exe'
$rayoDetails=& $rayoReadelf --file-header --dynamic-table $rayoLibrary
if($LASTEXITCODE -ne 0){throw 'ELF inspection failed'}
$rayoDetails | Set-Content (Join-Path $rayoOutput 'hwc-api31-readelf.txt')
$rayoHash=(Get-FileHash $rayoLibrary -Algorithm SHA256).Hash.ToLower()
[ordered]@{
    purpose='Build-only ARM32/API31 ABI probe; not a complete composer HAL or device validation'
    source_tree=$Source
    ndk=$NdkVersion
    api=31
    output='hwcomposer.rayo12_abi_probe.so'
    sha256=$rayoHash
    factory_abi_static_asserts='Passed against Android 12 headers'
    device_installed=$false
    unresolved=@('Factory blobs on Android 12 bionic','HWC2On1/composer service integration','Fence/buffer compatibility','Physical projection and timing')
} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $rayoOutput 'hwc-api31-build.json')
Write-Output ('ARM32/API31 adapter probe built: '+$rayoHash)
Write-Output 'Built and inspected only. No upload, HAL replacement, or flash performed.'
