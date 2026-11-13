param([string]$Sdk=$env:ANDROID_HOME,[string]$BuildTools='36.0.0',[string]$Keystore,[string]$SigningPasswordFile)
$ErrorActionPreference='Stop'
$rayoRoot=Split-Path -Parent $PSScriptRoot
if(-not $Sdk){$Sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$rayoJava=Get-Command javac -ErrorAction Stop
$rayoBuildTools=Join-Path $Sdk ('build-tools/'+$BuildTools)
$rayoAndroidJar=Join-Path $Sdk 'platforms/android-26/android.jar'
if(-not (Test-Path $rayoAndroidJar)){throw 'Install SDK platform android-26'}
# The Android 12 build of Hardware Check is signed with the development key, like the other GabOS apps.
$rayoDevKey=Join-Path $rayoRoot 'local/secrets/launcher-android12-development.p12'
$rayoDevPass=Join-Path $rayoRoot 'local/secrets/launcher-android12-development-password.txt'
if(-not $Keystore){
    $Keystore=Join-Path $rayoRoot 'local/secrets/launcher-signing.p12'
    if(-not (Test-Path $Keystore) -and (Test-Path $rayoDevKey)){$Keystore=$rayoDevKey;if(-not $SigningPasswordFile){$SigningPasswordFile=$rayoDevPass}}
}
if(-not (Test-Path $Keystore)){throw 'Copy the launcher signing key into local/secrets first'}
$rayoPassFile=if($SigningPasswordFile){$SigningPasswordFile}else{Join-Path $rayoRoot 'local/secrets/launcher-signing-password.txt'}
$rayoPassArgument=if($env:RAYO_SIGNING_PASSWORD){'env:RAYO_SIGNING_PASSWORD'}elseif(Test-Path $rayoPassFile){'file:'+$rayoPassFile}else{throw 'Set RAYO_SIGNING_PASSWORD or provide the local password file'}
$rayoSource=Join-Path $rayoRoot 'work/rayo-validation'
$rayoScratch=Join-Path $rayoRoot ('local/validation-build/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$rayoClasses=Join-Path $rayoScratch 'classes'
$rayoDex=Join-Path $rayoScratch 'dex'
$rayoGenerated=Join-Path $rayoScratch 'gen'
$rayoResources=Join-Path $rayoScratch 'res'
$rayoOutput=Join-Path $rayoRoot 'outputs/Rayo-Hardware-Check.apk'
New-Item -ItemType Directory -Force $rayoClasses,$rayoDex,$rayoGenerated,$rayoResources,(Split-Path $rayoOutput) | Out-Null
# Source resources (strings, icon, banner, theme) plus the generated test clip.
Copy-Item -Recurse -Force (Join-Path $rayoSource 'res/*') $rayoResources
& python (Join-Path $rayoRoot 'tools/generate-validation-media.py') --output (Join-Path $rayoResources 'raw/av_probe.mp4')
if($LASTEXITCODE -ne 0){throw 'Media generation failed'}
& (Join-Path $rayoBuildTools 'aapt.exe') package -f -m -J $rayoGenerated -M (Join-Path $rayoSource 'AndroidManifest.xml') -S $rayoResources -I $rayoAndroidJar
if($LASTEXITCODE -ne 0){throw 'Validation app resource index generation failed'}
& $rayoJava.Source -source 8 -target 8 -encoding UTF-8 -classpath $rayoAndroidJar -d $rayoClasses @(Get-ChildItem $rayoSource -Filter '*.java' | ForEach-Object FullName) @(Get-ChildItem $rayoGenerated -Recurse -Filter '*.java' | ForEach-Object FullName) (Join-Path $rayoRoot 'work/gabos-ui/GabosUi.java')
if($LASTEXITCODE -ne 0){throw 'Validation app Java compilation failed'}
$rayoClassFiles=@(Get-ChildItem $rayoClasses -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $rayoBuildTools 'd8.bat') --min-api 21 --lib $rayoAndroidJar --output $rayoDex @rayoClassFiles
if($LASTEXITCODE -ne 0){throw 'Validation app DEX compilation failed'}
& (Join-Path $rayoBuildTools 'aapt.exe') package -f -M (Join-Path $rayoSource 'AndroidManifest.xml') -S $rayoResources -I $rayoAndroidJar -F (Join-Path $rayoScratch 'unsigned.apk') $rayoDex
if($LASTEXITCODE -ne 0){throw 'Validation app resource packaging failed'}
& (Join-Path $rayoBuildTools 'zipalign.exe') -f 4 (Join-Path $rayoScratch 'unsigned.apk') (Join-Path $rayoScratch 'aligned.apk')
if($LASTEXITCODE -ne 0){throw 'Validation app alignment failed'}
& (Join-Path $rayoBuildTools 'apksigner.bat') sign --ks $Keystore --ks-pass $rayoPassArgument --v4-signing-enabled false --out $rayoOutput (Join-Path $rayoScratch 'aligned.apk')
if($LASTEXITCODE -ne 0){throw 'Validation app signing failed'}
& (Join-Path $rayoBuildTools 'apksigner.bat') verify $rayoOutput
if($LASTEXITCODE -ne 0){throw 'Validation app signature verification failed'}
Write-Output $rayoOutput
