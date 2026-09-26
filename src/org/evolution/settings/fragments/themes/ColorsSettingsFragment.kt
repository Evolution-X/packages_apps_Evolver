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

package org.evolution.settings.fragments.themes

import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import org.json.JSONObject
import org.evolution.settings.preferences.CustomSeekBarPreference
import org.evolution.settings.utils.toArgb

class ColorsSettingsFragment : SettingsPreferenceFragment(), 
    Preference.OnPreferenceChangeListener {

    companion object {
        private const val KEY_USE_WALLPAPER = "use_wallpaper_colors"
        private const val KEY_SEED_COLOR = "custom_seed_color"
        private const val KEY_WALLPAPER_SEED_COLOR = "wallpaper_seed_color"
        private const val KEY_THEME_STYLE = "theme_style"
        private const val KEY_FIDELITY = "fidelity_enabled"
        private const val KEY_CONTRAST = "contrast_level"
        private const val KEY_CHROMA = "chroma_boost"
        private const val KEY_CHROMA_ACCENT1 = "chroma_boost_accent1"
        private const val KEY_CHROMA_ACCENT2 = "chroma_boost_accent2"
        private const val KEY_CHROMA_ACCENT3 = "chroma_boost_accent3"
        private const val KEY_CHROMA_NEUTRAL1 = "chroma_boost_neutral1"
        private const val KEY_CHROMA_NEUTRAL2 = "chroma_boost_neutral2"
        private const val KEY_HUE_SHIFT = "hue_shift"
        private const val KEY_TONE_SHIFT_LIGHT = "tone_shift_light"
        private const val KEY_TONE_SHIFT_DARK = "tone_shift_dark"
    }

    private lateinit var useWallpaperColors: SwitchPreferenceCompat
    private lateinit var seedColorPref: Preference
    private lateinit var wallpaperSeedColorPref: Preference
    private lateinit var themeStyle: ListPreference
    private lateinit var fidelity: SwitchPreferenceCompat
    private var contrast: CustomSeekBarPreference? = null
    private var chroma: CustomSeekBarPreference? = null
    private var chromaAccent1: CustomSeekBarPreference? = null
    private var chromaAccent2: CustomSeekBarPreference? = null
    private var chromaAccent3: CustomSeekBarPreference? = null
    private var chromaNeutral1: CustomSeekBarPreference? = null
    private var chromaNeutral2: CustomSeekBarPreference? = null
    private var hueShift: CustomSeekBarPreference? = null
    private var toneShiftLight: CustomSeekBarPreference? = null
    private var toneShiftDark: CustomSeekBarPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.monet_settings)

        useWallpaperColors = findPreference(KEY_USE_WALLPAPER)!!
        seedColorPref = findPreference(KEY_SEED_COLOR)!!
        wallpaperSeedColorPref = findPreference(KEY_WALLPAPER_SEED_COLOR)!!
        themeStyle = findPreference(KEY_THEME_STYLE)!!
        fidelity = findPreference(KEY_FIDELITY)!!
        contrast = findPreference(KEY_CONTRAST)
        chroma = findPreference(KEY_CHROMA)
        chromaAccent1 = findPreference(KEY_CHROMA_ACCENT1)
        chromaAccent2 = findPreference(KEY_CHROMA_ACCENT2)
        chromaAccent3 = findPreference(KEY_CHROMA_ACCENT3)
        chromaNeutral1 = findPreference(KEY_CHROMA_NEUTRAL1)
        chromaNeutral2 = findPreference(KEY_CHROMA_NEUTRAL2)
        hueShift = findPreference(KEY_HUE_SHIFT)
        toneShiftLight = findPreference(KEY_TONE_SHIFT_LIGHT)
        toneShiftDark = findPreference(KEY_TONE_SHIFT_DARK)

        useWallpaperColors.onPreferenceChangeListener = this
        
        seedColorPref.setOnPreferenceClickListener {
            showColorPickerDialog()
            true
        }
        
        wallpaperSeedColorPref.setOnPreferenceClickListener {
            showWallpaperColorPickerDialog()
            true
        }
        
        themeStyle.onPreferenceChangeListener = this
        fidelity.onPreferenceChangeListener = this
        contrast?.onPreferenceChangeListener = this
        chroma?.onPreferenceChangeListener = this
        chromaAccent1?.onPreferenceChangeListener = this
        chromaAccent2?.onPreferenceChangeListener = this
        chromaAccent3?.onPreferenceChangeListener = this
        chromaNeutral1?.onPreferenceChangeListener = this
        chromaNeutral2?.onPreferenceChangeListener = this
        hueShift?.onPreferenceChangeListener = this
        toneShiftLight?.onPreferenceChangeListener = this
        toneShiftDark?.onPreferenceChangeListener = this

        loadCurrentSettings()
        updateSeedColorSummary()
        updateSeedColorDependency(useWallpaperColors.isChecked)
    }

    private fun loadCurrentSettings() {
        try {
            val json = getCurrentSettings()

            val colorSource = json.optString("android.theme.customization.color_source", "home")
            val useWallpaper = colorSource == "home" || colorSource == "lock"
            useWallpaperColors.isChecked = useWallpaper

            val style = json.optString("android.theme.customization.theme_style", "TONAL_SPOT")
            themeStyle.value = style

            val fidelityValue = json.optBoolean("_fidelity_enabled", false)
            fidelity.isChecked = fidelityValue

            contrast?.let {
                val contrastValue = (json.optDouble("_contrast_level", 0.0) * 100).toInt()
                it.value = contrastValue
            }

            chroma?.let {
                val chromaValue = json.optDouble("_chroma_boost", 0.0).toInt()
                it.value = chromaValue
            }
            chromaAccent1?.value = json.optDouble("_chroma_accent1", 0.0).toInt()
            chromaAccent2?.value = json.optDouble("_chroma_accent2", 0.0).toInt()
            chromaAccent3?.value = json.optDouble("_chroma_accent3", 0.0).toInt()
            chromaNeutral1?.value = json.optDouble("_chroma_neutral1", 0.0).toInt()
            chromaNeutral2?.value = json.optDouble("_chroma_neutral2", 0.0).toInt()
            hueShift?.value = json.optDouble("_hue_shift", 0.0).toInt()
            toneShiftLight?.value = json.optDouble("_tone_shift_light", 0.0).toInt()
            toneShiftDark?.value = json.optDouble("_tone_shift_dark", 0.0).toInt()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        when (preference.key) {
            KEY_USE_WALLPAPER -> {
                val useWallpaper = newValue as Boolean
                applyWallpaperColorsSetting(useWallpaper)
                updateSeedColorDependency(useWallpaper)
                return true
            }
            KEY_THEME_STYLE -> {
                applyThemeStyle(newValue as String)
                return true
            }
            KEY_FIDELITY -> {
                applyFidelitySetting(newValue as Boolean)
                return true
            }
            KEY_CONTRAST -> {
                applyContrastLevel(newValue as Int)
                return true
            }
            KEY_CHROMA -> {
                applyChromaBoost(newValue as Int)
                return true
            }
            KEY_CHROMA_ACCENT1 -> {
                applyDoubleKey("_chroma_accent1", (newValue as Int).toDouble())
                return true
            }
            KEY_CHROMA_ACCENT2 -> {
                applyDoubleKey("_chroma_accent2", (newValue as Int).toDouble())
                return true
            }
            KEY_CHROMA_ACCENT3 -> {
                applyDoubleKey("_chroma_accent3", (newValue as Int).toDouble())
                return true
            }
            KEY_CHROMA_NEUTRAL1 -> {
                applyDoubleKey("_chroma_neutral1", (newValue as Int).toDouble())
                return true
            }
            KEY_CHROMA_NEUTRAL2 -> {
                applyDoubleKey("_chroma_neutral2", (newValue as Int).toDouble())
                return true
            }
            KEY_HUE_SHIFT -> {
                applyDoubleKey("_hue_shift", (newValue as Int).toDouble())
                return true
            }
            KEY_TONE_SHIFT_LIGHT -> {
                applyDoubleKey("_tone_shift_light", (newValue as Int).toDouble())
                return true
            }
            KEY_TONE_SHIFT_DARK -> {
                applyDoubleKey("_tone_shift_dark", (newValue as Int).toDouble())
                return true
            }
        }
        return false
    }

    private fun showColorPickerDialog() {
        try {
            val json = getCurrentSettings()
            val seedColorHex = json.optString("_base_seed_color", "6750A4")

            val dialog = ColorPickerDialogFragment.newInstance(seedColorHex)
            dialog.setOnColorSelectedListener { color ->
                applySeedColor(color)
                updateSeedColorSummary()
            }
            dialog.show(parentFragmentManager, ColorPickerDialogFragment.TAG)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showWallpaperColorPickerDialog() {
        try {
            val dialog = WallpaperColorPickerDialogFragment.newInstance()
            dialog.setOnColorSelectedListener { color ->
                applySeedColor(color)
                updateSeedColorSummary()
            }
            dialog.show(parentFragmentManager, WallpaperColorPickerDialogFragment.TAG)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateSeedColorSummary() {
        try {
            val json = getCurrentSettings()
            val seedColor = json.optString("_base_seed_color", "6750A4")
            seedColorPref.summary = "#${seedColor.uppercase().replace("#", "")}"
        } catch (e: Exception) {
            seedColorPref.summary = "#6750A4"
        }
    }

    private fun updateSeedColorDependency(useWallpaper: Boolean) {
        seedColorPref.isEnabled = !useWallpaper
        wallpaperSeedColorPref.isEnabled = !useWallpaper
    }

    private fun getCurrentSettings(): JSONObject {
        return try {
            val currentJson = Settings.Secure.getString(
                activity?.contentResolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES
            )
            JSONObject(currentJson ?: "{}")
        } catch (e: Exception) {
            JSONObject("{}")
        }
    }

    private fun applySettings(json: JSONObject) {
        try {
            json.put("_applied_timestamp", System.currentTimeMillis())
            Settings.Secure.putString(
                activity?.contentResolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                json.toString()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyWallpaperColorsSetting(useWallpaper: Boolean) {
        try {
            val json = getCurrentSettings()
            if (useWallpaper) {
                json.remove("android.theme.customization.system_palette")
                json.remove("android.theme.customization.accent_color")
                json.remove("_base_seed_color")
                json.put("android.theme.customization.color_source", "home")
            } else {
                json.put("android.theme.customization.color_source", "preset")
                if (!json.has("_base_seed_color")) {
                    json.put("_base_seed_color", "6750A4")
                    json.put("android.theme.customization.system_palette", "6750A4")
                    json.put("android.theme.customization.accent_color", "6750A4")
                }
            }
            applySettings(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applySeedColor(color: ComposeColor) {
        try {
            val json = getCurrentSettings()

            val argb = color.toArgb()
            val hexColor = String.format("%06X", 0xFFFFFF and argb)

            json.put("_base_seed_color", hexColor)
            json.put("android.theme.customization.system_palette", hexColor)
            json.put("android.theme.customization.accent_color", hexColor)
            json.put("android.theme.customization.color_source", "preset")

            applySettings(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyThemeStyle(style: String) {
        try {
            val json = getCurrentSettings()
            json.put("android.theme.customization.theme_style", style)
            applySettings(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyFidelitySetting(enabled: Boolean) {
        try {
            val json = getCurrentSettings()
            json.put("_fidelity_enabled", enabled)
            applySettings(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyContrastLevel(value: Int) {
        try {
            val json = getCurrentSettings()
            json.put("_contrast_level", value / 100.0)
            applySettings(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyChromaBoost(value: Int) {
        try {
            val json = getCurrentSettings()
            json.put("_chroma_boost", value.toDouble())
            applySettings(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyDoubleKey(jsonKey: String, value: Double) {
        try {
            val json = getCurrentSettings()
            json.put(jsonKey, value)
            applySettings(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getMetricsCategory(): Int {
        return MetricsEvent.EVOLVER
    }
}
