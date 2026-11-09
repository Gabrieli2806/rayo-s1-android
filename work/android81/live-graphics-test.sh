#!/system/bin/sh
setprop ctl.stop surfaceflinger
setprop ctl.stop zygote
/sbin/busybox mount --bind /system/lib/egl/libGLES_mali.so /system/vendor/lib/egl/libGLES_mali.so
for n in $(cat /system/etc/public.libraries.txt); do
    if /sbin/busybox test -f /system/lib/$n; then
        /sbin/busybox echo $n
    fi
done > /data/local/tmp/public.libraries.trial.txt
/sbin/busybox mount --bind /data/local/tmp/public.libraries.trial.txt /system/etc/public.libraries.txt
setprop ctl.start surfaceflinger
setprop ctl.start zygote
