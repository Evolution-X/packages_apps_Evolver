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
package org.evolution.settings.fragments.themes.fonts

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.om.OverlayInfo
import android.graphics.Typeface

import com.android.internal.util.evolution.ThemeUtils

class FontManager(context: Context, private val isLockscreen: Boolean) {

    companion object {
        private const val DEFAULT_FONT_PACKAGE = "android"
        private const val FONT_OVERLAY_CATEGORY = "android.theme.customization.font"
        private const val LOCKSCREEN_FONT_OVERLAY_CATEGORY = "android.theme.customization.lockscreen_clock_font"
        private const val THEME_RESOURCE_FONT_FAMILY = "config_bodyFontFamily"
        private const val THEME_RESOURCE_CLOCK_FONT_FAMILY = "config_clockFontFamily"
        private const val THEME_RESOURCE_HEADLINE_FONT_FAMILY = "config_headlineFontFamily"

        private val HEADLINE_FONT_LABEL_MAP = setOf("NothingDot57")
    }

    private val mThemeUtils = ThemeUtils.getInstance(context)

    /**
     * Get all available fonts and return as a list of typefaces.
     */
    val fonts: List<Typeface>
        get() = mThemeUtils.fonts

    /**
     * Get all available font packages.
     */
    val allFontPackages: List<String>
        get() = mThemeUtils.getOverlayPackagesForCategory(
            if (isLockscreen) LOCKSCREEN_FONT_OVERLAY_CATEGORY else FONT_OVERLAY_CATEGORY,
            DEFAULT_FONT_PACKAGE
        )

    /**
     * Get the currently selected font package.
     */
    val currentFontPackage: String
        get() {
            val overlayInfos = mThemeUtils.getOverlayInfos(
                if (isLockscreen) LOCKSCREEN_FONT_OVERLAY_CATEGORY else FONT_OVERLAY_CATEGORY
            )
            return overlayInfos
                .filter { it.isEnabled }
                .map { it.packageName }
                .firstOrNull() ?: DEFAULT_FONT_PACKAGE
        }

    /**
     * Enable a selected font package.
     */
    fun enableFontPackage(position: Int) {
        val packages = allFontPackages
        if (position < 0 || position >= packages.size) {
            throw IllegalArgumentException("Invalid font package position: $position")
        }
        val selectedPackage = packages[position]
        mThemeUtils.setOverlayEnabled(
            if (isLockscreen) LOCKSCREEN_FONT_OVERLAY_CATEGORY else FONT_OVERLAY_CATEGORY,
            selectedPackage,
            DEFAULT_FONT_PACKAGE
        )
    }

    /**
     * Gets the font package label.
     */
    fun getLabel(context: Context, pkg: String): String {
        val pm = context.packageManager
        return try {
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /**
     * Gets the font package typeface.
     */
    fun getTypeface(context: Context?, pkg: String): Typeface? {
        if (context == null) return null
        val pm = context.packageManager
        return try {
            val res = if (pkg == DEFAULT_FONT_PACKAGE) {
                Resources.getSystem()
            } else {
                pm.getResourcesForApplication(pkg)
            }
            val label = getLabel(context, pkg)
            val identifier = when {
                isLockscreen -> THEME_RESOURCE_CLOCK_FONT_FAMILY
                !isLockscreen && HEADLINE_FONT_LABEL_MAP.contains(label) -> THEME_RESOURCE_HEADLINE_FONT_FAMILY
                else -> THEME_RESOURCE_FONT_FAMILY
            }
            Typeface.create(
                res.getString(res.getIdentifier(identifier, "string", pkg)),
                Typeface.NORMAL
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
