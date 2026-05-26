#
# Copyright (C) 2023 The Android Open Source Project
#
# SPDX-License-Identifier: Apache-2.0
#

DEVICE_PATH := device/xiaomi/haotian
KERNEL_PATH := $(DEVICE_PATH)-kernel

# Inherit from sm8650-common
include device/xiaomi/sm8750-common/BoardConfigCommon.mk

# Display
TARGET_SCREEN_DENSITY := 600

# Dtb/o
BOARD_PREBUILT_DTBOIMAGE := $(KERNEL_PATH)/dtbo.img
# Soong's prebuilt DTB packaging only globs *.dtb from this directory.
BOARD_PREBUILT_DTBIMAGE_DIR := $(KERNEL_PATH)/dtb

TARGET_NO_KERNEL_OVERRIDE := true
TARGET_KERNEL_SOURCE := $(KERNEL_PATH)/kernel-headers
TARGET_PREBUILT_KERNEL_HEADERS := $(KERNEL_PATH)/prebuilt_kernel_headers.tar.gz
PRODUCT_COPY_FILES += \
	$(KERNEL_PATH)/kernel:kernel

# Kernel modules
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD := $(strip $(shell cat $(KERNEL_PATH)/vendor_ramdisk/modules.load))
BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD := $(strip $(shell cat $(KERNEL_PATH)/vendor_ramdisk/modules.load.recovery))
BOARD_VENDOR_KERNEL_MODULES_LOAD := $(strip $(shell cat $(KERNEL_PATH)/vendor_dlkm/modules.load))

PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,$(KERNEL_PATH)/vendor_dlkm/,$(TARGET_COPY_OUT_VENDOR_DLKM)/lib/modules) \
    $(filter-out $(KERNEL_PATH)/vendor_ramdisk/modules.load.recovery:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/lib/modules/modules.load.recovery,$(call find-copy-subdir-files,*,$(KERNEL_PATH)/vendor_ramdisk/,$(TARGET_COPY_OUT_VENDOR_RAMDISK)/lib/modules)) \
    $(call find-copy-subdir-files,*,$(KERNEL_PATH)/system_dlkm/6.6.77-android15-8-gca30f3b4bef6-abogki440974771-4k/,$(TARGET_COPY_OUT_SYSTEM_DLKM)/lib/modules/6.6.77-android15-8-gca30f3b4bef6-abogki440974771-4k)

# Properties
TARGET_ODM_PROP += $(DEVICE_PATH)/configs/properties/odm.prop

# Inherit from the proprietary version
include vendor/xiaomi/haotian/BoardConfigVendor.mk
