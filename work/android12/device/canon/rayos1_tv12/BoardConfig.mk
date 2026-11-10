# Board-specific framework build preparation, not an installable boot target.
include device/canon/rayos1_12_lab/BoardConfig.mk
TARGET_COPY_OUT_PRODUCT := system/product
TARGET_COPY_OUT_SYSTEM_EXT := system/system_ext
TARGET_FLATTEN_APEX := true
