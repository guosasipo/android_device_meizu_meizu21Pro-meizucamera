/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.meizu.perf.sdk;

oneway interface IBoostAffinity {
    void requestBoostAffinity(String scene, long levelFlags, in @nullable int[] tids);
    void cancelBoostAffinity(String scene, in @nullable int[] tids);
}
