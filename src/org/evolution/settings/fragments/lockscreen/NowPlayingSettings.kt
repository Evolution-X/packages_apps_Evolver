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
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable

import org.evolution.settings.preferences.SystemSettingSwitchPreference

@SearchIndexable
class NowPlayingSettings : SettingsPreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    private var compactStylePref: SystemSettingSwitchPreference? = null
    private var artistTextSizePref: Preference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.nowplaying_settings)

        compactStylePref = findPreference("nowplaying_use_compact_style")
        artistTextSizePref = findPreference("nowplaying_artist_text_size")

        compactStylePref?.onPreferenceChangeListener = this

        updateArtistTextSizeVisibility()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return super.onPreferenceTreeClick(preference)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        if (preference.key == "nowplaying_use_compact_style") {
            updateArtistTextSizeVisibility()
        }
        return true
    }

    private fun updateArtistTextSizeVisibility() {
        val isCompactEnabled = Settings.System.getInt(
            contentResolver,
            "nowplaying_use_compact_style",
            0
        ) == 1

        artistTextSizePref?.isVisible = !isCompactEnabled
    }

    override fun getMetricsCategory(): Int {
        return MetricsProto.MetricsEvent.EVOLVER
    }

    companion object {
        const val TAG = "NowPlayingSettings"

        /** For search */
        @JvmField
        val SEARCH_INDEX_DATA_PROVIDER = object : BaseSearchIndexProvider(R.xml.nowplaying_settings) {
            override fun getNonIndexableKeys(context: Context): List<String> {
                val keys = super.getNonIndexableKeys(context).toMutableList()
                
                val isCompactEnabled = Settings.System.getInt(
                    context.contentResolver,
                    "nowplaying_use_compact_style",
                    0
                ) == 1
                
                if (isCompactEnabled) {
                    keys.add("nowplaying_artist_text_size")
                }
                
                return keys
            }
        }
    }
}
