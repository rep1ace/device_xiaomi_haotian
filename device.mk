
#
# Copyright (C) 2023 The Android Open Source Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from sm8650-common
$(call inherit-product, device/xiaomi/sm8750-common/common.mk)

# Get non-open-source specific aspects
$(call inherit-product, vendor/xiaomi/haotian/haotian-vendor.mk)

# Display
PRODUCT_COPY_FILES += \
    device/xiaomi/haotian/configs/displayconfig/display_id_4630946654109872275.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/displayconfig/display_id_4630946654109872275.xml

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)
