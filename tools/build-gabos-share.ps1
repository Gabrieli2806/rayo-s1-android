# Builds GabOS Share: the projector receiver (outputs/GabOS-Share.apk, a GabOS
# system app with its .build.json record) and the phone sender
# (outputs/GabOS-Share-Sender.apk, installable on ordinary Android phones).
# Same toolchain as build-gabos-app.ps1: javac against API 26, d8, aapt v1,
# zipalign and the development signing key in local/secrets.
param([ValidateSet('all','receiver','sender')][string]$Target='all')
$ErrorActionPreference='Stop'
$shareRoot=Split-Path -Parent $PSScriptRoot
$shareSdk=$env:ANDROID_HOME
if(-not $shareSdk){$shareSdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$shareBuild=Join-Path $shareSdk 'build-tools/36.0.0'
$shareJar=Join-Path $shareSdk 'platforms/android-26/android.jar'

function Build-ShareApk([string]$Source,[string]$Package,[int]$MinApi,[string]$Output,[bool]$Record){
    $work=Join-Path $shareRoot ('local/gabos-share-build/'+$Package+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
    $classes=Join-Path $work 'classes';$dex=Join-Path $work 'dex';$gen=Join-Path $work 'gen'
    New-Item -ItemType Directory -Force $classes,$dex,$gen | Out-Null
    $manifest=Join-Path $Source 'AndroidManifest.xml';$res=Join-Path $Source 'res'
    # R.java first, then the sources.
    & (Join-Path $shareBuild 'aapt.exe') package -f -m -J $gen -M $manifest -S $res -I $shareJar
    if($LASTEXITCODE -ne 0){throw "$Package resource generation failed"}
    $sources=@(Get-ChildItem (Join-Path $Source 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)+@(Get-ChildItem $gen -Recurse -Filter '*.java' | ForEach-Object FullName)
    & javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options -classpath $shareJar -d $classes @sources
    if($LASTEXITCODE -ne 0){throw "$Package Java compilation failed"}
    $classFiles=@(Get-ChildItem $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
    & (Join-Path $shareBuild 'd8.bat') --release --min-api $MinApi --lib $shareJar --output $dex @classFiles
    if($LASTEXITCODE -ne 0){throw "$Package DEX compilation failed"}
    & (Join-Path $shareBuild 'aapt.exe') package -f -M $manifest -S $res -I $shareJar -F (Join-Path $work 'unsigned.apk') $dex
    if($LASTEXITCODE -ne 0){throw "$Package packaging failed"}
    & (Join-Path $shareBuild 'zipalign.exe') -f 4 (Join-Path $work 'unsigned.apk') (Join-Path $work 'aligned.apk')
    if($LASTEXITCODE -ne 0){throw "$Package alignment failed"}
    & (Join-Path $shareBuild 'apksigner.bat') sign --ks (Join-Path $shareRoot 'local/secrets/launcher-android12-development.p12') --ks-pass ('file:'+(Join-Path $shareRoot 'local/secrets/launcher-android12-development-password.txt')) --v4-signing-enabled false --out $Output (Join-Path $work 'aligned.apk')
    if($LASTEXITCODE -ne 0){throw "$Package signing failed"}
    & (Join-Path $shareBuild 'apksigner.bat') verify $Output
    if($LASTEXITCODE -ne 0){throw "$Package signature verification failed"}
    if($Record){
        @{package=$Package;sha256=(Get-FileHash -Algorithm SHA256 $Output).Hash.ToLower();google_services=$false} | ConvertTo-Json | Set-Content -Encoding utf8 ($Output+'.build.json')
    }
    Write-Output $Output
}

New-Item -ItemType Directory -Force (Join-Path $shareRoot 'outputs') | Out-Null
if($Target -ne 'sender'){
    Build-ShareApk (Join-Path $shareRoot 'work/gabos-share') 'local.gabos.share' 23 (Join-Path $shareRoot 'outputs/GabOS-Share.apk') $true
}
if($Target -ne 'receiver'){
    Build-ShareApk (Join-Path $shareRoot 'work/gabos-share-sender') 'local.gabos.share.sender' 21 (Join-Path $shareRoot 'outputs/GabOS-Share-Sender.apk') $false
}
