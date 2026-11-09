#!/sbin/busybox sh
# Read-only system image and private test data. No writes to boot partitions.
set -eu
BB=/sbin/busybox
exec >>/dev/kmsg 2>&1
$BB echo 'RAYO_TRIAL: preparing isolated Android 8.1 storage'
$BB mount -t ext4 -o noatime,nosuid,nodev /dev/block/platform/1021c000.rksdmmc/by-name/userdata /realdata
$BB test -f /realdata/local/tmp/rayo81-system.raw.img
$BB mkdir -p /realdata/rayo81-trial/data /realdata/rayo81-trial/cache
$BB chmod 0700 /realdata/rayo81-trial
$BB chmod 0771 /realdata/rayo81-trial/data
$BB chown 1000:1000 /realdata/rayo81-trial/data
$BB chmod 0770 /realdata/rayo81-trial/cache
$BB chown 1000:2001 /realdata/rayo81-trial/cache
$BB test -b /dev/block/loop7 || $BB mknod -m 0600 /dev/block/loop7 b 7 7
$BB losetup -r /dev/block/loop7 /realdata/local/tmp/rayo81-system.raw.img
$BB mount --bind /realdata/rayo81-trial/data /data
$BB mkdir -p /data/cache
$BB mount --bind /realdata/rayo81-trial/cache /cache
$BB echo 'RAYO_TRIAL: read-only loop image attached; private data mounted'
