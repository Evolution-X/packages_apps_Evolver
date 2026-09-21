/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings

import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import androidx.preference.ListPreference
import androidx.preference.Preference

class EdgeLightSettings : SettingsPreferenceFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.edge_light_settings)

        val colorModePref = findPreference<ListPreference>(KEY_COLOR_MODE)!!
        val customColorPref = findPreference<Preference>(KEY_CUSTOM_COLOR)!!

        // Only show the custom color while the custom color mode is selected.
        customColorPref.isVisible = colorModePref.value == COLOR_MODE_CUSTOM
        colorModePref.setOnPreferenceChangeListener { _, newValue ->
            customColorPref.isVisible = newValue == COLOR_MODE_CUSTOM
            true
        }
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.EVOLVER

    companion object {
        private const val KEY_COLOR_MODE = "edge_light_color_mode"
        private const val KEY_CUSTOM_COLOR = "edge_light_custom_color"
        private const val COLOR_MODE_CUSTOM = "custom"

        @JvmStatic
        fun reset(context: Context) {
            val resolver = context.contentResolver
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_ENABLED, 0, UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_COLOR_MODE, "accent", UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_CUSTOM_COLOR, Color.WHITE, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_PULSE_COUNT, 1, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_STROKE_WIDTH, 8, UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_STYLE, "default", UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_ANIMATION_EFFECT, "none", UserHandle.USER_CURRENT)
        }
    }
}
