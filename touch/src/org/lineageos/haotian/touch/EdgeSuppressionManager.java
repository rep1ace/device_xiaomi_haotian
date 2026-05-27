/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.haotian.touch;

import android.content.Context;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.util.Arrays;

final class EdgeSuppressionManager {
    private static final String TAG = "HaotianEdgeSuppression";

    private static final String PROP_ENABLED =
            "persist.vendor.touchfeature.lineage.edge_suppression.enabled";
    private static final String PROP_ABSOLUTE_WIDTH =
            "persist.vendor.touchfeature.lineage.edge_suppression.absolute_width";
    private static final String PROP_CONDITION_WIDTH =
            "persist.vendor.touchfeature.lineage.edge_suppression.condition_width";

    private static final int MODE_CORNER = 0;
    private static final int MODE_CONDITION = 1;
    private static final int MODE_ABSOLUTE = 2;

    private static final int POSITION_TOP = 0;
    private static final int POSITION_BOTTOM = 1;
    private static final int POSITION_LEFT = 2;
    private static final int POSITION_RIGHT = 3;

    private final Context mContext;
    private final TouchFeatureClient mTouchFeatureClient = new TouchFeatureClient();

    private final boolean mDefaultEnabled;
    private final int mDefaultAbsoluteWidth;
    private final int mDefaultConditionWidth;
    private final int[] mCorner;
    private final int mSendSize;

    EdgeSuppressionManager(Context context) {
        mContext = context.getApplicationContext();

        Resources resources = mContext.getResources();
        mDefaultEnabled = resources.getBoolean(R.bool.config_edge_suppression_enabled);
        mDefaultAbsoluteWidth = resources.getInteger(
                R.integer.config_edge_suppression_absolute_width);
        mDefaultConditionWidth = resources.getInteger(
                R.integer.config_edge_suppression_condition_width);
        mCorner = resources.getIntArray(R.array.config_edge_suppression_corner);
        mSendSize = resources.getInteger(R.integer.config_edge_suppression_send_size);
    }

    boolean apply() {
        DisplayMetrics metrics = getRealDisplayMetrics();
        if (metrics == null) {
            return false;
        }

        int screenWidth = Math.max(0, Math.min(metrics.widthPixels, metrics.heightPixels) - 1);
        int screenHeight = Math.max(0, Math.max(metrics.widthPixels, metrics.heightPixels) - 1);
        int rotation = getRotation();

        boolean enabled = SystemProperties.getBoolean(PROP_ENABLED, mDefaultEnabled);
        int[] values = enabled
                ? buildSuppressionRectangles(screenWidth, screenHeight, rotation)
                : new int[mSendSize];

        boolean success = mTouchFeatureClient.setEdgeSuppression(values);
        if (success) {
            Log.i(TAG, "Applied edge suppression enabled=" + enabled
                    + " rotation=" + rotation + " display=" + screenWidth + "x" + screenHeight);
        }
        return success;
    }

    private DisplayMetrics getRealDisplayMetrics() {
        WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        if (windowManager == null || windowManager.getDefaultDisplay() == null) {
            Log.w(TAG, "Display is not available");
            return null;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }

    private int getRotation() {
        WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        if (windowManager == null || windowManager.getDefaultDisplay() == null) {
            return Surface.ROTATION_0;
        }
        return windowManager.getDefaultDisplay().getRotation();
    }

    private int[] buildSuppressionRectangles(int screenWidth, int screenHeight, int rotation) {
        int[] values = new int[mSendSize];
        int[] index = new int[] {0};

        int maxWidth = Math.max(0, screenWidth / 2);
        int absoluteWidth = clamp(SystemProperties.getInt(PROP_ABSOLUTE_WIDTH,
                mDefaultAbsoluteWidth), 0, maxWidth);
        int conditionWidth = clamp(SystemProperties.getInt(PROP_CONDITION_WIDTH,
                mDefaultConditionWidth), 0, maxWidth);

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            setRectPointForHorizontal(values, index, screenWidth, screenHeight, absoluteWidth,
                    MODE_ABSOLUTE);
            setRectPointForHorizontal(values, index, screenWidth, screenHeight, conditionWidth,
                    MODE_CONDITION);
        } else {
            setRectPointForPortrait(values, index, screenWidth, screenHeight, absoluteWidth,
                    MODE_ABSOLUTE);
            setRectPointForPortrait(values, index, screenWidth, screenHeight, conditionWidth,
                    MODE_CONDITION);
        }

        setCornerRectPoint(values, index, screenWidth, screenHeight, rotation);
        if (index[0] != mSendSize) {
            Log.w(TAG, "Unexpected edge suppression payload size " + index[0]
                    + ", expected " + mSendSize + ": " + Arrays.toString(values));
        }
        return values;
    }

