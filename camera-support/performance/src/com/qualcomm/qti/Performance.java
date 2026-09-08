/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.qualcomm.qti;

import android.util.Log;

public final class Performance {
    private static final String TAG = "MeizuCameraPerfLock";
    private static final int REQUEST_FAILED = -1;

    private static final boolean NATIVE_AVAILABLE;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("meizu_camera_performance_jni");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Unable to load performance client", e);
        }
        NATIVE_AVAILABLE = loaded;
    }

    private int mHandle;

    public Performance() {}

    public synchronized int perfLockAcquire(int duration, int... resources) {
        if (!NATIVE_AVAILABLE) {
            return REQUEST_FAILED;
        }

        mHandle = nativePerfLockAcquire(mHandle, duration, resources);
        return mHandle > 0 ? mHandle : REQUEST_FAILED;
    }

    public synchronized int perfLockRelease() {
        if (!NATIVE_AVAILABLE || mHandle <= 0) {
            return REQUEST_FAILED;
        }

        int result = nativePerfLockRelease(mHandle);
        mHandle = 0;
        return result;
    }

    private static native int nativePerfLockAcquire(int handle, int duration, int[] resources);

    private static native int nativePerfLockRelease(int handle);
}
