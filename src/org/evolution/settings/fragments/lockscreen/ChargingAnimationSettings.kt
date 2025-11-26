/*
 * Copyright (C) 2024-2025 Lunaris AOSP
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

package org.evolution.settings.fragments.lockscreen

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.ListPreference
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.evolution.VibrationUtils
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable

import org.evolution.settings.preferences.SystemSettingSeekBarPreference

@SearchIndexable
class ChargingAnimationSettings : SettingsPreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    private var animationStylePref: ListPreference? = null
    private var rippleOpacityPref: SystemSettingSeekBarPreference? = null
    private var glowIntensityPref: SystemSettingSeekBarPreference? = null
    private var arcCountPref: SystemSettingSeekBarPreference? = null

    companion object {
        const val TAG = "ChargingAnimationSettings"

        private const val STYLE_BUBBLE_STREAM = 0
        private const val STYLE_NEON = 1
        private const val STYLE_BEAM = 2
        private const val STYLE_PLASMA = 3
        private const val STYLE_QUANTUM_SPARKS = 4
        private const val STYLE_NEBULA = 5
        private const val STYLE_DIGITAL_MATRIX = 6
        private const val STYLE_GEOFLOW = 7
        
        private fun getHiddenKeysForStyle(style: Int): List<String> {
            return when (style) {
                STYLE_BUBBLE_STREAM -> listOf("charging_glow_intensity", "charging_arc_count")
                STYLE_NEON -> listOf("charging_ripple_opacity", "charging_arc_count")
                STYLE_BEAM -> listOf("charging_ripple_opacity", "charging_glow_intensity")
                STYLE_PLASMA -> emptyList()
                STYLE_QUANTUM_SPARKS -> listOf("charging_ripple_opacity", "charging_arc_count")
                STYLE_NEBULA -> listOf("charging_arc_count")
                STYLE_DIGITAL_MATRIX -> listOf("charging_ripple_opacity", "charging_arc_count")
                STYLE_GEOFLOW -> listOf("charging_glow_intensity", "charging_arc_count")
                else -> emptyList()
            }
        }

        @JvmField
        val SEARCH_INDEX_DATA_PROVIDER = object : BaseSearchIndexProvider(R.xml.charging_animation_settings) {
            override fun getNonIndexableKeys(context: Context): List<String> {
                val keys = super.getNonIndexableKeys(context).toMutableList()
                
                val style = Settings.System.getInt(
                    context.contentResolver,
                    "charging_animation_style",
                    STYLE_PLASMA
                )

                keys.addAll(getHiddenKeysForStyle(style))
                
                return keys
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.charging_animation_settings)

        animationStylePref = findPreference("charging_animation_style")
        rippleOpacityPref = findPreference("charging_ripple_opacity")
        glowIntensityPref = findPreference("charging_glow_intensity")
        arcCountPref = findPreference("charging_arc_count")

        animationStylePref?.onPreferenceChangeListener = this

        updatePreferenceVisibility()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key != null) {
            VibrationUtils.triggerVibration(context, 3)
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        if (preference.key == "charging_animation_style") {
            updatePreferenceVisibility()
        }
        return true
    }

    private fun updatePreferenceVisibility() {
        val style = Settings.System.getInt(
            contentResolver,
            "charging_animation_style",
            STYLE_PLASMA
        )

        when (style) {
            STYLE_BUBBLE_STREAM -> {
                rippleOpacityPref?.isVisible = true
                glowIntensityPref?.isVisible = false
                arcCountPref?.isVisible = false
            }
            STYLE_NEON -> {
                rippleOpacityPref?.isVisible = false
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = false
            }
            STYLE_BEAM -> {
                rippleOpacityPref?.isVisible = false
                glowIntensityPref?.isVisible = false
                arcCountPref?.isVisible = true
            }
            STYLE_PLASMA -> {
                rippleOpacityPref?.isVisible = true
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = true
            }
            STYLE_QUANTUM_SPARKS -> {
                rippleOpacityPref?.isVisible = false
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = false
            }
            STYLE_NEBULA -> {
                rippleOpacityPref?.isVisible = true
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = false
            }
            STYLE_DIGITAL_MATRIX -> {
                rippleOpacityPref?.isVisible = false
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = false
            }
            STYLE_GEOFLOW -> {
                rippleOpacityPref?.isVisible = true
                glowIntensityPref?.isVisible = false
                arcCountPref?.isVisible = false
            }
        }
    }

    override fun getMetricsCategory(): Int {
        return MetricsProto.MetricsEvent.EVOLVER
    }
}
