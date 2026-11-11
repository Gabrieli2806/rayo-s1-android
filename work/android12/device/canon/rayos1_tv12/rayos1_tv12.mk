# Native tests establish a graphics path; full TV boot remains unverified.
PRODUCT_SUPPORTS_CAMERA := false
$(call inherit-product, device/google/atv/products/atv_base.mk)
$(call inherit-product, device/google/atv/products/atv_lowram_defaults.mk)

PRODUCT_NAME := rayos1_tv12
PRODUCT_DEVICE := rayos1_tv12
PRODUCT_BRAND := Canon
PRODUCT_MODEL := Rayo S1 Android 12 TV Development
PRODUCT_MANUFACTURER := Canon
PRODUCT_CHARACTERISTICS := tv
PRODUCT_SHIPPING_API_LEVEL := 25
PRODUCT_USE_DYNAMIC_PARTITIONS := false
PRODUCT_SYSTEM_PROPERTIES += ro.sf.lcd_density=160 ro.kernel.ebpf.supported=false
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += debug.sf.no_hw_vsync=0
# SystemServer starts the Wi-Fi framework only when this hardware feature is declared.
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/android.hardware.wifi.xml \
    frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/android.hardware.usb.host.xml
PRODUCT_PACKAGES += \
    android.hardware.graphics.composer@2.1-service \
    android.hardware.graphics.allocator@2.0-service \
    android.hardware.graphics.allocator@2.0-impl \
    android.hardware.graphics.mapper@2.0-impl \
    hwcomposer.rayo12 \
    android.hardware.health@2.1-service \
    android.hardware.health@2.1-impl \
    android.hardware.keymaster@4.1-service \
    android.hardware.gatekeeper@1.0-service.software \
    android.hardware.audio.service \
    android.hardware.audio@7.0-impl \
    android.hardware.audio.effect@7.0-impl \
    libaudiohal@7.0 \
    LeanbackIME \
    TvProvision

# Original Google TV Home/services are a separate integration stage; no
# lookalike launcher is selected as a substitute. Settings/ADB bootstrap only.
# The isolated trial packager integrates OEM blobs, init, namespaces and
# rollback guards separately. Normal product image generation stays disabled.
PRODUCT_BUILD_SYSTEM_IMAGE := false
PRODUCT_BUILD_SYSTEM_OTHER_IMAGE := false
PRODUCT_BUILD_VENDOR_IMAGE := false
PRODUCT_BUILD_PRODUCT_IMAGE := false
PRODUCT_BUILD_SYSTEM_EXT_IMAGE := false
PRODUCT_BUILD_ODM_IMAGE := false
PRODUCT_BUILD_VENDOR_DLKM_IMAGE := false
PRODUCT_BUILD_ODM_DLKM_IMAGE := false
PRODUCT_BUILD_CACHE_IMAGE := false
PRODUCT_BUILD_RAMDISK_IMAGE := false
PRODUCT_BUILD_USERDATA_IMAGE := false
PRODUCT_BUILD_RECOVERY_IMAGE := false
PRODUCT_BUILD_BOOT_IMAGE := false
PRODUCT_BUILD_VENDOR_BOOT_IMAGE := false
PRODUCT_BUILD_VBMETA_IMAGE := false
PRODUCT_BUILD_SUPER_EMPTY_IMAGE := false
