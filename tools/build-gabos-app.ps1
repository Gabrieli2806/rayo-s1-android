param([ValidateSet('media')][string]$App='media')
$ErrorActionPreference='Stop'
$gabosRoot=Split-Path -Parent $PSScriptRoot
$gabosSdk=$env:ANDROID_HOME
if(-not $gabosSdk){$gabosSdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$gabosBuild=Join-Path $gabosSdk 'build-tools/36.0.0'
$gabosJar=Join-Path $gabosSdk 'platforms/android-26/android.jar'
$gabosSource=Join-Path $gabosRoot ('work/gabos-'+$App)
$gabosWork=Join-Path $gabosRoot (('local/'+$App+'-build/')+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$gabosClasses=Join-Path $gabosWork 'classes'
$gabosDex=Join-Path $gabosWork 'dex'
New-Item -ItemType Directory -Force $gabosClasses,$gabosDex | Out-Null
# R.java lets the code use the English/Spanish string resources.
$gabosGenerated=Join-Path $gabosWork 'gen'
New-Item -ItemType Directory -Force $gabosGenerated | Out-Null
& (Join-Path $gabosBuild 'aapt.exe') package -f -m -J $gabosGenerated -M (Join-Path $gabosSource 'AndroidManifest.xml') -S (Join-Path $gabosSource 'res') -I $gabosJar
if($LASTEXITCODE -ne 0){throw 'GabOS app resource index generation failed'}
& javac -source 8 -target 8 -encoding UTF-8 -classpath $gabosJar -d $gabosClasses @(Get-ChildItem $gabosSource -Filter '*.java' | ForEach-Object FullName) @(Get-ChildItem $gabosGenerated -Recurse -Filter '*.java' | ForEach-Object FullName) (Join-Path $gabosRoot 'work/gabos-ui/GabosUi.java')
if($LASTEXITCODE -ne 0){throw 'GabOS app Java compilation failed'}
$gabosFiles=@(Get-ChildItem $gabosClasses -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $gabosBuild 'd8.bat') --min-api 26 --lib $gabosJar --output $gabosDex @gabosFiles
if($LASTEXITCODE -ne 0){throw 'GabOS app DEX compilation failed'}
& (Join-Path $gabosBuild 'aapt.exe') package -f -M (Join-Path $gabosSource 'AndroidManifest.xml') -S (Join-Path $gabosSource 'res') -I $gabosJar -F (Join-Path $gabosWork 'unsigned.apk') $gabosDex
if($LASTEXITCODE -ne 0){throw 'GabOS app packaging failed'}
& (Join-Path $gabosBuild 'zipalign.exe') -f 4 (Join-Path $gabosWork 'unsigned.apk') (Join-Path $gabosWork 'aligned.apk')
if($LASTEXITCODE -ne 0){throw 'GabOS app alignment failed'}
$gabosOutput=Join-Path $gabosRoot ('outputs/GabOS-'+(Get-Culture).TextInfo.ToTitleCase($App)+'.apk')
& (Join-Path $gabosBuild 'apksigner.bat') sign --ks (Join-Path $gabosRoot 'local/secrets/launcher-android12-development.p12') --ks-pass ('file:'+(Join-Path $gabosRoot 'local/secrets/launcher-android12-development-password.txt')) --v4-signing-enabled false --out $gabosOutput (Join-Path $gabosWork 'aligned.apk')
if($LASTEXITCODE -ne 0){throw 'GabOS app signing failed'}
& (Join-Path $gabosBuild 'apksigner.bat') verify $gabosOutput
if($LASTEXITCODE -ne 0){throw 'GabOS app signature verification failed'}
Write-Output $gabosOutput

@{package=('local.gabos.'+$App);sha256=(Get-FileHash -Algorithm SHA256 $gabosOutput).Hash.ToLower();google_services=$false} | ConvertTo-Json | Set-Content -Encoding utf8 ($gabosOutput+'.build.json')
