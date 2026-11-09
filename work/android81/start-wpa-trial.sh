#!/system/bin/sh
set -eu
[ "$(getprop ro.rayo.trial)" = 1 ]
mkdir -p /data/misc/wifi/sockets /data/misc/wifi/wpa_supplicant /data/misc/wpa_supplicant
chown 1010:1010 /data/misc/wifi /data/misc/wifi/sockets /data/misc/wifi/wpa_supplicant /data/misc/wpa_supplicant
chmod 0770 /data/misc/wifi /data/misc/wifi/sockets /data/misc/wifi/wpa_supplicant /data/misc/wpa_supplicant
if [ ! -f /data/misc/wifi/wpa_supplicant.conf ]; then
    cp /system/etc/wifi/wpa_supplicant.conf /data/misc/wifi/wpa_supplicant.conf
    echo ctrl_interface=/data/misc/wifi/sockets >> /data/misc/wifi/wpa_supplicant.conf
    chown 1010:1010 /data/misc/wifi/wpa_supplicant.conf
    chmod 0660 /data/misc/wifi/wpa_supplicant.conf
fi
/vendor/bin/hw/wpa_supplicant -g@android:wpa_wlan0 -dd </dev/null >/data/local/tmp/rayo-wpa.log 2>&1 &
