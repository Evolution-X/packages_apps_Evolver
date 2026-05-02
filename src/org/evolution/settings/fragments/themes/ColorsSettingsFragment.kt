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
    }

    private lateinit var useWallpaperColors: SwitchPreferenceCompat
    private lateinit var seedColorPref: Preference
    private lateinit var wallpaperSeedColorPref: Preference
    private lateinit var themeStyle: ListPreference
    private lateinit var fidelity: SwitchPreferenceCompat
    private var contrast: CustomSeekBarPreference? = null
    private var chroma: CustomSeekBarPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.monet_settings)

        useWallpaperColors = findPreference(KEY_USE_WALLPAPER)!!
        seedColorPref = findPreference(KEY_SEED_COLOR)!!
        wallpaperSeedColorPref = findPreference(KEY_WALLPAPER_SEED_COLOR)!!
        themeStyle = findPreference(KEY_THEME_STYLE)!!
        fidelity = findPreference(KEY_FIDELITY)!!
        contrast = findPreference(KEY_CONTRAST)
        chroma = findPreference(KEY_CHROMA)

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

    override fun getMetricsCategory(): Int {
        return MetricsEvent.EVOLVER
    }
}
