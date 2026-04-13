/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package org.evolution.settings.fragments.statusbar

import android.os.Bundle
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import org.evolution.settings.utils.toArgb
import org.evolution.settings.utils.toHexString

@SearchIndexable
class CutoutProgressSettingsFragment : SettingsPreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    companion object {
        private const val KEY_MUSIC_RING_ENABLED = "cutout_progress_music_enabled"
        private const val KEY_MUSIC_COLOR_MODE = "cutout_progress_music_color_mode"
        private const val KEY_MUSIC_CUSTOM_COLOR = "cutout_progress_music_custom_color"

        private const val KEY_RING_COLOR_MODE = "cutout_progress_ring_color_mode"
        private const val COLOR_MODE_ACCENT = 0
        private const val COLOR_MODE_RAINBOW = 1
        private const val COLOR_MODE_CUSTOM = 2

        private const val MUSIC_COLOR_MODE_ALBUM_ICON = 0
        private const val MUSIC_COLOR_MODE_ACCENT = 1
        private const val MUSIC_COLOR_MODE_ALBUM_ART = 2
        private const val MUSIC_COLOR_MODE_CUSTOM = 3

        private const val KEY_RING_COLOR = "cutout_progress_ring_color"
        private const val KEY_ERROR_COLOR = "cutout_progress_error_color"
        private const val KEY_FLASH_COLOR = "cutout_progress_finish_flash_color"
        private const val KEY_BG_COLOR = "cutout_progress_bg_ring_color"
        private const val KEY_FINISH_STYLE = "cutout_progress_finish_style"
        private const val KEY_EASING = "cutout_progress_easing"
        private const val KEY_PERCENT_POSITION = "cutout_progress_percent_position"
        private const val KEY_FILENAME_POSITION = "cutout_progress_filename_position"
        private const val KEY_FILENAME_TRUNCATE = "cutout_progress_filename_truncate"

        private const val DEFAULT_RING_COLOR = 0xFF2196F3.toInt()
        private const val DEFAULT_ERROR_COLOR = 0xFFF44336.toInt()
        private const val DEFAULT_FLASH_COLOR = 0xFFFFFFFF.toInt()
        private const val DEFAULT_BG_COLOR = 0xFF808080.toInt()
        private const val DEFAULT_MUSIC_COLOR = 0xFF9C27B0.toInt()

