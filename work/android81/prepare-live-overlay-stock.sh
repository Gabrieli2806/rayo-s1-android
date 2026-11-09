#!/system/bin/sh
set -e
O=/data/rayo81-trial/data/local/tmp/rayo-live
/system/bin/busybox test -f "$O/lib/libc.so"
/system/bin/busybox cp -a /data/local/tmp/rayo-graphics-delta/vendor/. "$O/vendor/"
/system/bin/busybox chmod 0755 "$O/vendor/bin/hw/android.hardware.graphics.allocator@2.0-service"
/system/bin/busybox rm "$O/lib/egl/libGLES_mali.so"
/system/bin/busybox ln -s ../../vendor/lib/egl/libGLES_mali.so "$O/lib/egl/libGLES_mali.so"
/system/bin/busybox cp /data/local/tmp/trial-vendor-manifest.xml "$O/vendor/manifest.xml"
/system/bin/busybox cp /data/local/tmp/trial-vendor-manifest.xml "$O/vendor/etc/vintf/manifest.xml"
/system/bin/busybox test -f /data/rayo81-trial/data/local/tmp/public.libraries.trial.txt
/system/bin/busybox echo RAYO_OVERLAY_PREPARED
