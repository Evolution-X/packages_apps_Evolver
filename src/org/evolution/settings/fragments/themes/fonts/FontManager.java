/*
 * Copyright (C) 2023 The risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.themes.fonts;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.om.OverlayInfo;
import android.graphics.Typeface;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.android.internal.util.android.ThemeUtils;

public class FontManager {

    private final static String DEFAULT_FONT_PACKAGE = "android";
    private final static String FONT_OVERLAY_CATEGORY = "android.theme.customization.font";
    private final static String LOCKSCREEN_FONT_OVERLAY_CATEGORY = "android.theme.customization.lockscreen_clock_font";
    private final static String THEME_RESOURCE_FONT_FAMILY = "config_bodyFontFamily";
    private final static String THEME_RESOURCE_CLOCK_FONT_FAMILY = "config_clockFontFamily";
    private static final String THEME_RESOURCE_HEADLINE_FONT_FAMILY = "config_headlineFontFamily";

    private static final Set<String> HEADLINE_FONT_LABEL_MAP = new HashSet<>();

    private ThemeUtils mThemeUtils;
    private boolean isLockscreen;

    static {
        HEADLINE_FONT_LABEL_MAP.add("NothingDot57");
    }

    public FontManager(Context context, boolean lockscreen) {
        mThemeUtils = ThemeUtils.getInstance(context);
        isLockscreen = lockscreen;
    }

    /**
     * Get all available fonts and return as a list of typefaces.
     */
    public List<Typeface> getFonts() {
        return mThemeUtils.getFonts();
    }

    /**
     * Get all available font packages.
     */
    public List<String> getAllFontPackages() {
        return mThemeUtils.getOverlayPackagesForCategory(isLockscreen ? LOCKSCREEN_FONT_OVERLAY_CATEGORY : FONT_OVERLAY_CATEGORY, DEFAULT_FONT_PACKAGE);
    }

    /**
     * Get the currently selected font package.
     */
    public String getCurrentFontPackage() {
        List<OverlayInfo> overlayInfos = mThemeUtils.getOverlayInfos(isLockscreen ? LOCKSCREEN_FONT_OVERLAY_CATEGORY : FONT_OVERLAY_CATEGORY);
        return overlayInfos.stream()
                .filter(OverlayInfo::isEnabled)
                .map(OverlayInfo::getPackageName)
                .findFirst()
                .orElse(DEFAULT_FONT_PACKAGE);
    }

    /**
     * Enable a selected font package.
     */
    public void enableFontPackage(int position) {
        if (position < 0 || position >= getAllFontPackages().size()) {
            throw new IllegalArgumentException("Invalid font package position: " + position);
        }
        String selectedPackage = getAllFontPackages().get(position);
        mThemeUtils.setOverlayEnabled(isLockscreen ? LOCKSCREEN_FONT_OVERLAY_CATEGORY : FONT_OVERLAY_CATEGORY, selectedPackage, DEFAULT_FONT_PACKAGE);
    }

    /**
     * Gets the font package label.
     */
    public String getLabel(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {}
        return pkg;
    }

    /**
     * Gets the font package typeface.
     */
    public Typeface getTypeface(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            Resources res = pkg.equals(DEFAULT_FONT_PACKAGE) ? Resources.getSystem()
                    : pm.getResourcesForApplication(pkg);
            String label = getLabel(context, pkg);
            String identifier = isLockscreen ? THEME_RESOURCE_CLOCK_FONT_FAMILY : THEME_RESOURCE_FONT_FAMILY;
            if (!isLockscreen && HEADLINE_FONT_LABEL_MAP.contains(label)) {
                identifier = THEME_RESOURCE_HEADLINE_FONT_FAMILY;
            }
            return Typeface.create(res.getString(
                    res.getIdentifier(identifier, "string", pkg)), Typeface.NORMAL);
        } catch (PackageManager.NameNotFoundException e) {}
        return null;
    }
}
