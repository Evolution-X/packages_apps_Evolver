/*
 * Copyright (C) 2019-2025 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen;

import android.content.Context;
import android.os.Bundle;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

@SearchIndexable
public class LockScreenWeather extends SettingsPreferenceFragment {

    public static final String TAG = "LockScreenWeather";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.evolution_settings_lock_screen_weather);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    /**
     * For search
     */
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_lock_screen_weather) {

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);

                    return keys;
                }
            };
}
