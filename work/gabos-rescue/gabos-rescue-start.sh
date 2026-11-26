#!/sbin/busybox sh
# GabOS Rescue start-up: mount userdata, make the next power-on a normal boot,
# show the help picture and turn the light on. Every step is independent; a
# failure is logged to the kernel log and the rest still runs, so root ADB is
# available whatever happens here.
BB=/sbin/busybox
BASE=/dev/block/platform/1021c000.rksdmmc/by-name
exec >>/dev/kmsg 2>&1
$BB echo 'GABOS_RESCUE: starting'

$BB mount -t ext4 -o noatime,nosuid,nodev $BASE/userdata /realdata \
    || $BB echo 'GABOS_RESCUE: userdata could not be mounted'

# A "reboot,recovery" from GabOS (or its boot watchdog) leaves the one-shot
# recovery command in misc. Clear it, so turning the projector off and on
# starts GabOS again instead of this rescue.
if $BB test "$($BB head -c 13 $BASE/misc)" = 'boot-recovery'; then
    $BB dd if=/dev/zero of=$BASE/misc bs=2048 count=1 conv=notrunc && $BB sync \
        && $BB echo 'GABOS_RESCUE: cleared the recovery command'
fi

# Help picture, 1280x720 RGB565. After the loader logo the display
# controller may still scan 32-bit pixels while the framebuffer reports
# 16-bit, which shows the picture stretched and striped. Changing the mode
# away and back makes the driver program the controller for 16-bit again
# (confirmed on the projector).
$BB ln -sf /dev/graphics/fb0 /dev/fb0
$BB fbset -g 1280 720 1280 720 32
$BB fbset -g 1280 720 1280 720 16
$BB echo 0 > /sys/class/graphics/fb0/blank
$BB dd if=/res/gabos-rescue.rgb565 of=/dev/graphics/fb0 bs=2560 count=720     || $BB echo 'GABOS_RESCUE: picture not shown'

# DLP light on (it may still be off if GabOS turned it off for inactivity).
PANEL=/sys/devices/20072000.i2c/i2c-0/0-001b
$BB test -e $PANEL/panel_pmu_off && $BB echo 0 > $PANEL/panel_pmu_off

$BB touch /dev/.gabos-rescue-ready
$BB echo 'GABOS_RESCUE: ready'