    private static void setRectPointForHorizontal(int[] values, int[] index, int screenWidth,
            int screenHeight, int width, int mode) {
        addRect(values, index, mode, POSITION_TOP, 0, 0, screenWidth, width);
        addRect(values, index, mode, POSITION_BOTTOM, 0, screenHeight - width, screenWidth,
                screenHeight);
        addRect(values, index, mode, POSITION_LEFT, 0, 0, width, screenHeight);
        addRect(values, index, mode, POSITION_RIGHT, screenWidth - width, 0, screenWidth,
                screenHeight);
    }

    private static void setRectPointForPortrait(int[] values, int[] index, int screenWidth,
            int screenHeight, int width, int mode) {
        addRect(values, index, mode, POSITION_TOP, 0, 0, 0, 0);
        addRect(values, index, mode, POSITION_BOTTOM, 0, 0, 0, 0);
        addRect(values, index, mode, POSITION_LEFT, 0, 0, width, screenHeight);
        addRect(values, index, mode, POSITION_RIGHT, screenWidth - width, 0, screenWidth,
                screenHeight);
    }

    private void setCornerRectPoint(int[] values, int[] index, int screenWidth, int screenHeight,
            int rotation) {
        int portraitWidth = getCorner(0, screenWidth);
        int portraitHeight = getCorner(1, screenHeight);
        int landscapeWidth = getCorner(2, screenWidth);
        int landscapeHeight = getCorner(3, screenHeight);

        switch (rotation) {
            case Surface.ROTATION_90:
                addRect(values, index, MODE_CORNER, POSITION_TOP, 0, 0, landscapeWidth,
                        landscapeHeight);
                addRect(values, index, MODE_CORNER, POSITION_BOTTOM, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_LEFT, 0,
                        screenHeight - landscapeHeight, landscapeWidth, screenHeight);
                addRect(values, index, MODE_CORNER, POSITION_RIGHT, 0, 0, 0, 0);
                break;
            case Surface.ROTATION_180:
                addRect(values, index, MODE_CORNER, POSITION_TOP, 0, 0, portraitWidth,
                        portraitHeight);
                addRect(values, index, MODE_CORNER, POSITION_BOTTOM, screenWidth - portraitWidth,
                        0, screenWidth, portraitHeight);
                addRect(values, index, MODE_CORNER, POSITION_LEFT, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_RIGHT, 0, 0, 0, 0);
                break;
            case Surface.ROTATION_270:
                addRect(values, index, MODE_CORNER, POSITION_TOP, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_BOTTOM, screenWidth - landscapeWidth,
                        0, screenWidth, landscapeHeight);
                addRect(values, index, MODE_CORNER, POSITION_LEFT, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_RIGHT, screenWidth - landscapeWidth,
                        screenHeight - landscapeHeight, screenWidth, screenHeight);
                break;
            case Surface.ROTATION_0:
            default:
                addRect(values, index, MODE_CORNER, POSITION_TOP, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_BOTTOM, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_LEFT, 0, screenHeight - portraitHeight,
                        portraitWidth, screenHeight);
                addRect(values, index, MODE_CORNER, POSITION_RIGHT, screenWidth - portraitWidth,
                        screenHeight - portraitHeight, screenWidth, screenHeight);
                break;
        }
    }

    private int getCorner(int index, int max) {
        int value = index < mCorner.length ? mCorner[index] : 0;
        return clamp(value, 0, max);
    }

    private static void addRect(int[] values, int[] index, int type, int position, int left,
            int top, int right, int bottom) {
        int i = index[0];
        values[i++] = type;
        values[i++] = position;
        values[i++] = left;
        values[i++] = top;
        values[i++] = right;
        values[i++] = bottom;
        values[i++] = 0;
        values[i++] = 0;
        index[0] = i;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
