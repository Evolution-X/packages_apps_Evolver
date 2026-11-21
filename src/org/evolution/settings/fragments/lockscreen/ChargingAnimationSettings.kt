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
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.ListPreference
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.evolution.VibrationUtils
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.Utils
import com.android.settingslib.widget.LayoutPreference
import com.android.settingslib.search.SearchIndexable

import org.evolution.settings.preferences.SystemSettingSeekBarPreference

@SearchIndexable
class ChargingAnimationSettings : SettingsPreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    private var animationStylePref: ListPreference? = null
    private var rippleOpacityPref: SystemSettingSeekBarPreference? = null
    private var glowIntensityPref: SystemSettingSeekBarPreference? = null
    private var arcCountPref: SystemSettingSeekBarPreference? = null

    private var preview: ChargingPreviewView? = null
    private var previewStyle = -1
    private var previewColorMode = -1
    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewObserver = object : ContentObserver(previewHandler) {
        override fun onChange(selfChange: Boolean) {
            syncPreview()
        }
    }

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

        private const val KEY_PREVIEW = "charging_preview"
        private const val KEY_STYLE = "charging_animation_style"
        private const val KEY_COLOR_MODE = "charging_color_mode"
        private const val KEY_RIPPLE_OPACITY = "charging_ripple_opacity"
        private const val KEY_GLOW_INTENSITY = "charging_glow_intensity"
        private const val KEY_ARC_COUNT = "charging_arc_count"
        private const val PREVIEW_RESTART_MS = 350L
        private val PREVIEW_KEYS = arrayOf(
            KEY_STYLE, KEY_COLOR_MODE, KEY_RIPPLE_OPACITY, KEY_GLOW_INTENSITY, KEY_ARC_COUNT
        )
        
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
                keys.add(KEY_PREVIEW)
                
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

        preview = findPreference<LayoutPreference>(KEY_PREVIEW)
            ?.findViewById(R.id.charging_preview)
        animationStylePref = findPreference("charging_animation_style")
        rippleOpacityPref = findPreference("charging_ripple_opacity")
        glowIntensityPref = findPreference("charging_glow_intensity")
        arcCountPref = findPreference("charging_arc_count")

        animationStylePref?.onPreferenceChangeListener = this

        updatePreferenceVisibility()
    }

    override fun onStart() {
        super.onStart()
        for (key in PREVIEW_KEYS) {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, previewObserver
            )
        }
        previewStyle = -1
        previewColorMode = -1
        syncPreview()
        preview?.show()
    }

    override fun onStop() {
        super.onStop()
        contentResolver.unregisterContentObserver(previewObserver)
        previewHandler.removeCallbacksAndMessages(null)
        preview?.hide()
    }

    // The preview plays whether or not the animation is switched on, so a style can be
    // judged before it is enabled. A new style or color mode restarts it, as the view only
    // starts its animators when it is shown.
    private fun syncPreview() {
        val view = preview ?: return
        val style = readInt(KEY_STYLE, 0).coerceIn(0, 7)
        val colorMode = readInt(KEY_COLOR_MODE, 0).coerceIn(0, 2)
        val level = requireContext()
            .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) ?: 50

        view.accentColor = Utils.getColorAccentDefaultColor(requireContext())
        view.batteryLevel = level
        view.animationStyle = style
        view.colorMode = colorMode
        view.rippleOpacity = readInt(KEY_RIPPLE_OPACITY, 60) / 100f
        view.glowIntensity = readInt(KEY_GLOW_INTENSITY, 80) / 100f
        view.arcCount = readInt(KEY_ARC_COUNT, 3)

        val restart = previewStyle != -1 &&
            (style != previewStyle || colorMode != previewColorMode)
        previewStyle = style
        previewColorMode = colorMode
        if (restart) {
            previewHandler.removeCallbacksAndMessages(null)
            view.hide()
            previewHandler.postDelayed({ view.show() }, PREVIEW_RESTART_MS)
        }
    }

    private fun readInt(key: String, default: Int): Int =
        Settings.System.getIntForUser(contentResolver, key, default, UserHandle.USER_CURRENT)

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
