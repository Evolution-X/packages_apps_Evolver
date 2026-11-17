/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.settings.fragments.themes

import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference

import com.android.internal.logging.nano.MetricsProto

import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment

class LiquidGlassSettings : SettingsPreferenceFragment() {

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        addPreferencesFromResource(R.xml.liquid_glass_settings)

        // The ColorOS style is frosted and does not refract.
        val refraction = findPreference<Preference>(KEY_REFRACTION)
        val style = findPreference<Preference>(KEY_STYLE)
        refraction?.isVisible = readStyle() == STYLE_IOS
        style?.setOnPreferenceChangeListener { _, newValue ->
            refraction?.isVisible = newValue.toString().toIntOrNull() == STYLE_IOS
            true
        }
    }

    private fun readStyle(): Int =
        Settings.Secure.getInt(requireContext().contentResolver, KEY_STYLE, STYLE_IOS)

    private companion object {
        const val KEY_STYLE = "liquid_glass_style"
        const val KEY_REFRACTION = "liquid_glass_refraction"
        const val STYLE_IOS = 0
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.VIEW_UNKNOWN
}
