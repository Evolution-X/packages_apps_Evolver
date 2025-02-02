/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.themes;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.evolution.settings.preferences.CustomSeekBarPreference;
import org.evolution.settings.utils.SystemUtils;

import java.util.List;

@SearchIndexable
public class Wallpaper extends SettingsPreferenceFragment
            implements Preference.OnPreferenceChangeListener {

    public static final String TAG = "Wallpaper";

    private Preference mBlurWpPref;
    private Preference mBlurWpStylePref;
    private Preference mDimPref;
    private Preference mDimLvlPref;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.evolution_settings_wallpaper);
        mBlurWpPref = findPreference("persist.sys.wallpaper.blur_enabled");
        mBlurWpPref.setOnPreferenceChangeListener(this);
        mBlurWpStylePref = findPreference("persist.sys.wallpaper.blur_type");
        mBlurWpStylePref.setOnPreferenceChangeListener(this);
        mDimPref = findPreference("persist.sys.wallpaper.dim_enabled");
        mDimPref.setOnPreferenceChangeListener(this);
        mDimLvlPref = findPreference("persist.sys.wallpaper.dim_level");
        mDimLvlPref.setOnPreferenceChangeListener(this);
    }

    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        if (preference == mBlurWpPref
                || preference == mBlurWpStylePref
                || preference == mDimPref
                || preference == mDimLvlPref) {
          if (preference == mDimLvlPref) {
              android.os.SystemProperties.set("persist.sys.wallpaper.dim_level", newValue.toString());
          }
          SystemUtils.showSystemUiRestartDialog(context);
          return true;
        }
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    /**
     * For search
     */
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_wallpaper) {

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);

                    return keys;
                }
            };
}
