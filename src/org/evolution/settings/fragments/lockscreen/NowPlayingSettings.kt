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
package org.evolution.settings.fragments.lockscreen

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable
import com.android.settingslib.widget.FooterPreference
import org.evolution.settings.utils.PixelAmbientIndicationDetector

@SearchIndexable
class NowPlayingSettings : SettingsPreferenceFragment() {

    private var infoFooterPref: FooterPreference? = null

    private val isNativePixelAmbientIndication: Boolean by lazy {
        PixelAmbientIndicationDetector.shouldUseNativeAmbientIndication(requireContext())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.nowplaying_settings)

        infoFooterPref = findPreference("nowplaying_info_footer")

        if (isNativePixelAmbientIndication) {
            disableAllPreferencesForNativePixel()
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return super.onPreferenceTreeClick(preference)
    }

    private fun disableAllPreferencesForNativePixel() {
        val screen = preferenceScreen
        for (i in 0 until screen.preferenceCount) {
            disableRecursively(screen.getPreference(i))
        }
        infoFooterPref?.title = getString(R.string.nowplaying_pixel_native_footer)
    }

    private fun disableRecursively(pref: Preference) {
        if (pref === infoFooterPref) return
        pref.isEnabled = false
        if (pref is PreferenceGroup) {
            for (i in 0 until pref.preferenceCount) {
                disableRecursively(pref.getPreference(i))
            }
        }
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
                return super.getNonIndexableKeys(context)
            }
        }
    }
}