    @JvmField
    val SEARCH_INDEX_DATA_PROVIDER =
        BaseSearchIndexProvider(R.xml.cutout_progress_settings)
    }

    private lateinit var ringColorModePref: ListPreference
    private lateinit var musicColorModePref: ListPreference

    private lateinit var ringColorPref: Preference
    private lateinit var errorColorPref: Preference
    private lateinit var flashColorPref: Preference
    private lateinit var bgColorPref: Preference
    private lateinit var musicColorPref: Preference

    private lateinit var finishStylePref: ListPreference
    private lateinit var easingPref: ListPreference
    private lateinit var pctPosPref: ListPreference
    private lateinit var fnamePosPref: ListPreference
    private lateinit var fnameTruncPref: ListPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.cutout_progress_settings)

        ringColorModePref = findPreference(KEY_RING_COLOR_MODE)!!
        musicColorModePref = findPreference(KEY_MUSIC_COLOR_MODE)!!

        ringColorPref = findPreference(KEY_RING_COLOR)!!
        errorColorPref = findPreference(KEY_ERROR_COLOR)!!
        flashColorPref = findPreference(KEY_FLASH_COLOR)!!
        bgColorPref = findPreference(KEY_BG_COLOR)!!
        musicColorPref = findPreference(KEY_MUSIC_CUSTOM_COLOR)!!

        finishStylePref = findPreference(KEY_FINISH_STYLE)!!
        easingPref = findPreference(KEY_EASING)!!
        pctPosPref = findPreference(KEY_PERCENT_POSITION)!!
        fnamePosPref = findPreference(KEY_FILENAME_POSITION)!!
        fnameTruncPref = findPreference(KEY_FILENAME_TRUNCATE)!!

        refreshColorSummaries()
        syncListPreferences()

        val storedMode = readSecureInt(KEY_RING_COLOR_MODE, COLOR_MODE_ACCENT)
        ringColorModePref.value = storedMode.toString()
        updateColorPickerVisibility(storedMode, KEY_RING_COLOR_MODE)

        val storedMusicMode = readSecureInt(KEY_MUSIC_COLOR_MODE, MUSIC_COLOR_MODE_ALBUM_ICON)
        musicColorModePref.value = storedMusicMode.toString()
        updateColorPickerVisibility(storedMusicMode, KEY_MUSIC_COLOR_MODE)

        ringColorModePref.onPreferenceChangeListener = this
        musicColorModePref.onPreferenceChangeListener = this

        ringColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_ring_color_title),
                key = KEY_RING_COLOR,
                default = DEFAULT_RING_COLOR
            )
            true
        }
        errorColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_error_color_title),
                key = KEY_ERROR_COLOR,
                default = DEFAULT_ERROR_COLOR
            )
            true
        }
        flashColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_finish_flash_color_title),
                key = KEY_FLASH_COLOR,
                default = DEFAULT_FLASH_COLOR
            )
            true
        }
        bgColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_bg_ring_color_title),
                key = KEY_BG_COLOR,
                default = DEFAULT_BG_COLOR
            )
            true
        }
        musicColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_music_custom_color_title),
                key = KEY_MUSIC_CUSTOM_COLOR,
                default = DEFAULT_MUSIC_COLOR
            )
            true
        }

        finishStylePref.onPreferenceChangeListener = this
        easingPref.onPreferenceChangeListener = this
        pctPosPref.onPreferenceChangeListener = this
        fnamePosPref.onPreferenceChangeListener = this
        fnameTruncPref.onPreferenceChangeListener = this
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        val intValue = (newValue as? String)?.toIntOrNull() ?: return false

        if (preference.key == KEY_RING_COLOR_MODE || preference.key == KEY_MUSIC_COLOR_MODE) {
            writeSecureInt(preference.key, intValue)
            updateColorPickerVisibility(intValue, preference.key)
            return true
        }

        writeSecureInt(preference.key, intValue)
        return true
    }

    private fun updateColorPickerVisibility(mode: Int, key: String) {
        when (key) {
            KEY_RING_COLOR_MODE -> ringColorPref.isVisible = (mode == COLOR_MODE_CUSTOM)
            KEY_MUSIC_COLOR_MODE -> musicColorPref.isVisible = (mode == MUSIC_COLOR_MODE_CUSTOM)
        }
    }

    private fun syncListPreferences() {
        listOf(
            finishStylePref to 0,
            easingPref to 0,
            pctPosPref to 0,
            fnamePosPref to 4,
            fnameTruncPref to 0
        ).forEach { (pref, default) ->
            pref.value = readSecureInt(pref.key, default).toString()
        }
    }

    private fun showColorPicker(title: String, key: String, default: Int) {
        val currentArgb = readSecureInt(key, default)
        val currentHex = argbToHex(currentArgb)

        val dialog = CutoutProgressColorPickerDialogFragment.newInstance(
            title = title,
            colorHex = currentHex
        )
        dialog.setOnColorSelectedListener { color: Color ->
            writeSecureInt(key, color.toArgb())
            refreshColorSummaries()
        }
        dialog.show(parentFragmentManager, CutoutProgressColorPickerDialogFragment.TAG)
    }

    private fun refreshColorSummaries() {
        ringColorPref.summary = "#${argbToHex(readSecureInt(KEY_RING_COLOR, DEFAULT_RING_COLOR))}"
        errorColorPref.summary = "#${argbToHex(readSecureInt(KEY_ERROR_COLOR, DEFAULT_ERROR_COLOR))}"
        flashColorPref.summary = "#${argbToHex(readSecureInt(KEY_FLASH_COLOR, DEFAULT_FLASH_COLOR))}"
        bgColorPref.summary = "#${argbToHex(readSecureInt(KEY_BG_COLOR, DEFAULT_BG_COLOR))}"
        musicColorPref.summary = "#${argbToHex(readSecureInt(KEY_MUSIC_CUSTOM_COLOR, DEFAULT_MUSIC_COLOR))}"
    }

    private fun readSecureInt(key: String, default: Int): Int =
        Settings.Secure.getInt(requireContext().contentResolver, key, default)

    private fun writeSecureInt(key: String, value: Int) {
        Settings.Secure.putInt(requireContext().contentResolver, key, value)
    }

    private fun argbToHex(argb: Int): String =
        String.format("%06X", 0xFFFFFF and argb)

    override fun getMetricsCategory(): Int = MetricsEvent.EVOLVER
}
