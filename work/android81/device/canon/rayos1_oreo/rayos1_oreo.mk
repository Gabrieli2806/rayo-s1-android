# Minimal complete Android framework, not the native-only ABI target.
# This is NOT approved for flashing until the kernel and restore tests pass.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_base.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/go_defaults.mk)
$(call inherit-product-if-exists, external/roboto-fonts/fonts.mk)
$(call inherit-product-if-exists, external/noto-fonts/fonts.mk)
$(call inherit-product, frameworks/base/data/keyboards/keyboards.mk)
$(call inherit-product, frameworks/base/data/fonts/fonts.mk)

PRODUCT_NAME := rayos1_oreo
PRODUCT_DEVICE := rayos1_oreo
PRODUCT_BRAND := Canon
PRODUCT_MODEL := Rayo S1 Oreo Experimental
PRODUCT_MANUFACTURER := Canon
PRODUCT_LOCALES := es_US en_US
PRODUCT_AAPT_CONFIG := normal large xlarge mdpi hdpi xhdpi
PRODUCT_AAPT_PREF_CONFIG := mdpi
PRODUCT_CHARACTERISTICS := tablet
PRODUCT_SHIPPING_API_LEVEL := 25
PRODUCT_ART_TARGET_INCLUDE_DEBUG_BUILD := false

PRODUCT_PACKAGES += \
    SystemUI Settings InputDevices LatinIME KeyChain CaptivePortalLogin \
    CertInstaller DocumentsUI DownloadProviderUi ExternalStorageProvider \
    FusedLocation VpnDialogs StorageManager Bluetooth BluetoothMidiService \
    android.hardware.audio@2.0-service android.hardware.audio@2.0-impl \
    android.hardware.audio.effect@2.0-impl \
    android.hardware.bluetooth@1.0-service android.hardware.bluetooth@1.0-impl \
    android.hardware.wifi@1.0-service \
    wpa_supplicant hostapd android.hardware.wifi.supplicant@1.0 \
    audio.primary.default audio.a2dp.default audio.r_submix.default audio.usb.default \
    android.hardware.graphics.mapper@2.0-impl \
    android.hardware.graphics.allocator@2.0-impl \
    android.hardware.graphics.allocator@2.0-service libcompiler_rt \
    android.hardware.keymaster@3.0-impl android.hardware.keymaster@3.0-service

PRODUCT_SYSTEM_SERVER_APPS += FusedLocation InputDevices KeyChain
PRODUCT_PROPERTY_OVERRIDES += \
    ro.sf.lcd_density=160 \
    ro.sf.hwrotation=0 \
    ro.opengles.version=131072 \
    wifi.interface=wlan0 \
    ro.hwui.renderer=opengl

# Blob integration and the board-specific boot ramdisk are separate steps.
# Never substitute this generic audio fallback for the checked Rockchip HAL.
