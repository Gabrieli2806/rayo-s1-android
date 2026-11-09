# Experimental system-image build. Boot/recovery generation stays disabled.
include device/canon/rayos1/BoardConfig.mk
TARGET_USES_HWC2 := false
BOARD_WLAN_DEVICE := bcmdhd
BOARD_WPA_SUPPLICANT_DRIVER := NL80211
BOARD_HOSTAPD_DRIVER := NL80211
WPA_SUPPLICANT_VERSION := VER_0_8_X
BOARD_WPA_SUPPLICANT_PRIVATE_LIB := lib_driver_cmd_bcmdhd
BOARD_HOSTAPD_PRIVATE_LIB := lib_driver_cmd_bcmdhd
WIFI_DRIVER_FW_PATH_PARAM := /sys/module/bcmdhd/parameters/firmware_path
WIFI_DRIVER_FW_PATH_STA := /system/etc/firmware/fw_bcm4330.bin
WIFI_DRIVER_FW_PATH_AP := /system/etc/firmware/fw_bcm4330_apsta.bin
USE_XML_AUDIO_POLICY_CONF := false
WITH_DEXPREOPT := true
DEX_PREOPT_DEFAULT := nostripping
