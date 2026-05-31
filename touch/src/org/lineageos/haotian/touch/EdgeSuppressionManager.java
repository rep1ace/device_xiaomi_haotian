/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.haotian.touch;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class EdgeSuppressionManager {
    private static final String TAG = "HaotianEdgeSuppression";

    static final String SETTING_EDGE_TYPE = "edge_type";
    static final String SETTING_EDGE_SIZE = "edge_size";
    static final String SETTING_GAME_BOOSTER = "gb_boosting";

    private static final String PROP_ENABLED =
            "persist.vendor.touchfeature.lineage.edge_suppression.enabled";
    private static final String PROP_TOUCHFEATURE_TYPE = "ro.vendor.touchfeature.type";

    private static final String KEY_SCREEN_EDGE_MODE_DEFAULT = "default_suppression";
    private static final String KEY_SCREEN_EDGE_MODE_WAKE = "wake_suppression";
    private static final String KEY_SCREEN_EDGE_MODE_STRONG = "strong_suppression";
    private static final String KEY_SCREEN_EDGE_MODE_CUSTOM = "custom_suppression";
    private static final String KEY_SCREEN_EDGE_MODE_DIY = "diy_suppression";

    private static final int USER_CURRENT = -2;
    private static final int FEATURE_EDGE_MODE = 0x4;

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
    private final int mSendSize;
    private final EdgeSuppressionConfig mConfig;

    EdgeSuppressionManager(Context context) {
        mContext = context.getApplicationContext();

        Resources resources = mContext.getResources();
        mDefaultEnabled = resources.getBoolean(R.bool.config_edge_suppression_enabled);
        mSendSize = resources.getInteger(R.integer.config_edge_suppression_send_size);
        mConfig = EdgeSuppressionConfig.load(resources);
    }

    boolean apply(String reason) {
        if (!isEdgeModeSupported()) {
            Log.w(TAG, "Skipping edge suppression; unsupported touchfeature type="
                    + SystemProperties.getInt(PROP_TOUCHFEATURE_TYPE, 0));
            return true;
        }

        ScreenSize screenSize = getScreenSize();
        if (screenSize == null) {
            return false;
        }

        int rotation = getRotation();
        boolean enabled = SystemProperties.getBoolean(PROP_ENABLED, mDefaultEnabled);
        String edgeModeType = enabled ? getCurrentEdgeModeType() : "disabled";
        int[] values = enabled
                ? buildSuppressionRectangles(screenSize.width, screenSize.height, rotation,
                        edgeModeType)
                : new int[mSendSize];

        boolean success = mTouchFeatureClient.setEdgeSuppression(values);
        if (success) {
            int firstRectSize = Math.min(8, values.length);
            Log.i(TAG, "Applied edge suppression reason=" + reason + " enabled=" + enabled
                    + " type=" + edgeModeType + " rotation=" + rotation
                    + " display=" + screenSize.width + "x" + screenSize.height
                    + " payload=" + values.length
                    + " firstRect=" + Arrays.toString(Arrays.copyOf(values, firstRectSize)));
        }
        return success;
    }

    boolean isGameMode() {
        return Settings.Secure.getInt(mContext.getContentResolver(), SETTING_GAME_BOOSTER, 0) == 1;
    }

    private boolean isEdgeModeSupported() {
        return (SystemProperties.getInt(PROP_TOUCHFEATURE_TYPE, 0) & FEATURE_EDGE_MODE) != 0;
    }

    private ScreenSize getScreenSize() {
        WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        if (windowManager == null || windowManager.getDefaultDisplay() == null) {
            Log.w(TAG, "Display is not available");
            return null;
        }

        Display display = windowManager.getDefaultDisplay();
        int maxPhysicalWidth = 0;
        int maxPhysicalHeight = 0;
        for (Display.Mode mode : display.getSupportedModes()) {
            maxPhysicalWidth = Math.max(maxPhysicalWidth, mode.getPhysicalWidth());
            maxPhysicalHeight = Math.max(maxPhysicalHeight, mode.getPhysicalHeight());
        }

        if (maxPhysicalWidth <= 0 || maxPhysicalHeight <= 0) {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            maxPhysicalWidth = metrics.widthPixels;
            maxPhysicalHeight = metrics.heightPixels;
        }

        int screenWidth = Math.max(0, Math.min(maxPhysicalWidth, maxPhysicalHeight) - 1);
        int screenHeight = Math.max(0, Math.max(maxPhysicalWidth, maxPhysicalHeight) - 1);
        return new ScreenSize(screenWidth, screenHeight);
    }

    private int getRotation() {
        WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        if (windowManager == null || windowManager.getDefaultDisplay() == null) {
            return Surface.ROTATION_0;
        }
        return windowManager.getDefaultDisplay().getRotation();
    }

    private String getCurrentEdgeModeType() {
        String edgeType = Settings.System.getStringForUser(mContext.getContentResolver(),
                SETTING_EDGE_TYPE, USER_CURRENT);
        if (edgeType == null) {
            return KEY_SCREEN_EDGE_MODE_DEFAULT;
        }
        if (KEY_SCREEN_EDGE_MODE_DIY.equals(edgeType)) {
            return KEY_SCREEN_EDGE_MODE_CUSTOM;
        }
        if (mConfig.isKnownMode(edgeType)) {
            return edgeType;
        }
        Log.w(TAG, "Unknown edge mode type " + edgeType + "; using default");
        return KEY_SCREEN_EDGE_MODE_DEFAULT;
    }

    private int[] buildSuppressionRectangles(int screenWidth, int screenHeight, int rotation,
            String edgeModeType) {
        int[] values = new int[mSendSize];
        int[] index = new int[] {0};
        boolean horizontal = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        EdgeSuppressionInfo info = mConfig.getInfo(edgeModeType, horizontal);

        if (KEY_SCREEN_EDGE_MODE_CUSTOM.equals(edgeModeType)) {
            int conditionSize = clamp((int) getCurrentEdgeModeSize(),
                    mConfig.getMinConditionSize(), mConfig.getMaxConditionSize());
            info = info.withSizes(getAbsoluteSize(conditionSize), conditionSize);
        }

        if (horizontal) {
            setRectPointForHorizontal(values, index, screenWidth, screenHeight,
                    info.absoluteSize, MODE_ABSOLUTE);
            setRectPointForHorizontal(values, index, screenWidth, screenHeight,
                    info.conditionSize, MODE_CONDITION);
        } else {
            setRectPointForPortrait(values, index, screenWidth, screenHeight,
                    info.absoluteSize, MODE_ABSOLUTE);
            setRectPointForPortrait(values, index, screenWidth, screenHeight,
                    info.conditionSize, MODE_CONDITION);
        }

        setCornerRectPoint(values, index, screenWidth, screenHeight, rotation,
                info.connerWidth, info.connerHeight);
        if (index[0] != mSendSize) {
            Log.w(TAG, "Unexpected edge suppression payload size " + index[0]
                    + ", expected " + mSendSize + ": " + Arrays.toString(values));
        }
        return values;
    }

    private float getCurrentEdgeModeSize() {
        float edgeModeSize = mConfig.getDefaultConditionSize();
        String edgeSize = Settings.System.getStringForUser(mContext.getContentResolver(),
                SETTING_EDGE_SIZE, USER_CURRENT);
        if (edgeSize != null) {
            try {
                edgeModeSize = Float.parseFloat(edgeSize);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid edge_size " + edgeSize + "; using default", e);
            }
        }

        if (Float.isNaN(edgeModeSize) || Float.isInfinite(edgeModeSize)) {
            edgeModeSize = mConfig.getDefaultConditionSize();
        }
        if (edgeModeSize <= 1.0f) {
            edgeModeSize *= mConfig.maxAdjustValue;
        }
        return edgeModeSize;
    }

    private int getAbsoluteSize(float conditionSize) {
        int absoluteSize = mConfig.absoluteLevel[0];
        for (int i = 0; i < mConfig.conditionLevel.length - 1; i++) {
            if (Float.compare(conditionSize, mConfig.conditionLevel[i]) > 0
                    && Float.compare(conditionSize, mConfig.conditionLevel[i + 1]) <= 0) {
                absoluteSize = mConfig.absoluteLevel[i + 1];
                break;
            }
        }
        return absoluteSize;
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

    private static void setCornerRectPoint(int[] values, int[] index, int screenWidth,
            int screenHeight, int rotation, int cornerWidth, int cornerHeight) {
        switch (rotation) {
            case Surface.ROTATION_90:
                addRect(values, index, MODE_CORNER, POSITION_TOP, 0, 0, cornerWidth,
                        cornerHeight);
                addRect(values, index, MODE_CORNER, POSITION_BOTTOM, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_LEFT, 0,
                        screenHeight - cornerHeight, cornerWidth, screenHeight);
                addRect(values, index, MODE_CORNER, POSITION_RIGHT, 0, 0, 0, 0);
                break;
            case Surface.ROTATION_180:
                addRect(values, index, MODE_CORNER, POSITION_TOP, 0, 0, cornerWidth,
                        cornerHeight);
                addRect(values, index, MODE_CORNER, POSITION_BOTTOM, screenWidth - cornerWidth,
                        0, screenWidth, cornerHeight);
                addRect(values, index, MODE_CORNER, POSITION_LEFT, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_RIGHT, 0, 0, 0, 0);
                break;
            case Surface.ROTATION_270:
                addRect(values, index, MODE_CORNER, POSITION_TOP, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_BOTTOM, screenWidth - cornerWidth,
                        0, screenWidth, cornerHeight);
                addRect(values, index, MODE_CORNER, POSITION_LEFT, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_RIGHT, screenWidth - cornerWidth,
                        screenHeight - cornerHeight, screenWidth, screenHeight);
                break;
            case Surface.ROTATION_0:
            default:
                addRect(values, index, MODE_CORNER, POSITION_TOP, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_BOTTOM, 0, 0, 0, 0);
                addRect(values, index, MODE_CORNER, POSITION_LEFT, 0,
                        screenHeight - cornerHeight, cornerWidth, screenHeight);
                addRect(values, index, MODE_CORNER, POSITION_RIGHT, screenWidth - cornerWidth,
                        screenHeight - cornerHeight, screenWidth, screenHeight);
                break;
        }
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

    private static final class ScreenSize {
        final int width;
        final int height;

        ScreenSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class EdgeSuppressionInfo {
        final String type;
        final boolean isHorizontal;
        final int absoluteSize;
        final int conditionSize;
        final int connerWidth;
        final int connerHeight;

        EdgeSuppressionInfo(String type, boolean isHorizontal, int absoluteSize,
                int conditionSize, int connerWidth, int connerHeight) {
            this.type = type;
            this.isHorizontal = isHorizontal;
            this.absoluteSize = absoluteSize;
            this.conditionSize = conditionSize;
            this.connerWidth = connerWidth;
            this.connerHeight = connerHeight;
        }

        EdgeSuppressionInfo withSizes(int absoluteSize, int conditionSize) {
            return new EdgeSuppressionInfo(type, isHorizontal, absoluteSize, conditionSize,
                    connerWidth, connerHeight);
        }
    }

    private static final class EdgeSuppressionConfig {
        private static final String TAG_ITEM = "edgesuppressionitem";

        private static final int LEVEL_MIN = 0;
        private static final int LEVEL_WAKE = 1;
        private static final int LEVEL_DEFAULT = 2;
        private static final int LEVEL_STRONG = 3;
        private static final int LEVEL_MAX = 4;

        final int[] absoluteLevel = new int[] {0, 0, 0, 1, 1};
        final int[] conditionLevel = new int[] {10, 30, 50, 60, 80};
        final int maxAdjustValue;

        private final List<EdgeSuppressionInfo> mInfos = new ArrayList<>();

        private EdgeSuppressionConfig(int maxAdjustValue) {
            this.maxAdjustValue = maxAdjustValue;
        }

        static EdgeSuppressionConfig load(Resources resources) {
            EdgeSuppressionConfig config = new EdgeSuppressionConfig(resources.getInteger(
                    R.integer.config_edge_suppression_max_adjust_value));
            XmlResourceParser parser = null;
            try {
                parser = resources.getXml(R.xml.edge_suppression_config);
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.getEventType() != XmlPullParser.START_TAG
                            || !TAG_ITEM.equals(parser.getName())) {
                        continue;
                    }
                    config.readItem(parser);
                }
            } catch (IOException | XmlPullParserException | RuntimeException e) {
                Log.e(TAG, "Failed to parse edge_suppression_config; using stock defaults", e);
            } finally {
                if (parser != null) {
                    parser.close();
                }
            }
            config.ensureStockDefaults();
            return config;
        }

        boolean isKnownMode(String type) {
            return KEY_SCREEN_EDGE_MODE_DEFAULT.equals(type)
                    || KEY_SCREEN_EDGE_MODE_WAKE.equals(type)
                    || KEY_SCREEN_EDGE_MODE_STRONG.equals(type)
                    || KEY_SCREEN_EDGE_MODE_CUSTOM.equals(type);
        }

        EdgeSuppressionInfo getInfo(String type, boolean horizontal) {
            EdgeSuppressionInfo info = findInfo(type, horizontal);
            if (info != null) {
                return info;
            }
            return findInfo(KEY_SCREEN_EDGE_MODE_DEFAULT, horizontal);
        }

        int getDefaultConditionSize() {
            return conditionLevel[LEVEL_DEFAULT];
        }

        int getMinConditionSize() {
            return conditionLevel[LEVEL_MIN];
        }

        int getMaxConditionSize() {
            return conditionLevel[LEVEL_MAX];
        }

        private void readItem(XmlResourceParser parser) {
            String type = parser.getAttributeValue(null, "type");
            if (!isKnownMode(type)) {
                return;
            }

            boolean isHorizontal = getIntAttribute(parser, "isHorizontal", 0) == 1;
            int absoluteSize = getIntAttribute(parser, "absoluteSize", 0);
            int conditionSize = getIntAttribute(parser, "conditionSize", 0);
            int connerWidth = getIntAttribute(parser, "connerWidth", 0);
            int connerHeight = getIntAttribute(parser, "connerHeight", 0);

            mInfos.add(new EdgeSuppressionInfo(type, isHorizontal, absoluteSize,
                    conditionSize, connerWidth, connerHeight));
            updateLevels(parser, type, absoluteSize, conditionSize);
        }

        private void updateLevels(XmlResourceParser parser, String type, int absoluteSize,
                int conditionSize) {
            if (KEY_SCREEN_EDGE_MODE_DEFAULT.equals(type)) {
                absoluteLevel[LEVEL_DEFAULT] = absoluteSize;
                conditionLevel[LEVEL_DEFAULT] = conditionSize;
            } else if (KEY_SCREEN_EDGE_MODE_WAKE.equals(type)) {
                absoluteLevel[LEVEL_WAKE] = absoluteSize;
                conditionLevel[LEVEL_WAKE] = conditionSize;
            } else if (KEY_SCREEN_EDGE_MODE_STRONG.equals(type)) {
                absoluteLevel[LEVEL_STRONG] = absoluteSize;
                conditionLevel[LEVEL_STRONG] = conditionSize;
            } else if (KEY_SCREEN_EDGE_MODE_CUSTOM.equals(type)) {
                absoluteLevel[LEVEL_MIN] = getIntAttribute(parser, "minAbsoluteSize",
                        absoluteLevel[LEVEL_MIN]);
                absoluteLevel[LEVEL_MAX] = getIntAttribute(parser, "maxAbsoluteSize",
                        absoluteLevel[LEVEL_MAX]);
                conditionLevel[LEVEL_MIN] = getIntAttribute(parser, "minConditionSize",
                        conditionLevel[LEVEL_MIN]);
                conditionLevel[LEVEL_MAX] = getIntAttribute(parser, "maxConditionSize",
                        conditionLevel[LEVEL_MAX]);
            }
        }

        private void ensureStockDefaults() {
            ensureInfo(KEY_SCREEN_EDGE_MODE_DEFAULT, false, 0, 50, 150, 300);
            ensureInfo(KEY_SCREEN_EDGE_MODE_DEFAULT, true, 0, 50, 170, 170);
            ensureInfo(KEY_SCREEN_EDGE_MODE_WAKE, false, 0, 30, 150, 300);
            ensureInfo(KEY_SCREEN_EDGE_MODE_WAKE, true, 0, 30, 170, 170);
            ensureInfo(KEY_SCREEN_EDGE_MODE_STRONG, false, 1, 60, 150, 300);
            ensureInfo(KEY_SCREEN_EDGE_MODE_STRONG, true, 1, 60, 170, 170);
            ensureInfo(KEY_SCREEN_EDGE_MODE_CUSTOM, false, 0, 0, 150, 300);
            ensureInfo(KEY_SCREEN_EDGE_MODE_CUSTOM, true, 0, 0, 170, 170);
        }

        private void ensureInfo(String type, boolean isHorizontal, int absoluteSize,
                int conditionSize, int connerWidth, int connerHeight) {
            if (findInfo(type, isHorizontal) == null) {
                mInfos.add(new EdgeSuppressionInfo(type, isHorizontal, absoluteSize,
                        conditionSize, connerWidth, connerHeight));
            }
        }

        private EdgeSuppressionInfo findInfo(String type, boolean horizontal) {
            for (EdgeSuppressionInfo info : mInfos) {
                if (info.isHorizontal == horizontal && info.type.equals(type)) {
                    return info;
                }
            }
            return null;
        }

        private static int getIntAttribute(XmlResourceParser parser, String name,
                int defaultValue) {
            String value = parser.getAttributeValue(null, name);
            if (value == null || value.isEmpty()) {
                return defaultValue;
            }
            return Integer.parseInt(value);
        }
    }
}
