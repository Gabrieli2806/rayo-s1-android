param(
    [ValidateSet('Doctor','Status','StartTrial','PrepareRuntime','BootOriginal','RestoreOriginalRecovery')]
    [string]$Action='Status',
    [string]$AdbPath
)
$ErrorActionPreference='Stop'
$rayoRoot=Split-Path -Parent $PSScriptRoot
$rayoKit=Join-Path $rayoRoot 'local/device-kit'
if(-not $AdbPath){
    $rayoCommand=Get-Command adb -ErrorAction SilentlyContinue
    if($rayoCommand){$AdbPath=$rayoCommand.Source}
    elseif($env:ANDROID_HOME){$AdbPath=Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'}
}
if($Action -eq 'Doctor'){
    foreach($rayoTool in @('git','gh','python','wsl','javac')){
        $rayoFound=Get-Command $rayoTool -ErrorAction SilentlyContinue
        [pscustomobject]@{Tool=$rayoTool;Available=[bool]$rayoFound;Path=$rayoFound.Source}
    }
    [pscustomobject]@{Tool='adb';Available=[bool]($AdbPath -and (Test-Path $AdbPath));Path=$AdbPath}
    [pscustomobject]@{Tool='device-kit';Available=(Test-Path (Join-Path $rayoKit 'MANIFEST.json'));Path=$rayoKit}
    return
}
if(-not $AdbPath -or -not (Test-Path $AdbPath)){throw 'Install Android Platform Tools or pass -AdbPath'}
function Invoke-RayoAdb([string[]]$AdbArguments){
    $rayoOutput=& $AdbPath @AdbArguments
    if($LASTEXITCODE -ne 0){throw ('ADB failed: '+($AdbArguments -join ' '))}
    return $rayoOutput
}
$rayoDevices=Invoke-RayoAdb @('devices')
$rayoSerial=$null
foreach($rayoKnown in @('0123456789ABCDEF','3GKL2KGCA400000000')){
    if($rayoDevices -match ('^'+$rayoKnown+'\s+(device|recovery)\s*$')){$rayoSerial=$rayoKnown;break}
}
if(-not $rayoSerial){throw 'This known Rayo S1 is not connected/authorized. Check adb devices and USB drivers.'}
$rayoVersion=((Invoke-RayoAdb @('-s',$rayoSerial,'shell','getprop','ro.build.version.release')) -join '').Trim()
$rayoTrial=((Invoke-RayoAdb @('-s',$rayoSerial,'shell','getprop','ro.rayo.trial')) -join '').Trim()
$rayoComplete=((Invoke-RayoAdb @('-s',$rayoSerial,'shell','getprop','sys.boot_completed')) -join '').Trim()
if($Action -eq 'Status'){
    [pscustomobject]@{Serial=$rayoSerial;Android=$rayoVersion;Trial=$rayoTrial;BootCompleted=$rayoComplete}
    return
}
if($rayoVersion -notin @('7.1.2','8.1.0')){throw 'Unexpected Android version; no writes performed'}
if($rayoVersion -eq '7.1.2' -and $rayoSerial -ne '3GKL2KGCA400000000'){throw 'Boot the original Android normally before using this action'}
if($rayoVersion -eq '8.1.0' -and $rayoTrial -ne '1'){throw 'Unexpected 8.1 session; no writes performed'}
function Invoke-RayoRoot([string[]]$RootArguments){
    $rayoArgs=@('-s',$rayoSerial,'shell')
    if($rayoVersion -eq '7.1.2'){$rayoArgs+=@('su','0')}
    return Invoke-RayoAdb ($rayoArgs+$RootArguments)
}
function Assert-RayoFile([string]$Relative){
    $rayoManifestPath=Join-Path $rayoKit 'MANIFEST.json'
    if(-not (Test-Path $rayoManifestPath)){throw 'Download the device kit first: tools/download-device-kit.ps1'}
    $rayoManifest=Get-Content $rayoManifestPath -Raw | ConvertFrom-Json
    $rayoRecord=@($rayoManifest.files | Where-Object {$_.path -eq $Relative})
    if($rayoRecord.Count -ne 1){throw ('Missing kit manifest entry: '+$Relative)}
    $rayoFile=Join-Path $rayoKit $Relative
    if(-not (Test-Path $rayoFile)){throw ('Missing kit file: '+$Relative)}
    if((Get-FileHash $rayoFile -Algorithm SHA256).Hash.ToLower() -ne $rayoRecord[0].sha256){throw ('Kit SHA256 mismatch: '+$Relative)}
    return $rayoFile
}
function Assert-RayoRemoteMd5([string]$Remote,[string]$Expected){
    $rayoHash=(Invoke-RayoRoot @('md5sum',$Remote)) -join "`n"
    if($rayoHash -notmatch ('^'+$Expected+'\s')){throw ('Remote checkpoint differs: '+$Remote)}
}
if($Action -eq 'BootOriginal'){
    if($rayoVersion -eq '7.1.2'){Write-Output 'Original Android is already running';return}
    Invoke-RayoAdb @('-s',$rayoSerial,'shell','setprop','sys.powerctl','reboot') | Out-Host
    return
}
if($Action -eq 'PrepareRuntime'){
    if($rayoVersion -ne '7.1.2'){throw 'PrepareRuntime requires the preserved original Android'}
    $rayoImage=Assert-RayoFile 'system/rayo81-system.raw.img'
    $rayoOverlay=Assert-RayoFile 'runtime/rayo81-live-overlay.tar.gz'
    Invoke-RayoAdb @('-s',$rayoSerial,'push',$rayoImage,'/data/local/tmp/rayo81-system.raw.img') | Out-Host
    Assert-RayoRemoteMd5 '/data/local/tmp/rayo81-system.raw.img' 'ac1bc01a07c33e93ff079063bd647acf'
    Invoke-RayoAdb @('-s',$rayoSerial,'push',$rayoOverlay,'/data/local/tmp/rayo81-portable-overlay.tar.gz') | Out-Host
    Invoke-RayoRoot @('/system/bin/busybox','mkdir','-p','/data/rayo81-trial/data/local/tmp') | Out-Host
    $rayoStamp=Get-Date -Format 'yyyyMMdd-HHmmss'
    $rayoCheck=& $AdbPath -s $rayoSerial shell su 0 /system/bin/busybox test -d /data/rayo81-trial/data/local/tmp/rayo-live
    if($LASTEXITCODE -eq 0){
        Invoke-RayoRoot @('/system/bin/busybox','mv','/data/rayo81-trial/data/local/tmp/rayo-live',('/data/rayo81-trial/data/local/tmp/rayo-live.backup-'+$rayoStamp)) | Out-Host
    }
    Invoke-RayoRoot @('/system/bin/busybox','tar','-xzf','/data/local/tmp/rayo81-portable-overlay.tar.gz','-C','/data/rayo81-trial/data/local/tmp') | Out-Host
    # The legacy overlay script expects this separate bind source too.
    Invoke-RayoRoot @('/system/bin/busybox','cp','/data/rayo81-trial/data/local/tmp/rayo-live/etc/public.libraries.txt','/data/rayo81-trial/data/local/tmp/public.libraries.trial.txt') | Out-Host
    Write-Output 'Trial system and runtime prepared. Existing runtime was retained as a timestamped backup. No partitions written.'
    return
}
if($Action -eq 'StartTrial' -and $rayoVersion -eq '8.1.0'){
    Invoke-RayoAdb @('-s',$rayoSerial,'shell','am','start','-n','local.rayo.launcher/.MainActivity') | Out-Host
    Write-Output 'Android 8.1 trial is already running';return
}
if($Action -eq 'StartTrial'){
    $rayoImage=Assert-RayoFile 'boot/trial-recovery-v14.img'
    Assert-RayoRemoteMd5 '/data/local/tmp/rayo81-system.raw.img' 'ac1bc01a07c33e93ff079063bd647acf'
    Assert-RayoRemoteMd5 '/data/rayo81-trial/data/local/tmp/rayo-live/lib/libsurfaceflinger.so' 'ab536d6a6826341488f51b65ee0e31c0'
    Assert-RayoRemoteMd5 '/data/rayo81-trial/data/local/tmp/rayo-live/lib/hw/hwcomposer.rk30board.so' '5b414cb2fa1666325aa1a6d10f2f0015'
    Assert-RayoRemoteMd5 '/data/rayo81-trial/data/local/tmp/rayo-live/vendor/lib/libbt-vendor.so' 'a098a2c5f41758f00198e25fe6550c1a'
    Assert-RayoRemoteMd5 '/data/rayo81-trial/data/local/tmp/rayo-live/vendor/lib/libtg.so' '727917f74ddb111c7aea0b16733127e9'
    Assert-RayoRemoteMd5 '/data/rayo81-trial/data/local/tmp/rayo-live/etc/media_codecs.xml' '0014166bd070664d7df9da0f4016c61a'
}elseif($Action -eq 'RestoreOriginalRecovery'){
    $rayoImage=Assert-RayoFile 'original/recovery.img'
}else{throw 'Unsupported action'}
if((Get-Item $rayoImage).Length -ne 33554432){throw 'Recovery must be exactly 32 MiB'}
$rayoMd5=(Get-FileHash $rayoImage -Algorithm MD5).Hash.ToLower()
if($Action -eq 'StartTrial' -and $rayoMd5 -ne '7a3ac489b05f0f2e9cde16280551511a'){throw 'Unexpected trial recovery image'}
if($Action -eq 'RestoreOriginalRecovery' -and $rayoMd5 -ne 'b32f971e7dfce13c708d264c6221844d'){throw 'Unexpected original recovery image'}
Invoke-RayoAdb @('-s',$rayoSerial,'push',$rayoImage,'/data/local/tmp/rayo-recovery-candidate.img') | Out-Host
Assert-RayoRemoteMd5 '/data/local/tmp/rayo-recovery-candidate.img' $rayoMd5
if($rayoVersion -eq '7.1.2'){
    Invoke-RayoRoot @('setprop','ctl.stop','flash_recovery') | Out-Host
    $rayoRestorer=((Invoke-RayoRoot @('getprop','init.svc.flash_recovery')) -join '').Trim()
    if($rayoRestorer -ne 'stopped'){throw 'Factory recovery restorer is still active'}
}
$rayoBusybox=if($rayoVersion -eq '7.1.2'){'/system/bin/busybox'}else{'/sbin/busybox'}
Invoke-RayoRoot @($rayoBusybox,'dd','if=/data/local/tmp/rayo-recovery-candidate.img','of=/dev/block/platform/1021c000.rksdmmc/by-name/recovery','bs=1048576','count=32') | Out-Host
Invoke-RayoRoot @('sync') | Out-Host
Assert-RayoRemoteMd5 '/dev/block/platform/1021c000.rksdmmc/by-name/recovery' $rayoMd5
if($Action -eq 'StartTrial'){
    Invoke-RayoAdb @('-s',$rayoSerial,'reboot','recovery') | Out-Host
    Write-Output 'Recovery written and verified. One-time trial boot requested; check Status after boot.'
}else{Write-Output 'Original recovery written and verified. No reboot requested.'}
