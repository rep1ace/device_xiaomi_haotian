/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.haotian.touch;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public final class EdgeSuppressionService extends Service {
    private static final String TAG = "HaotianEdgeService";
    private static final long RETRY_DELAY_MS = 2000;
    private static final int BOOT_RETRY_COUNT = 20;
    private static final int EVENT_RETRY_COUNT = 3;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }

            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                requestApply(EVENT_RETRY_COUNT);
            }
        }
    };

    private EdgeSuppressionManager mEdgeSuppressionManager;
    private int mRemainingRetries;

    private final Runnable mApplyRunnable = new Runnable() {
        @Override
        public void run() {
            if (mEdgeSuppressionManager == null) {
                return;
            }

            if (mEdgeSuppressionManager.apply()) {
                mRemainingRetries = 0;
                return;
            }

            if (mRemainingRetries > 0) {
                mRemainingRetries--;
                mHandler.postDelayed(this, RETRY_DELAY_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        mEdgeSuppressionManager = new EdgeSuppressionManager(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        registerReceiver(mStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        requestApply(BOOT_RETRY_COUNT);
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        requestApply(EVENT_RETRY_COUNT);
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mApplyRunnable);
        try {
            unregisterReceiver(mStateReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "State receiver was already unregistered", e);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void requestApply(int retryCount) {
        mRemainingRetries = retryCount;
        mHandler.removeCallbacks(mApplyRunnable);
        mHandler.post(mApplyRunnable);
    }
}
