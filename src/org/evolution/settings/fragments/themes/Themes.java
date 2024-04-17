/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.Preference.OnPreferenceChangeListener;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import org.evolution.settings.utils.DeviceUtils;

@SearchIndexable
public class Themes extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Themes";

    private static final String STATUS_BAR_ICONS_CATEGORY_KEY = "themes_status_bar_icons_category";
    private static final String SIGNAL_ICON_KEY = "android.theme.customization.signal_icon";

    private PreferenceCategory mStatusBarIconsCategory;
    private Preference mSignalIcon;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.evolution_settings_themes);
        Context mContext = getActivity().getApplicationContext();

        mStatusBarIconsCategory = (PreferenceCategory) findPreference(STATUS_BAR_ICONS_CATEGORY_KEY);
        mSignalIcon = (Preference) findPreference(SIGNAL_ICON_KEY);

        if (!DeviceUtils.deviceSupportsMobileData(mContext)) {
            mStatusBarIconsCategory.removePreference(mSignalIcon);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_themes);
}
