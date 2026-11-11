# Build-only radio candidate. No flashable image or device operation.
include device/canon/rayos1_tv12/BoardConfig.mk
BOARD_WLAN_DEVICE := bcmdhd
BOARD_WPA_SUPPLICANT_DRIVER := NL80211
WPA_SUPPLICANT_VERSION := VER_0_8_X
BOARD_WPA_SUPPLICANT_PRIVATE_LIB := lib_driver_cmd_bcmdhd
WIFI_DRIVER_FW_PATH_PARAM := /sys/module/bcmdhd/parameters/firmware_path
WIFI_DRIVER_FW_PATH_STA := /system/etc/firmware/fw_RK903_ag.bin
# AP/P2P modes are not selected or declared validated by this profile.
