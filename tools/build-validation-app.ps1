param([string]$Sdk=$env:ANDROID_HOME,[string]$BuildTools='36.0.0',[string]$Keystore)
$ErrorActionPreference='Stop'
$rayoRoot=Split-Path -Parent $PSScriptRoot
if(-not $Sdk){$Sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$rayoJava=Get-Command javac -ErrorAction Stop
$rayoBuildTools=Join-Path $Sdk ('build-tools/'+$BuildTools)
$rayoAndroidJar=Join-Path $Sdk 'platforms/android-26/android.jar'
if(-not (Test-Path $rayoAndroidJar)){throw 'Install SDK platform android-26'}
if(-not $Keystore){$Keystore=Join-Path $rayoRoot 'local/secrets/launcher-signing.p12'}
if(-not (Test-Path $Keystore)){throw 'Copy the original launcher signing key into local/secrets first'}
$rayoPassFile=Join-Path $rayoRoot 'local/secrets/launcher-signing-password.txt'
$rayoPassArgument=if($env:RAYO_SIGNING_PASSWORD){'env:RAYO_SIGNING_PASSWORD'}elseif(Test-Path $rayoPassFile){'file:'+$rayoPassFile}else{throw 'Set RAYO_SIGNING_PASSWORD or provide the local password file'}
$rayoSource=Join-Path $rayoRoot 'work/rayo-validation'
$rayoScratch=Join-Path $rayoRoot ('local/validation-build/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$rayoClasses=Join-Path $rayoScratch 'classes'
$rayoDex=Join-Path $rayoScratch 'dex'
$rayoOutput=Join-Path $rayoRoot 'outputs/Rayo-Hardware-Check.apk'
New-Item -ItemType Directory -Force $rayoClasses,$rayoDex,(Split-Path $rayoOutput) | Out-Null
& $rayoJava.Source -source 8 -target 8 -encoding UTF-8 -classpath $rayoAndroidJar -d $rayoClasses (Join-Path $rayoSource 'MainActivity.java')
if($LASTEXITCODE -ne 0){throw 'Validation app Java compilation failed'}
$rayoClassFiles=@(Get-ChildItem $rayoClasses -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $rayoBuildTools 'd8.bat') --min-api 21 --lib $rayoAndroidJar --output $rayoDex @rayoClassFiles
if($LASTEXITCODE -ne 0){throw 'Validation app DEX compilation failed'}
$rayoResources=Join-Path $rayoScratch 'res'
& python (Join-Path $rayoRoot 'tools/generate-validation-media.py') --output (Join-Path $rayoResources 'raw/av_probe.mp4')
if($LASTEXITCODE -ne 0){throw 'Media generation failed'}
& (Join-Path $rayoBuildTools 'aapt.exe') package -f -M (Join-Path $rayoSource 'AndroidManifest.xml') -S $rayoResources -I $rayoAndroidJar -F (Join-Path $rayoScratch 'unsigned.apk') $rayoDex
if($LASTEXITCODE -ne 0){throw 'Validation app resource packaging failed'}
& (Join-Path $rayoBuildTools 'zipalign.exe') -f 4 (Join-Path $rayoScratch 'unsigned.apk') (Join-Path $rayoScratch 'aligned.apk')
if($LASTEXITCODE -ne 0){throw 'Validation app alignment failed'}
& (Join-Path $rayoBuildTools 'apksigner.bat') sign --ks $Keystore --ks-pass $rayoPassArgument --v4-signing-enabled false --out $rayoOutput (Join-Path $rayoScratch 'aligned.apk')
if($LASTEXITCODE -ne 0){throw 'Validation app signing failed'}
& (Join-Path $rayoBuildTools 'apksigner.bat') verify $rayoOutput
if($LASTEXITCODE -ne 0){throw 'Validation app signature verification failed'}
Write-Output $rayoOutput


