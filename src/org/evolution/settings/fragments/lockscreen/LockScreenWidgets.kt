/*
 * Copyright (C) 2025 AxionOS Project
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

import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settings.R
import com.android.settings.preferences.BasePreferenceFragment

class LockScreenWidgets : BasePreferenceFragment(R.xml.custom_settings_lockscreen_widgets),
    Preference.OnPreferenceClickListener {

    companion object {
        private const val LOCKSCREEN_WIDGETS_EXTRAS_KEY = "lockscreen_widgets_extras"
        private const val LOCKSCREEN_WIDGETS_ENABLED_KEY = "lockscreen_widgets_enabled"
    }

    private lateinit var lockScreenWidgetsEnabledPref: SwitchPreferenceCompat
    private lateinit var pickerPref: Preference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lockScreenWidgetsEnabledPref = findPreference(LOCKSCREEN_WIDGETS_ENABLED_KEY)!!
        pickerPref = findPreference("lockscreen_widgets_picker")!!
        pickerPref.onPreferenceClickListener = this

        val isWidgetsEnabled = Settings.System.getIntForUser(
            requireContext().contentResolver,
            LOCKSCREEN_WIDGETS_ENABLED_KEY, 0, UserHandle.USER_CURRENT
        ) != 0

        lockScreenWidgetsEnabledPref.isChecked = isWidgetsEnabled
        pickerPref.isVisible = isWidgetsEnabled

        lockScreenWidgetsEnabledPref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            pickerPref.isVisible = enabled
            Settings.System.putIntForUser(
                requireContext().contentResolver,
                LOCKSCREEN_WIDGETS_ENABLED_KEY,
                if (enabled) 1 else 0,
                UserHandle.USER_CURRENT
            )
            true
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        return if (preference.key == "lockscreen_widgets_picker") {
            val current = Settings.System.getString(
                requireContext().contentResolver,
                LOCKSCREEN_WIDGETS_EXTRAS_KEY
            )?.split(",")?.map { it.trim() } ?: emptyList()

            WidgetPickerBottomSheetDialog.newInstance(current)
                .show(parentFragmentManager, "WidgetPicker")
            true
        } else {
            false
        }
    }
}
