/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.os.Process;
import android.util.Log;

final class LauncherOverlayController {

    private static final String TAG = "LauncherOverlayController";
    private static final String LAUNCHER_PACKAGE = "com.android.launcher3";

    static final String OVERLAY_HIDE_PAGE_INDICATOR =
            "com.android.evolution.launcher.hidepageindicator";
    static final String OVERLAY_PAGE_INDICATOR_DARK =
            "com.android.evolution.launcher.pageindicatordark";
    static final String OVERLAY_PAGE_INDICATOR_LIGHT =
            "com.android.evolution.launcher.pageindicatorlight";
    static final String OVERLAY_FOLDER_OPACITY_75 =
            "com.android.evolution.launcher.folderopacity75";
    static final String OVERLAY_FOLDER_OPACITY_50 =
            "com.android.evolution.launcher.folderopacity50";
    static final String OVERLAY_FOLDER_OPACITY_0 =
            "com.android.evolution.launcher.folderopacity0";
    static final String OVERLAY_DOCK_COMPACT =
            "com.android.evolution.launcher.dockcompact";
    static final String OVERLAY_DOCK_WIDE =
            "com.android.evolution.launcher.dockwide";
    static final String OVERLAY_HIDE_SCROLLBAR =
            "com.android.evolution.launcher.hidescrollbar";

    private static final String[] ALL_OVERLAYS = {
            OVERLAY_HIDE_PAGE_INDICATOR,
            OVERLAY_PAGE_INDICATOR_DARK,
            OVERLAY_PAGE_INDICATOR_LIGHT,
            OVERLAY_FOLDER_OPACITY_75,
            OVERLAY_FOLDER_OPACITY_50,
            OVERLAY_FOLDER_OPACITY_0,
            OVERLAY_DOCK_COMPACT,
            OVERLAY_DOCK_WIDE,
            OVERLAY_HIDE_SCROLLBAR,
    };

    private final OverlayManager mOverlayManager;
    private final ActivityManager mActivityManager;

    LauncherOverlayController(Context context) {
        mOverlayManager = context.getSystemService(OverlayManager.class);
        mActivityManager = context.getSystemService(ActivityManager.class);
    }

    boolean isAvailable() {
        return mOverlayManager != null;
    }

    boolean areAllOverlaysAvailable() {
        if (!isAvailable()) {
            return false;
        }
        for (String packageName : ALL_OVERLAYS) {
            if (!isInstalled(packageName)) {
                return false;
            }
        }
        return true;
    }

    boolean isInstalled(String packageName) {
        return getOverlayInfo(packageName) != null;
    }

    boolean isEnabled(String packageName) {
        OverlayInfo info = getOverlayInfo(packageName);
        return info != null && info.isEnabled();
    }

    boolean setEnabled(String packageName, boolean enabled) {
        if (!isAvailable()) {
            return false;
        }
        try {
            mOverlayManager.setEnabled(packageName, enabled, Process.myUserHandle());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to change overlay " + packageName, e);
            return false;
        }
    }

    /**
     * Enables exactly one overlay in a group, or none when selectedPackage is null.
     * Restores the previous state if any change fails.
     */
    boolean select(String[] packages, String selectedPackage) {
        if (!isAvailable()) {
            return false;
        }

        final boolean[] original = new boolean[packages.length];
        for (int i = 0; i < packages.length; i++) {
            original[i] = isEnabled(packages[i]);
        }

        for (int i = 0; i < packages.length; i++) {
            final boolean enable = packages[i].equals(selectedPackage);
            if (original[i] == enable) {
                continue;
            }
            if (!setEnabled(packages[i], enable)) {
                for (int j = 0; j < i; j++) {
                    if (isEnabled(packages[j]) != original[j]) {
                        setEnabled(packages[j], original[j]);
                    }
                }
                return false;
            }
        }
        return true;
    }

    String getSelected(String[] packages, String[] values, String fallback) {
        for (int i = 0; i < packages.length; i++) {
            if (isEnabled(packages[i])) {
                return values[i];
            }
        }
        return fallback;
    }

    boolean restartLauncher() {
        if (mActivityManager == null) {
            return false;
        }
        try {
            mActivityManager.forceStopPackage(LAUNCHER_PACKAGE);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to restart " + LAUNCHER_PACKAGE, e);
            return false;
        }
    }

    private OverlayInfo getOverlayInfo(String packageName) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return mOverlayManager.getOverlayInfo(packageName, Process.myUserHandle());
        } catch (Exception e) {
            Log.w(TAG, "Unable to read overlay " + packageName, e);
            return null;
        }
    }
}
