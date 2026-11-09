param([switch]$Gpu, [switch]$Trace, [switch]$HwBinder)
$ErrorActionPreference='Stop'
$rayoLab='/data/local/tmp/rayo81-abi-lab'
$rayoMounts=@()
try {
    $rayoNodes=@('urandom')
    if($Gpu){$rayoNodes+=@('mali','ion')}
    if($HwBinder){$rayoNodes+=@('hwbinder','vndbinder')}
    foreach($rayoNode in $rayoNodes){
        adb shell su 0 /system/bin/busybox mount --bind /dev/$rayoNode "$rayoLab/dev/$rayoNode"
        if($LASTEXITCODE -ne 0){throw "Mount failed: $rayoNode"}
        $rayoMounts+="$rayoLab/dev/$rayoNode"
    }
    adb shell su 0 /system/bin/busybox mount -t proc -o ro,nosuid,nodev,noexec proc "$rayoLab/proc"
    if($LASTEXITCODE -ne 0){throw 'Proc mount failed'}
    $rayoMounts+="$rayoLab/proc"
    $rayoCommand=@('/system/bin/busybox','chroot',$rayoLab)
    if($HwBinder){$rayoCommand+='/system/bin/rayo_hwbinder_probe'}
    elseif($Gpu){$rayoCommand+='/system/bin/rayo_gpu_probe'}
    else{$rayoCommand+=@('/system/bin/rayo_abi_probe','/vendor/lib/egl/libGLES_mali.so','/vendor/lib/hw/gralloc.rk30board.so','/vendor/lib/hw/hwcomposer.rk30board.so')}
    if($Trace){$rayoCommand=@('/system/xbin/strace','-f','-o','/data/local/tmp/rayo81-gpu.strace','-e','trace=file,ioctl')+$rayoCommand}
    adb shell su 0 timeout -k 3 20 @rayoCommand
    $rayoResult=$LASTEXITCODE
} finally {
    [array]::Reverse($rayoMounts)
    foreach($rayoMount in $rayoMounts){adb shell su 0 /system/bin/busybox umount $rayoMount}
}
exit $rayoResult
