#!/system/bin/sh
/vendor/bin/hw/android.hardware.keymaster@3.0-service </dev/null >/data/local/tmp/rayo-keymaster.log 2>&1 &
setprop ctl.restart keystore
