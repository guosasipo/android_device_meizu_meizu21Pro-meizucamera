#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from pathlib import PurePosixPath
from shutil import copyfile
from zipfile import ZipFile, ZipInfo

from extract_utils.fixups_blob import blob_fixup
from extract_utils.fixups_lib import lib_fixups
from extract_utils.main import ExtractUtils, ExtractUtilsModule


def lib_fixup_camera_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}_meizucamera' if partition == 'system' else None


def extract_filter_assets(ctx, file, file_path, *args, tmp_dir=None):
    archive_path = f'{tmp_dir}/filters.zip'
    with ZipFile(file_path) as source, ZipFile(archive_path, 'w') as output:
        for entry in source.infolist():
            if entry.is_dir() or not entry.filename.startswith((
                'assets/classicFilter/', 'assets/filterManager/',
            )):
                continue
            asset = PurePosixPath(entry.filename).relative_to('assets')
            if len(asset.parts) != 2 or '..' in asset.parts:
                raise ValueError(f'Invalid filter asset: {asset}')
            info = ZipInfo(str(asset), entry.date_time)
            info.compress_type = entry.compress_type
            output.writestr(info, source.read(entry))
    copyfile(archive_path, file_path)


lib_fixups = {
    **lib_fixups,
    (
        'libOpenCL',
        'libarcsoft_qnnhtp',
        'libc++_shared',
        'libcdsprpc',
        'libmpbase',
    ): lib_fixup_camera_suffix,
}

blob_fixups = {
    'system/etc/meizucamera/filters.zip': blob_fixup()
        .call(extract_filter_assets),
    'system/priv-app/MeizuCamera/MeizuCamera.apk': blob_fixup()
        .apktool_patch('blob-patches'),
    'system/app/MeizuPhotoService/MeizuPhotoService.apk': blob_fixup()
        .apktool_patch('photoservice-patches'),
    'system/lib64/libjni_offlinepostproc.so': blob_fixup()
        .replace_needed(
            'vendor.qti.hardware.camera.postproc-V1-ndk.so',
            'vendor.qti.hardware.camera.postproc-V1-ndk-meizu.so',
        ),
}

module = ExtractUtilsModule(
    'meizu21Pro-meizucamera',
    'meizu',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=['device/meizu/meizu21Pro-meizucamera'],
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
