#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2024 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

import extract_utils.tools
from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixup_remove,
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'device/xiaomi/sm8750-common',
    'hardware/qcom-caf/sm8750',
    'hardware/xiaomi',
    'vendor/qcom/opensource/commonsys-intf/display',
    'vendor/xiaomi/sm8750-common',
]

blob_fixups: blob_fixups_user_type = {
    (
        'odm/etc/camera/motiontuning.xml',
        'odm/etc/camera/snsc_bokeh_motiontuning.xml',
        'odm/etc/camera/snsc_enhance_motiontuning.xml',
        'odm/etc/camera/snsc_noface_motiontuning.xml',
        'odm/etc/camera/enhance_motiontuning.xml',
        'odm/etc/camera/snsc_motiontuning.xml'
    ): blob_fixup()
        .regex_replace('xml=version', 'xml version'),
    (
    'vendor/lib64/libcameraopt.so',
    ): blob_fixup().add_needed('libprocessgroup_shim.so'),
    (
        'odm/lib64/libanc_dc_plugin_xiaomi_v3.so',
    ): blob_fixup()
        .add_needed('libc++_shared.so'),
    (
        'odm/lib64/libMiEmojiEffect.so',
        'odm/lib64/libMiVideoFilter.so',
        'odm/lib64/libAncHumanPreviewBokeh.so',
        'odm/lib64/libTrueSight.so',
        'odm/lib64/libwa_widelens_undistort.so',
        'odm/lib64/libMiPhotoFilter.so'
    ): blob_fixup()
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_lockPlanes')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock')
        .clear_symbol_version('AHardwareBuffer_lock')
        .clear_symbol_version('AHardwareBuffer_isSupported'),
    (
       'odm/lib64/camera/components/com.qti.node.dewarp.so',
       'odm/lib64/hw/com.qti.chi.override.so',
       'odm/lib64/libcamximageformatutils.so',
       'odm/lib64/libchifeature2.so',
       'odm/lib64/vendor.qti.hardware.camera.offlinecamera-service-impl.so',
    ): blob_fixup()
        .remove_needed('android.hardware.graphics.allocator-V1-ndk.so'),
    (
       'vendor/lib64/vendor.xiaomi.hardware.camera.injection-V1-ndk.so',
       'vendor/lib64/vendor.xiaomi.hardware.camera.injection-client.so',
       'vendor/lib64/vendor.xiaomi.hardware.camera.injection-service.so',
    ): blob_fixup()
        .replace_needed(
            'android.hardware.camera.device-V1-ndk.so',
            'android.hardware.camera.device-V2-ndk.so'
        ),
    (
       'odm/lib64/hw/camera.qcom.so',
    ): blob_fixup()
        .replace_needed(
            'android.hardware.sensors-V2-ndk.so',
            'android.hardware.sensors-V3-ndk.so'
        ),
    (
        'odm/bin/hw/vendor.qti.camera.provider-service_64',
        'odm/lib64/com.xiaomi.plugin.ecdengine.so',
        'odm/lib64/libcamxcoreutils.so',
        'odm/lib64/libcamxods.so',
        'odm/lib64/libmicamera_aidl_provider.so',
        'odm/lib64/libmicamera_hal_core.so',
        'odm/lib64/libsimulation.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.anchor.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineawbideal.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineb2y.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineformatconvertor.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinehdrraw2y.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineheic.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinei2y.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinejpeg.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinemfnr.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinemlawb.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinetintless.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinetintlesshdr.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineyuvreprocess.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineyuvsplit.so',
    ): blob_fixup()
        .binary_regex_replace(b'libtinyxml2.so\0', b'libtinyxmlQ.so\0'),
    'vendor/lib64/libultrahdr_haotian.so': blob_fixup()
        .replace_needed(
            'libjpegencoder.so',
            'libjpegencoder_haotian.so'
        )
        .replace_needed(
            'libjpegdecoder.so',
            'libjpegdecoder_haotian.so'
        ),
    ('odm/lib64/camera/plugins/com.xiaomi.plugin.jpegrAggr.so', 'odm/lib64/camera/plugins/com.xiaomi.plugin.gainmap.so'): blob_fixup()
        .replace_needed(
            'libultrahdr.so',
            'libultrahdr_haotian.so'
        ),
    (
        'vendor/lib64/libcamera2ndk_vendor.so',
    ): blob_fixup()
        .replace_needed('android.frameworks.cameraservice.device-V2-ndk.so', 'android.frameworks.cameraservice.device-V3-ndk.so')
        .replace_needed('android.frameworks.cameraservice.service-V2-ndk.so', 'android.frameworks.cameraservice.service-V3-ndk.so'),
    'odm/etc/camera/xiaomi/ecoMetaExtensionExt.json': blob_fixup()
        # Force Xiaomi's eco engine to keep third-party JPEG_R disabled even
        # when /data/property still carries an older persisted value of 1.
        .regex_replace(
            r'("Signature":"MiviThirdJpegr"[\s\S]*?"Name": "persist\.vendor\.camera\.sdk\.third\.jpegr\.enable",\s*"Value": )\["", "0"\]',
            r'\1["", "0", "1"]'
        )
        .regex_replace(
            r'("Signature":"MiviThirdJpegr"[\s\S]*?"Name": "persist\.vendor\.camera\.sdk\.third\.jpegr\.enable",\s*"Value": )"1"',
            r'\1"0"'
        ),
    'odm/etc/camera/mihal_overlap/overlap_config.json': blob_fixup()
        .regex_replace(
            r'"CAMX__JPEGR_STREAM_CONFIG_SIZES_3Party_FRONT": \[[\s\S]*?\n    \],',
            '"CAMX__JPEGR_STREAM_CONFIG_SIZES_3Party_FRONT": [],'
        )
        .regex_replace(
            r'"CAMX__JPEGR_STREAM_CONFIG_SIZES_3Party_REAR": \[[\s\S]*?\n    \],',
            '"CAMX__JPEGR_STREAM_CONFIG_SIZES_3Party_REAR": [],'
        ),
    'odm/etc/sensors/config/sm8750_tcs3720_fb.json': blob_fixup()
        .regex_replace(
            r'"near_threshold":\{ "type": "flt", "ver": "[0-9]+",\n          "data": "(?:140\.0|105\.0|105)"\n        \}',
            '"near_threshold":{ "type": "flt", "ver": "3",\n          "data": "90.0"\n        }',
        )
        .regex_replace(
            r'"far_threshold":\{ "type": "flt", "ver": "[0-9]+",\n          "data": "(?:80\.0|65\.0|65)"\n        \}',
            '"far_threshold":{ "type": "flt", "ver": "3",\n          "data": "55.0"\n        }',
        )
        .regex_replace(
            r'"parm0":\{ "type": "flt", "ver": "[0-9]+",\n          "data": "(?:130\.0|105\.0|105)"\n        \}',
            '"parm0":{ "type": "flt", "ver": "2",\n          "data": "90.0"\n        }',
        ),
}

module = ExtractUtilsModule(
    'haotian',
    'xiaomi',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    check_elf=True,
    add_firmware_proprietary_file=True,
)

if __name__ == '__main__':
    utils = ExtractUtils.device_with_common(
        module, 'sm8750-common', module.vendor
    )
    utils.run()