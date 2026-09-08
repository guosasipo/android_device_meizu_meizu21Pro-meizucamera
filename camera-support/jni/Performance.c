/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "MeizuCameraBoost"

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>

#define PERF_CLIENT_LIBRARY "libqti-perfd-client.so"
#define MAX_RESOURCES 64

typedef int (*perf_lock_acq_fn)(int handle, int duration, int resources[],
                                int count);
typedef int (*perf_lock_rel_fn)(int handle);

static pthread_once_t perf_client_once = PTHREAD_ONCE_INIT;
static perf_lock_acq_fn perf_lock_acq;
static perf_lock_rel_fn perf_lock_rel;

static void load_perf_client(void) {
  void *library = dlopen(PERF_CLIENT_LIBRARY, RTLD_NOW | RTLD_LOCAL);
  if (library == NULL) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Unable to load %s: %s",
                        PERF_CLIENT_LIBRARY, dlerror());
    return;
  }

  perf_lock_acq = (perf_lock_acq_fn)dlsym(library, "perf_lock_acq");
  perf_lock_rel = (perf_lock_rel_fn)dlsym(library, "perf_lock_rel");
  if (perf_lock_acq == NULL || perf_lock_rel == NULL) {
    __android_log_print(
        ANDROID_LOG_ERROR, LOG_TAG,
        "Unable to resolve Qualcomm performance client symbols");
    perf_lock_acq = NULL;
    perf_lock_rel = NULL;
  }
}

static jint acquire(JNIEnv *env, jint handle, jint duration_ms,
                    jintArray resources) {
  if (resources == NULL || duration_ms <= 0) {
    return -1;
  }

  jsize count = (*env)->GetArrayLength(env, resources);
  if (count <= 0 || count > MAX_RESOURCES || (count & 1) != 0) {
    return -1;
  }

  jint values[MAX_RESOURCES];
  (*env)->GetIntArrayRegion(env, resources, 0, count, values);
  if ((*env)->ExceptionCheck(env)) {
    return -1;
  }

  pthread_once(&perf_client_once, load_perf_client);
  if (perf_lock_acq == NULL) {
    return -1;
  }
  return perf_lock_acq(handle, duration_ms, values, count);
}

static jint release(jint handle) {
  pthread_once(&perf_client_once, load_perf_client);
  if (perf_lock_rel == NULL || handle <= 0) {
    return -1;
  }
  return perf_lock_rel(handle);
}

JNIEXPORT jint JNICALL
Java_com_meizu_perf_sdk_BoostAffinityService_nativeAcquire(
    JNIEnv *env, jclass clazz, jint duration_ms, jintArray resources) {
  (void)clazz;
  return acquire(env, 0, duration_ms, resources);
}

JNIEXPORT jint JNICALL
Java_com_meizu_perf_sdk_BoostAffinityService_nativeRelease(JNIEnv *env,
                                                           jclass clazz,
                                                           jint handle) {
  (void)env;
  (void)clazz;
  return release(handle);
}

JNIEXPORT jint JNICALL Java_com_qualcomm_qti_Performance_nativePerfLockAcquire(
    JNIEnv *env, jclass clazz, jint handle, jint duration_ms,
    jintArray resources) {
  (void)clazz;
  return acquire(env, handle, duration_ms, resources);
}

JNIEXPORT jint JNICALL Java_com_qualcomm_qti_Performance_nativePerfLockRelease(
    JNIEnv *env, jclass clazz, jint handle) {
  (void)env;
  (void)clazz;
  return release(handle);
}
