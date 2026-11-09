#!/sbin/busybox sh
set -eu
BB=/sbin/busybox
O=/data/local/tmp/rayo-live
$BB test -f "$O/vendor/lib/hw/android.hardware.graphics.mapper@2.0-impl.so"
$BB mount --bind "$O/lib" /system/lib
$BB mount --bind "$O/vendor" /system/vendor
$BB mount --bind "$O/etc" /system/etc
$BB mount --bind /data/local/tmp/public.libraries.trial.txt /system/etc/public.libraries.txt
if $BB test -f "$O/keylayout/Generic.kl"; then
    $BB mount --bind "$O/keylayout" /system/usr/keylayout
fi
if $BB test -f "$O/bootanimation.zip"; then
    $BB mount --bind "$O/bootanimation.zip" /system/media/bootanimation.zip
fi
$BB echo RAYO_GRAPHICS_OVERLAY_READY > /dev/kmsg
