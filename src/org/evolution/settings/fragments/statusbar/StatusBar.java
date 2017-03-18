/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.statusbar;

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
public class StatusBar extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "StatusBar";

    private static final String ICONS_CATEGORY_KEY = "status_bar_icons_category";
    private static final String BLUETOOTH_BATTERY_STATUS_KEY = "bluetooth_show_battery";

    private PreferenceCategory mIconsCategory;
    private Preference mBluetoothBatteryStatus;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.evolution_settings_status_bar);
        Context mContext = getActivity().getApplicationContext();

        mIconsCategory = (PreferenceCategory) findPreference(ICONS_CATEGORY_KEY);
        mBluetoothBatteryStatus = (Preference) findPreference(BLUETOOTH_BATTERY_STATUS_KEY);

        if (!DeviceUtils.deviceSupportsBluetooth(mContext)) {
            mIconsCategory.removePreference(mBluetoothBatteryStatus);
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
            new BaseSearchIndexProvider(R.xml.evolution_settings_status_bar);
}
