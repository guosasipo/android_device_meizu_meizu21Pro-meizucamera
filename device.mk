#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

MEIZUCAMERA_PATH := device/meizu/meizu21Pro-meizucamera

PRODUCT_SOONG_NAMESPACES += $(MEIZUCAMERA_PATH)

PRODUCT_PACKAGES += \
    MeizuCameraSupport

PRODUCT_COPY_FILES += \
    $(MEIZUCAMERA_PATH)/configs/permissions/privapp-permissions-meizucamera.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-meizucamera.xml

PRODUCT_PRODUCT_PROPERTIES += \
    ro.product.flyme.model=M2481

$(call inherit-product, vendor/meizu/meizu21Pro-meizucamera/meizu21Pro-meizucamera-vendor.mk)
