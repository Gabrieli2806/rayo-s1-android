$(call inherit-product, $(SRC_TARGET_DIR)/product/embedded.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/go_defaults.mk)

PRODUCT_NAME := rayos1_abi
PRODUCT_DEVICE := rayos1
PRODUCT_BRAND := Canon
PRODUCT_MODEL := Rayo S1 ABI Laboratory
PRODUCT_MANUFACTURER := Canon
PRODUCT_LOCALES := es_US en_US

PRODUCT_PACKAGES += \
    rayo_abi_probe \
    libc \
    libdl \
    liblog \
    libc++ \
    libcutils \
    libutils \
    libbinder \
    libhardware \
    libion \
    libui \
    libgui \
    libEGL \
    libGLESv1_CM \
    libGLESv2 \
    libhidlbase \
    libhidltransport \
    libhwbinder

# This product is used to build individual libraries, not systemimage.
# No telephony, GMS, commercial launcher, camera app, or preinstalled media apps.
