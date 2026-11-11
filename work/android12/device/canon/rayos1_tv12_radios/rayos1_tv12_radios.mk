$(call inherit-product, device/canon/rayos1_tv12/rayos1_tv12.mk)
PRODUCT_NAME := rayos1_tv12_radios
PRODUCT_DEVICE := rayos1_tv12_radios
PRODUCT_MODEL := Rayo S1 Android 12 Offline Radio Candidate
PRODUCT_PACKAGES += \
    android.hardware.wifi@1.0-service \
    wpa_supplicant \
    android.hardware.bluetooth@1.0-service \
    android.hardware.bluetooth@1.0-impl \
    librayo12_bt_timer_compat
