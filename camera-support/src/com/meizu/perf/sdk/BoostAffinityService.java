/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.meizu.perf.sdk;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BoostAffinityService extends Service {
    private static final String TAG = "MeizuCameraBoost";
    private static final String BIND_PERMISSION =
            "com.meizu.media.camera.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";

    private static final int BOOST_DURATION_MS = 10_000;
    private static final int MAX_THREAD_IDS = 8;
    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 3;

    private static final int MPCTLV3_MIN_FREQ_CLUSTER_BIG_CORE_0 = 0x40800000;
    private static final int MPCTLV3_SCHED_TASK_BOOST = 0x40c80000;
    private static final int MPCTLV3_STORAGE_CLK_SCALING_DISABLE = 0x42c10000;

    private static final int[] PROFILE_RESOURCES = {
        MPCTLV3_MIN_FREQ_CLUSTER_BIG_CORE_0, 1920, MPCTLV3_STORAGE_CLK_SCALING_DISABLE, 1,
    };

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

    private final SparseArray<LockState> mLocks = new SparseArray<>();

    private Handler mHandler;
    private HandlerThread mHandlerThread;

    private final IBoostAffinity.Stub mBinder =
            new IBoostAffinity.Stub() {
                @Override
                public void requestBoostAffinity(String scene, long levelFlags, int[] tids) {
                    int uid = Binder.getCallingUid();
                    if (!isCallerAllowed(uid)) {
                        return;
                    }

                    int[] tidsCopy = tids == null ? null : tids.clone();
                    mHandler.post(() -> acquire(uid, levelFlags, tidsCopy));
                }

                @Override
                public void cancelBoostAffinity(String scene, int[] tids) {
                    int uid = Binder.getCallingUid();
                    if (!isCallerAllowed(uid)) {
                        return;
                    }

                    mHandler.post(() -> release(uid));
                }
            };

    private static native int nativeAcquire(int durationMs, int[] resources);

    private static native int nativeRelease(int handle);

    @Override
    public void onCreate() {
        super.onCreate();
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = Handler.createAsync(mHandlerThread.getLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mHandler.post(this::releaseAll);
        return false;
    }

    @Override
    public void onDestroy() {
        Handler handler = mHandler;
        HandlerThread thread = mHandlerThread;
        if (handler != null && thread != null) {
            handler.removeCallbacksAndMessages(null);
            handler.post(
                    () -> {
                        releaseAll();
                        thread.quitSafely();
                    });
        }
        super.onDestroy();
    }

    private boolean isCallerAllowed(int uid) {
        if (checkCallingPermission(BIND_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        Log.w(TAG, "Rejected caller uid " + uid);
        return false;
    }

    private void acquire(int uid, long levelFlags, int[] tids) {
        if (!NATIVE_AVAILABLE) {
            return;
        }

        int level = (int) (levelFlags & 0xffL);
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            Log.w(TAG, "Rejected performance level " + level);
            return;
        }

        release(uid);

        LockState state = new LockState();
        addHandle(state, nativeAcquire(BOOST_DURATION_MS, PROFILE_RESOURCES));

        if (tids != null) {
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < tids.length && seen.size() < MAX_THREAD_IDS; i++) {
                int tid = tids[i];
                if (tid <= 0 || !seen.add(tid) || !isThreadOwnedBy(tid, uid)) {
                    continue;
                }
                addHandle(
                        state,
                        nativeAcquire(
                                BOOST_DURATION_MS, new int[] {MPCTLV3_SCHED_TASK_BOOST, tid}));
            }
        }

        if (state.handles.isEmpty()) {
            return;
        }

        mLocks.put(uid, state);
        mHandler.postDelayed(() -> expire(uid, state), BOOST_DURATION_MS);
    }

    private static void addHandle(LockState state, int handle) {
        if (handle > 0) {
            state.handles.add(handle);
        }
    }

    private static boolean isThreadOwnedBy(int tid, int uid) {
        try {
            return Os.stat("/proc/" + tid).st_uid == uid;
        } catch (ErrnoException e) {
            return false;
        }
    }

    private void expire(int uid, LockState expected) {
        if (mLocks.get(uid) == expected) {
            release(uid);
        }
    }

    private void release(int uid) {
        LockState state = mLocks.get(uid);
        if (state == null) {
            return;
        }
        mLocks.remove(uid);
        release(state);
    }

    private static void release(LockState state) {
        for (int handle : state.handles) {
            nativeRelease(handle);
        }
        state.handles.clear();
    }

    private void releaseAll() {
        for (int i = mLocks.size() - 1; i >= 0; i--) {
            release(mLocks.valueAt(i));
        }
        mLocks.clear();
    }

    private static final class LockState {
        final List<Integer> handles = new ArrayList<>();
    }
}
