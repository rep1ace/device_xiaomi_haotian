/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.haotian.touch;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

public final class EdgeSuppressionService extends Service {
    private static final String TAG = "HaotianEdgeService";
    private static final long RETRY_DELAY_MS = 2000;
    private static final int BOOT_RETRY_COUNT = 20;
    private static final int EVENT_RETRY_COUNT = 3;
    private static final int USER_ALL = -1;

    private static final String REASON_BOOT = "boot";
    private static final String REASON_SCREEN_ON = "screenOn";
    private static final String REASON_SETTINGS = "settings";
    private static final String REASON_CONFIGURATION = "configuration";
    private static final String REASON_ROTATION = "rotation";
    private static final String REASON_GAME_BOOSTER = "gameBooster";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }

            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                requestApply(EVENT_RETRY_COUNT, REASON_SCREEN_ON);
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                requestApply(EVENT_RETRY_COUNT, REASON_CONFIGURATION);
            }
        }
    };
    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri != null && Settings.Secure.getUriFor(
                    EdgeSuppressionManager.SETTING_GAME_BOOSTER).equals(uri)) {
                if (mEdgeSuppressionManager != null && !mEdgeSuppressionManager.isGameMode()) {
                    requestApply(EVENT_RETRY_COUNT, REASON_GAME_BOOSTER);
                }
                return;
            }

            requestApply(EVENT_RETRY_COUNT, REASON_SETTINGS);
        }
    };
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY
                            && mEdgeSuppressionManager != null
                            && !mEdgeSuppressionManager.isGameMode()) {
                        requestApply(EVENT_RETRY_COUNT, REASON_ROTATION);
                    }
                }
            };

    private EdgeSuppressionManager mEdgeSuppressionManager;
    private DisplayManager mDisplayManager;
    private int mRemainingRetries;
    private String mPendingReason = REASON_BOOT;

    private final Runnable mApplyRunnable = new Runnable() {
        @Override
        public void run() {
            if (mEdgeSuppressionManager == null) {
                return;
            }

            if (mEdgeSuppressionManager.apply(mPendingReason)) {
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
        mDisplayManager = getSystemService(DisplayManager.class);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        registerReceiver(mStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        registerSettingsObservers();

        if (mDisplayManager != null) {
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        requestApply(BOOT_RETRY_COUNT, REASON_BOOT);
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        requestApply(EVENT_RETRY_COUNT, REASON_CONFIGURATION);
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mApplyRunnable);
        getContentResolver().unregisterContentObserver(mSettingsObserver);
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
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

    private void requestApply(int retryCount, String reason) {
        mPendingReason = reason;
        mRemainingRetries = retryCount;
        mHandler.removeCallbacks(mApplyRunnable);
        mHandler.post(mApplyRunnable);
    }

    private void registerSettingsObservers() {
        ContentResolver resolver = getContentResolver();
        resolver.registerContentObserver(Settings.System.getUriFor(
                EdgeSuppressionManager.SETTING_EDGE_TYPE), false, mSettingsObserver, USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                EdgeSuppressionManager.SETTING_EDGE_SIZE), false, mSettingsObserver, USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                EdgeSuppressionManager.SETTING_GAME_BOOSTER), false, mSettingsObserver, USER_ALL);
    }
}
