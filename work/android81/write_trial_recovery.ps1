param([Parameter(Mandatory=$true)][string]$Image,[Parameter(Mandatory=$true)][string]$ExpectedMd5)
$ErrorActionPreference='Stop'
$rayoSerial='3GKL2KGCA400000000'
$rayoSource=(Resolve-Path -LiteralPath $Image).Path
if((Get-Item -LiteralPath $rayoSource).Length -ne 33554432){throw 'Recovery image must be exactly 32 MiB'}
if((Get-FileHash -LiteralPath $rayoSource -Algorithm MD5).Hash -ne $ExpectedMd5){throw 'Local checksum mismatch'}
adb -s $rayoSerial wait-for-device
$rayoVersion=adb -s $rayoSerial shell getprop ro.build.version.release
if($rayoVersion.Trim() -ne '7.1.2'){throw 'This writer requires the preserved stock Android session'}
adb -s $rayoSerial push $rayoSource /data/local/tmp/rayo-recovery-candidate.img
if($LASTEXITCODE -ne 0){throw 'Transfer failed'}
$rayoHash=adb -s $rayoSerial shell su 0 md5sum /data/local/tmp/rayo-recovery-candidate.img
if($rayoHash -notmatch ('^'+$ExpectedMd5.ToLower()+'\s')){throw 'Transferred checksum mismatch'}
adb -s $rayoSerial shell su 0 setprop ctl.stop flash_recovery
$rayoState=adb -s $rayoSerial shell su 0 getprop init.svc.flash_recovery
if($rayoState.Trim() -ne 'stopped'){throw 'Factory recovery restorer is not stopped'}
adb -s $rayoSerial shell su 0 /system/bin/busybox dd if=/data/local/tmp/rayo-recovery-candidate.img of=/dev/block/platform/1021c000.rksdmmc/by-name/recovery bs=1048576 count=32
if($LASTEXITCODE -ne 0){throw 'Recovery write failed'}
adb -s $rayoSerial shell su 0 sync
$rayoHash=adb -s $rayoSerial shell su 0 md5sum /dev/block/platform/1021c000.rksdmmc/by-name/recovery
if($rayoHash -notmatch ('^'+$ExpectedMd5.ToLower()+'\s')){throw 'Readback checksum mismatch'}
Write-Output 'Candidate recovery written and verified; requesting one-time recovery boot.'
adb -s $rayoSerial reboot recovery
exit $LASTEXITCODE
