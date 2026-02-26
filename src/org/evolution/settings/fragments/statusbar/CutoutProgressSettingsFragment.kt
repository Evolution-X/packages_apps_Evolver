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
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import org.evolution.settings.utils.toArgb
import org.evolution.settings.utils.toHexString

class CutoutProgressSettingsFragment : SettingsPreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    companion object {
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
    }

    private lateinit var ringColorPref: Preference
    private lateinit var errorColorPref: Preference
    private lateinit var flashColorPref: Preference
    private lateinit var bgColorPref: Preference
    private lateinit var finishStylePref: ListPreference
    private lateinit var easingPref: ListPreference
    private lateinit var pctPosPref: ListPreference
    private lateinit var fnamePosPref: ListPreference
    private lateinit var fnameTruncPref: ListPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.cutout_progress_settings)

        ringColorPref = findPreference(KEY_RING_COLOR)!!
        errorColorPref = findPreference(KEY_ERROR_COLOR)!!
        flashColorPref = findPreference(KEY_FLASH_COLOR)!!
        bgColorPref = findPreference(KEY_BG_COLOR)!!
        finishStylePref = findPreference(KEY_FINISH_STYLE)!!
        easingPref = findPreference(KEY_EASING)!!
        pctPosPref = findPreference(KEY_PERCENT_POSITION)!!
        fnamePosPref = findPreference(KEY_FILENAME_POSITION)!!
        fnameTruncPref = findPreference(KEY_FILENAME_TRUNCATE)!!

        refreshColorSummaries()

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

        finishStylePref.onPreferenceChangeListener = this
        easingPref.onPreferenceChangeListener = this
        pctPosPref.onPreferenceChangeListener = this
        fnamePosPref.onPreferenceChangeListener = this
        fnameTruncPref.onPreferenceChangeListener = this

        syncListPreferences()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        val intValue = (newValue as? String)?.toIntOrNull() ?: return false
        writeSecureInt(preference.key, intValue)
        return true
    }

    private fun syncListPreferences() {
        listOf(
            finishStylePref to 0,
            easingPref to 0,
            pctPosPref to 0,
            fnamePosPref to 4,
            fnameTruncPref to 0
        ).forEach { (pref, default) ->
            val current = readSecureInt(pref.key, default)
            pref.value = current.toString()
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
            val argb = color.toArgb()
            writeSecureInt(key, argb)
            refreshColorSummaries()
        }
        dialog.show(parentFragmentManager, CutoutProgressColorPickerDialogFragment.TAG)
    }

    private fun refreshColorSummaries() {
        ringColorPref.summary = "#${argbToHex(readSecureInt(KEY_RING_COLOR, DEFAULT_RING_COLOR))}"
        errorColorPref.summary = "#${argbToHex(readSecureInt(KEY_ERROR_COLOR, DEFAULT_ERROR_COLOR))}"
        flashColorPref.summary = "#${argbToHex(readSecureInt(KEY_FLASH_COLOR, DEFAULT_FLASH_COLOR))}"
        bgColorPref.summary = "#${argbToHex(readSecureInt(KEY_BG_COLOR, DEFAULT_BG_COLOR))}"
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
