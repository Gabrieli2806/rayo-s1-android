# Native ABI laboratory only. No boot/recovery image is generated.
TARGET_ARCH := arm
TARGET_ARCH_VARIANT := armv7-a-neon
TARGET_CPU_VARIANT := cortex-a7
TARGET_CPU_ABI := armeabi-v7a
TARGET_CPU_ABI2 := armeabi
TARGET_BOARD_PLATFORM := rk3128
TARGET_BOOTLOADER_BOARD_NAME := rk30board
TARGET_USES_64_BIT_BINDER := false

TARGET_NO_BOOTLOADER := true
TARGET_NO_KERNEL := true
TARGET_NO_RECOVERY := true
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_SYSTEMIMAGE_PARTITION_SIZE := 2147483648
BOARD_FLASH_BLOCK_SIZE := 131072
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_COPY_OUT_VENDOR := system/vendor

# The shipping kernel only exposes /dev/binder (protocol 7).
# Do not turn this laboratory target into a flashable ROM until a tested
# hwbinder strategy and a compatible Android 8.1 ramdisk exist.
