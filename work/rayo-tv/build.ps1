$ErrorActionPreference='Stop'
$taskRoot='C:/Users/gabri/Documents/Codex/2026-11-08/q'
Set-Location -LiteralPath $taskRoot
& 'C:/Program Files/Java/jdk-17/bin/javac.exe' -source 8 -target 8 -encoding UTF-8 -classpath 'C:/Users/gabri/AppData/Local/Android/Sdk/platforms/android-26/android.jar' -d 'work/rayo-tv/classes' 'work/rayo-tv/MainActivity.java'
if ($LASTEXITCODE -ne 0) { throw 'TV launcher compile failed' }
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$tvClasses=@(Get-ChildItem -LiteralPath 'work/rayo-tv/classes/local/rayo/launcher' -Filter '*.class' | ForEach-Object FullName)
& 'C:/Users/gabri/AppData/Local/Android/Sdk/build-tools/36.0.0/d8.bat' --min-api 21 --lib 'C:/Users/gabri/AppData/Local/Android/Sdk/platforms/android-26/android.jar' --output 'work/rayo-tv/dex' $tvClasses
if ($LASTEXITCODE -ne 0) { throw 'TV launcher dex failed' }
& 'C:/Users/gabri/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt.exe' package -f -M 'work/rayo-tv/AndroidManifest.xml' -S 'work/rayo-tv/res' -I 'C:/Users/gabri/AppData/Local/Android/Sdk/platforms/android-26/android.jar' -F 'work/rayo-tv/unsigned.apk' 'work/rayo-tv/dex'
if ($LASTEXITCODE -ne 0) { throw 'TV launcher package failed' }
& 'C:/Users/gabri/AppData/Local/Android/Sdk/build-tools/36.0.0/zipalign.exe' -f 4 'work/rayo-tv/unsigned.apk' 'work/rayo-tv/aligned.apk'
& 'C:/Users/gabri/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' sign --ks 'work/rayo-helper/local-signing.p12' --ks-pass env:RAYO_SIGNING_PASSWORD --key-pass env:RAYO_SIGNING_PASSWORD --v4-signing-enabled false --out 'outputs/rayo-s1/Rayo-Dark-TV-v0.2.apk' 'work/rayo-tv/aligned.apk'
if ($LASTEXITCODE -ne 0) { throw 'TV launcher signing failed' }
& 'C:/Users/gabri/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat' verify 'outputs/rayo-s1/Rayo-Dark-TV-v0.2.apk'
if ($LASTEXITCODE -ne 0) { throw 'TV launcher signature validation failed' }
